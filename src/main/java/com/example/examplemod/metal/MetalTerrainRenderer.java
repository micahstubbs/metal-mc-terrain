package com.example.examplemod.metal;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase D: Metal terrain renderer.
 *
 * Intercepts visible chunk sections from WorldRenderer, reads their vertex data
 * from GL VBOs, uploads to Metal mega-buffers, and renders terrain via Metal.
 *
 * v0.1: Renders terrain via Metal to offscreen target (not visible). Measures GPU time.
 * v0.2: Renders terrain to CAMetalLayer (visible on screen). Toggle with F8.
 *       Entities behind opaque terrain are hidden (depth artifact, fixed in v0.3).
 */
public class MetalTerrainRenderer {

    private static final Logger LOGGER = LogManager.getLogger();

    // Render type indices (match native METAL_RT_* constants)
    private static final int RT_SOLID = 0;
    private static final int RT_CUTOUT_MIPPED = 1;
    private static final int RT_CUTOUT = 2;
    private static final int RT_TRANSLUCENT = 3;

    // Reflection fields
    private Field renderChunksField;         // WorldRenderer.renderChunksInFrustum (list of ChunkInfo)
    private Field chunkInfoChunkField;       // WorldRenderer.RenderChunkInfo.chunk
    private Field vboIdField;                // VertexBuffer.id (GL VBO name)
    private Field vboVertexCountField;       // VertexBuffer.vertexCount
    private boolean reflectionReady = false;
    private boolean reflectionFailed = false;

    // VBO data cache: maps VBO GL id -> cached vertex data
    // Invalidated when vertex count changes (chunk recompiled)
    private final Map<Integer, CachedVBOData> vboCache = new HashMap<>();

    // Reusable read buffer for glGetBufferSubData
    private ByteBuffer readBuffer;
    private static final int READ_BUFFER_SIZE = 4 * 1024 * 1024; // 4MB

    // Texture state
    private boolean texturesImported = false;
    private int lastAtlasTexId = -1;

    // Metal terrain always renders on-screen (v0.2 default)
    private static volatile boolean active = false;

    // Stats (totals)
    private long lastMetalGPUTimeNanos = 0;
    private int lastMetalDrawCount = 0;
    private int lastMetalVertexCount = 0;
    private int chunksUploaded = 0;
    private int chunksCached = 0;
    private long uploadTimeNanos = 0;

    // Per-render-type CPU timing (nanoseconds)
    private static final String[] RT_NAMES = {"solid", "cutout_mipped", "cutout", "translucent"};
    private final long[] rtCpuNanos = new long[4];
    private final int[] rtChunkCount = new int[4];
    private final int[] rtDrawCount = new int[4];
    private final int[] rtVertexCount = new int[4];

    // Frame counter for texture refresh
    private int frameCount = 0;

    // GL texture for IOSurface compositing
    private int glIOSurfaceTexId = -1;
    // GL_TEXTURE_RECTANGLE constant (0x84F5) - not in all LWJGL versions
    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;

    private static class CachedVBOData {
        ByteBuffer data;      // direct ByteBuffer with vertex data
        int vertexCount;      // number of vertices
        int vboId;            // GL VBO name for identity
    }

    public MetalTerrainRenderer() {
        readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE);
        readBuffer.order(ByteOrder.nativeOrder());
    }

    /**
     * Initialize reflection access to WorldRenderer internals.
     */
    private boolean initReflection() {
        if (reflectionReady) return true;
        if (reflectionFailed) return false;

        try {
            // WorldRenderer.renderChunksInFrustum -- the list of visible chunk info objects
            // In official mappings this is "renderChunksInFrustum" but may be obfuscated at runtime.
            // Try multiple possible field names.
            Class<?> wrClass = WorldRenderer.class;
            // Forge 36.2.34 uses field_72755_R (ObjectArrayList<LocalRenderInformationContainer>)
            // which is populated during setupRender and NOT cleared before RenderWorldLastEvent.
            // The vanilla field_175009_l (renderChunksInFrustum) is a LinkedHashSet that gets
            // cleared before our event fires.
            // Try the Forge field first, then fall back to vanilla.
            renderChunksField = findField(wrClass, "field_72755_R");
            if (renderChunksField == null) {
                renderChunksField = findField(wrClass, "renderChunksInFrustum", "field_175009_l");
            }
            if (renderChunksField == null) {
                // Try by type: any Collection field (could be List, Set, LinkedHashSet, etc.)
                for (Field f : wrClass.getDeclaredFields()) {
                    if (Collection.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(Minecraft.getInstance().levelRenderer);
                        if (val instanceof Collection) {
                            Collection<?> coll = (Collection<?>) val;
                            if (!coll.isEmpty()) {
                                Object first = coll.iterator().next();
                                // Check if elements have a ChunkRender field
                                for (Field cf : first.getClass().getDeclaredFields()) {
                                    if (ChunkRenderDispatcher.ChunkRender.class.isAssignableFrom(cf.getType())) {
                                        renderChunksField = f;
                                        chunkInfoChunkField = cf;
                                        cf.setAccessible(true);
                                        break;
                                    }
                                }
                                // Also check if the element IS a ChunkRender directly
                                if (renderChunksField == null &&
                                    first instanceof ChunkRenderDispatcher.ChunkRender) {
                                    renderChunksField = f;
                                }
                            }
                            if (renderChunksField != null) break;
                        }
                    }
                }
            }

            if (renderChunksField == null) {
                LOGGER.warn("[METAL-TERRAIN] Could not find renderChunksInFrustum field");
                reflectionFailed = true;
                return false;
            }
            renderChunksField.setAccessible(true);

            // Find the ChunkRender field within ChunkInfo if not already found
            if (chunkInfoChunkField == null) {
                Object val = renderChunksField.get(Minecraft.getInstance().levelRenderer);
                if (val instanceof Collection && !((Collection<?>)val).isEmpty()) {
                    Object first = ((Collection<?>)val).iterator().next();
                    if (first instanceof ChunkRenderDispatcher.ChunkRender) {
                        // Elements ARE ChunkRender directly, no wrapper
                        chunkInfoChunkField = null;  // signal: no wrapper
                    } else {
                        for (Field cf : first.getClass().getDeclaredFields()) {
                            cf.setAccessible(true);
                            if (ChunkRenderDispatcher.ChunkRender.class.isAssignableFrom(cf.getType())) {
                                chunkInfoChunkField = cf;
                                break;
                            }
                        }
                    }
                }
            }

            // VertexBuffer fields -- find by type, not by name, since SRG names vary.
            // VertexBuffer has: int id (GL VBO name), int vertexCount, VertexFormat format
            // We need both int fields. Identify them by examining their runtime values:
            // id > 0 (GL name), vertexCount >= 0.
            // Fallback: try named fields first.
            vboIdField = findField(VertexBuffer.class, "id", "field_177364_c");
            vboVertexCountField = findField(VertexBuffer.class, "vertexCount", "field_177363_b");

            // Verify the fields are actually int type (Forge may reorder/rename)
            if (vboIdField != null && vboIdField.getType() != int.class) {
                LOGGER.warn("[METAL-TERRAIN] vboIdField {} is type {}, not int - resetting",
                    vboIdField.getName(), vboIdField.getType().getName());
                vboIdField = null;
            }
            if (vboVertexCountField != null && vboVertexCountField.getType() != int.class) {
                LOGGER.warn("[METAL-TERRAIN] vboVertexCountField {} is type {}, not int - resetting",
                    vboVertexCountField.getName(), vboVertexCountField.getType().getName());
                vboVertexCountField = null;
            }

            // If named fields failed, find all int fields on VertexBuffer
            if (vboIdField == null || vboVertexCountField == null) {
                java.util.List<Field> intFields = new java.util.ArrayList<>();
                for (Field f : VertexBuffer.class.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        intFields.add(f);
                    }
                }
                LOGGER.info("[METAL-TERRAIN] Found {} int fields on VertexBuffer", intFields.size());
                for (Field f : intFields) {
                    LOGGER.info("[METAL-TERRAIN]   int field: {}", f.getName());
                }
                if (intFields.size() >= 2) {
                    // field_177364_c = id (GL VBO name), field_177365_a = vertexCount
                    // Order in getDeclaredFields may not match declaration order,
                    // so identify by name if possible
                    Field idCandidate = null, countCandidate = null;
                    for (Field f : intFields) {
                        if (f.getName().equals("field_177364_c") || f.getName().equals("id")) {
                            idCandidate = f;
                        } else if (f.getName().equals("field_177365_a") || f.getName().equals("vertexCount")) {
                            countCandidate = f;
                        }
                    }
                    if (idCandidate != null && countCandidate != null) {
                        vboIdField = idCandidate;
                        vboVertexCountField = countCandidate;
                    } else {
                        // Fallback: first = vertexCount (usually declared first), second = id
                        vboIdField = intFields.get(1);
                        vboVertexCountField = intFields.get(0);
                    }
                    LOGGER.info("[METAL-TERRAIN] vboIdField={}, vboVertexCountField={}",
                        vboIdField.getName(), vboVertexCountField.getName());
                } else if (intFields.size() == 1) {
                    // Only one int field -- it's the id. vertexCount might be accessed differently.
                    vboIdField = intFields.get(0);
                    LOGGER.warn("[METAL-TERRAIN] Only 1 int field on VertexBuffer, vertexCount unavailable");
                }
            }
            if (vboIdField != null) vboIdField.setAccessible(true);
            if (vboVertexCountField != null) vboVertexCountField.setAccessible(true);

            reflectionReady = (renderChunksField != null && vboIdField != null &&
                               vboVertexCountField != null);

            if (reflectionReady) {
                LOGGER.info("[METAL-TERRAIN] Reflection initialized successfully");
                LOGGER.info("[METAL-TERRAIN]   renderChunks: {}", renderChunksField.getName());
                LOGGER.info("[METAL-TERRAIN]   chunkInfo.chunk: {}",
                        chunkInfoChunkField != null ? chunkInfoChunkField.getName() : "N/A");
                LOGGER.info("[METAL-TERRAIN]   vboId: {}", vboIdField.getName());
                LOGGER.info("[METAL-TERRAIN]   vertexCount: {}", vboVertexCountField.getName());
            } else {
                LOGGER.warn("[METAL-TERRAIN] Reflection incomplete");
                reflectionFailed = true;
            }

            return reflectionReady;
        } catch (Exception e) {
            LOGGER.error("[METAL-TERRAIN] Reflection init failed", e);
            reflectionFailed = true;
            return false;
        }
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /**
     * Called each frame after world rendering to perform Metal terrain rendering.
     * v0.1 (renderToScreen=false): renders to offscreen Metal target.
     * v0.2 (renderToScreen=true): renders to CAMetalLayer (visible on screen).
     */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!MetalBridge.isAvailable()) return;
        if (!MetalBridge.isTerrainReady()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        frameCount++;

        // Initialize reflection on first call
        if (!initReflection()) return;

        boolean frameStarted = false;
        try {
            long frameStart = System.nanoTime();

            // Import textures (first time or periodically)
            if (!texturesImported || frameCount % 600 == 0) {
                importTextures();
            }

            // Get camera position
            ActiveRenderInfo camera = mc.gameRenderer.getMainCamera();
            Vector3d cameraPos = camera.getPosition();
            double camX = cameraPos.x;
            double camY = cameraPos.y;
            double camZ = cameraPos.z;

            // Get view-projection matrix from the event's MatrixStack
            MatrixStack matrixStack = event.getMatrixStack();
            Matrix4f viewMatrix = matrixStack.last().pose();
            Matrix4f projMatrix = event.getProjectionMatrix();

            // Combine view * projection
            Matrix4f viewProj = projMatrix.copy();
            viewProj.multiply(viewMatrix);
            float[] viewProjArr = matrix4fToArray(viewProj);

            // Fog parameters (approximate)
            float fogStart = Math.max(0, mc.options.renderDistance * 16 - 32);
            float fogEnd = mc.options.renderDistance * 16;
            float[] fogColor = {0.7f, 0.8f, 1.0f, 1.0f};

            // Begin batched frame -- always render to screen
            int fbWidth = mc.getWindow().getWidth();
            int fbHeight = mc.getWindow().getHeight();

            frameStarted = MetalBridge.terrainBeginFrame(fbWidth, fbHeight, true);
            if (!frameStarted) {
                if (frameCount % 300 == 1) {
                    LOGGER.warn("[METAL-TERRAIN] begin_frame failed, skipping frame");
                }
                return;
            }

            // Upload visible chunks and render each type, timing each
            int[] rtTypes = {RT_SOLID, RT_CUTOUT_MIPPED, RT_CUTOUT, RT_TRANSLUCENT};
            RenderType[] rtRenderTypes = {RenderType.solid(), RenderType.cutoutMipped(),
                                          RenderType.cutout(), RenderType.translucent()};
            float[] rtAlpha = {0.0f, 0.5f, 0.1f, 0.0f};

            for (int i = 0; i < 4; i++) {
                long rtStart = System.nanoTime();
                uploadAndRender(rtTypes[i], rtRenderTypes[i], camX, camY, camZ,
                               viewProjArr, fogStart, fogEnd, fogColor, rtAlpha[i]);
                rtCpuNanos[i] = System.nanoTime() - rtStart;
            }

            long frameEnd = System.nanoTime();
            uploadTimeNanos = frameEnd - frameStart;

            // Read back stats (total + per-RT)
            lastMetalGPUTimeNanos = MetalBridge.terrainGetGPUTimeNanos();
            lastMetalDrawCount = MetalBridge.terrainGetDrawCount();
            lastMetalVertexCount = MetalBridge.terrainGetVertexCount();

            for (int i = 0; i < 4; i++) {
                rtDrawCount[i] = MetalBridge.terrainGetRTDrawCount(i);
                rtVertexCount[i] = MetalBridge.terrainGetRTVertexCount(i);
            }

        } catch (Throwable e) {
            // Catch Throwable (not just Exception) to handle UnsatisfiedLinkError etc.
            if (frameCount % 300 == 1) {
                LOGGER.error("[METAL-TERRAIN] Frame error", e);
            }
        } finally {
            // Always end the frame if it was started, even if an exception occurred.
            // Without this, t_frameActive stays true and all future frames are skipped.
            if (frameStarted) {
                MetalBridge.terrainEndFrame();
                // Blit the Metal-rendered IOSurface to GL as a fullscreen quad.
                // This composites Metal terrain on top of GL content within the
                // same GL context, bypassing all CAMetalLayer compositing issues.
                blitIOSurfaceToGL();
            }
        }
    }

    /**
     * Draw the Metal-rendered IOSurface as a fullscreen GL quad.
     * Uses CGLTexImageIOSurface2D to bind the shared IOSurface as a
     * GL_TEXTURE_RECTANGLE, then draws it over the current GL framebuffer.
     */
    private void blitIOSurfaceToGL() {
        try {
            int surfaceId = MetalBridge.getIOSurfaceID();
            if (surfaceId <= 0) return;

            int w = MetalBridge.getIOSurfaceWidth();
            int h = MetalBridge.getIOSurfaceHeight();
            if (w <= 0 || h <= 0) return;

            // Create GL texture on first use
            if (glIOSurfaceTexId <= 0) {
                glIOSurfaceTexId = GL11.glGenTextures();
                LOGGER.info("[METAL-BLIT] Created GL texture {} for IOSurface {}", glIOSurfaceTexId, surfaceId);
            }

            // Bind the IOSurface to our GL texture (via native JNI call that uses CGLTexImageIOSurface2D)
            boolean bound = MetalBridge.bindIOSurfaceToGLTexture(glIOSurfaceTexId);
            if (!bound) {
                if (frameCount % 300 == 1) {
                    LOGGER.warn("[METAL-BLIT] Failed to bind IOSurface to GL texture");
                }
                return;
            }

            // Save GL state
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, 1, 0, 1, -1, 1);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();

            // Set up for alpha-blended fullscreen quad
            GL11.glEnable(GL_TEXTURE_RECTANGLE);
            GL11.glBindTexture(GL_TEXTURE_RECTANGLE, glIOSurfaceTexId);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);

            // Draw fullscreen quad
            // GL_TEXTURE_RECTANGLE uses pixel coordinates (0..w, 0..h), not normalized 0..1
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0, h);  GL11.glVertex2f(0, 0);  // bottom-left
            GL11.glTexCoord2f(w, h);  GL11.glVertex2f(1, 0);  // bottom-right
            GL11.glTexCoord2f(w, 0);  GL11.glVertex2f(1, 1);  // top-right
            GL11.glTexCoord2f(0, 0);  GL11.glVertex2f(0, 1);  // top-left
            GL11.glEnd();

            // Restore GL state
            GL11.glDisable(GL_TEXTURE_RECTANGLE);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();

        } catch (Throwable e) {
            if (frameCount % 300 == 1) {
                LOGGER.error("[METAL-BLIT] GL blit error", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void uploadAndRender(int metalRT, RenderType renderType,
                                  double camX, double camY, double camZ,
                                  float[] viewProj, float fogStart, float fogEnd,
                                  float[] fogColor, float alphaThreshold) throws Exception {
        WorldRenderer wr = Minecraft.getInstance().levelRenderer;
        Collection<?> renderChunks = (Collection<?>) renderChunksField.get(wr);

        MetalBridge.terrainClearChunks(metalRT);
        chunksUploaded = 0;
        chunksCached = 0;

        // One-time diagnostic: log what we're iterating over
        if (frameCount == 15 && metalRT == RT_SOLID) {
            LOGGER.info("[METAL-DIAG] renderChunks field={}, type={}, size={}",
                renderChunksField.getName(), renderChunks.getClass().getName(), renderChunks.size());
            // Dump ALL collection fields on WorldRenderer to find the right one
            WorldRenderer wr2 = Minecraft.getInstance().levelRenderer;
            for (Field f : wr2.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(wr2);
                    if (val instanceof Collection) {
                        Collection<?> c = (Collection<?>) val;
                        String elemType = c.isEmpty() ? "?" : c.iterator().next().getClass().getSimpleName();
                        LOGGER.info("[METAL-DIAG] WR field '{}' type={} size={} elemType={}",
                            f.getName(), c.getClass().getSimpleName(), c.size(), elemType);
                    }
                } catch (Exception ignored) {}
            }
        }

        int chunkIndex = 0;
        int diagSkipEmpty = 0, diagSkipVbo = 0, diagSkipId = 0, diagSkipData = 0;
        for (Object chunkInfo : renderChunks) {
            // Get the ChunkRender from ChunkInfo
            ChunkRenderDispatcher.ChunkRender chunk;
            if (chunkInfoChunkField != null) {
                chunk = (ChunkRenderDispatcher.ChunkRender) chunkInfoChunkField.get(chunkInfo);
            } else if (chunkInfo instanceof ChunkRenderDispatcher.ChunkRender) {
                chunk = (ChunkRenderDispatcher.ChunkRender) chunkInfo;
            } else {
                // Lazy resolve: collection was empty at init time, so we couldn't
                // determine the wrapper type. Now we have a real element -- find
                // the ChunkRender field inside it.
                for (Field cf : chunkInfo.getClass().getDeclaredFields()) {
                    if (ChunkRenderDispatcher.ChunkRender.class.isAssignableFrom(cf.getType())) {
                        cf.setAccessible(true);
                        chunkInfoChunkField = cf;
                        LOGGER.info("[METAL-TERRAIN] Lazy-resolved chunkInfo.chunk: {}", cf.getName());
                        break;
                    }
                }
                if (chunkInfoChunkField != null) {
                    chunk = (ChunkRenderDispatcher.ChunkRender) chunkInfoChunkField.get(chunkInfo);
                } else {
                    LOGGER.warn("[METAL-TERRAIN] Cannot find ChunkRender field in {}", chunkInfo.getClass().getName());
                    continue;
                }
            }

            // Check if this chunk has data for this render type
            if (chunk.getCompiledChunk().isEmpty(renderType)) { diagSkipEmpty++; continue; }

            // Get the VertexBuffer for this render type
            VertexBuffer vbo = chunk.getBuffer(renderType);
            if (vbo == null) { diagSkipVbo++; continue; }

            int vboId = vboIdField.getInt(vbo);
            int vertexCount = vboVertexCountField.getInt(vbo);
            if (frameCount == 15 && metalRT == RT_SOLID && chunkIndex == 0) {
                LOGGER.info("[METAL-DIAG] First chunk: vboId={}, vertexCount={}", vboId, vertexCount);
                // Dump ALL fields on this VBO for debugging
                for (Field df : vbo.getClass().getDeclaredFields()) {
                    try {
                        df.setAccessible(true);
                        Object val = df.get(vbo);
                        LOGGER.info("[METAL-DIAG] VBO field '{}' type={} value={}",
                            df.getName(), df.getType().getSimpleName(), val);
                    } catch (Exception ignored) {}
                }
            }
            if (vboId <= 0 || vertexCount <= 0) { diagSkipId++; continue; }

            // Get chunk offset (camera-relative)
            BlockPos origin = chunk.getOrigin();
            float offsetX = (float)(origin.getX() - camX);
            float offsetY = (float)(origin.getY() - camY);
            float offsetZ = (float)(origin.getZ() - camZ);

            // Get vertex data from cache or read from GL
            ByteBuffer vertexData = getVertexData(vboId, vertexCount);
            if (vertexData == null) { diagSkipData++; continue; }

            // Upload to Metal
            MetalBridge.terrainSetChunk(metalRT, chunkIndex, vertexData, vertexCount,
                                        offsetX, offsetY, offsetZ);
            chunkIndex++;
        }

        // One-time diagnostic: why were chunks skipped?
        if (frameCount == 15 && metalRT == RT_SOLID) {
            LOGGER.info("[METAL-DIAG] RT_SOLID: uploaded={}, skipEmpty={}, skipVbo={}, skipId={}, skipData={}",
                chunkIndex, diagSkipEmpty, diagSkipVbo, diagSkipId, diagSkipData);
        }

        // Render this type
        if (chunkIndex > 0) {
            MetalBridge.terrainRender(metalRT, viewProj, fogStart, fogEnd, fogColor, alphaThreshold);
        }
    }

    /**
     * Get vertex data for a VBO, using cache when possible.
     * Reads from GL via glGetBufferSubData if not cached or if vertex count changed.
     */
    private ByteBuffer getVertexData(int vboId, int vertexCount) {
        CachedVBOData cached = vboCache.get(vboId);
        if (cached != null && cached.vertexCount == vertexCount) {
            chunksCached++;
            return cached.data;
        }

        // Read from GL
        int dataSize = vertexCount * 32;  // 32 bytes per vertex (BLOCK format)
        if (dataSize > READ_BUFFER_SIZE) {
            // Chunk too large for our read buffer, skip
            return null;
        }

        try {
            // Bind the VBO and read its data
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
            readBuffer.clear();
            readBuffer.limit(dataSize);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, readBuffer);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // Copy to persistent cache buffer
            ByteBuffer cacheData = ByteBuffer.allocateDirect(dataSize);
            cacheData.order(ByteOrder.nativeOrder());
            readBuffer.position(0);
            readBuffer.limit(dataSize);
            cacheData.put(readBuffer);
            cacheData.flip();

            CachedVBOData entry = new CachedVBOData();
            entry.data = cacheData;
            entry.vertexCount = vertexCount;
            entry.vboId = vboId;
            vboCache.put(vboId, entry);

            chunksUploaded++;
            return cacheData;
        } catch (Exception e) {
            // GL error - VBO might be invalid
            return null;
        }
    }

    /**
     * Import block atlas and lightmap textures from GL to Metal.
     */
    private void importTextures() {
        try {
            Minecraft mc = Minecraft.getInstance();

            // Block atlas texture
            int atlasTexId = mc.getTextureManager()
                    .getTexture(net.minecraft.client.renderer.texture.AtlasTexture.LOCATION_BLOCKS)
                    .getId();

            if (atlasTexId != lastAtlasTexId || !texturesImported) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlasTexId);
                int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

                if (width > 0 && height > 0) {
                    int dataSize = width * height * 4;
                    ByteBuffer pixels = ByteBuffer.allocateDirect(dataSize);
                    pixels.order(ByteOrder.nativeOrder());
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                                       GL11.GL_UNSIGNED_BYTE, pixels);
                    pixels.flip();

                    MetalBridge.terrainImportTexture(0, width, height, pixels, dataSize);
                    lastAtlasTexId = atlasTexId;
                    LOGGER.info("[METAL-TERRAIN] Imported block atlas {}x{}", width, height);
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            }

            // Lightmap texture (access via reflection since LightTexture.lightTexture is private)
            // Field is "lightTexture" in dev, "a" at runtime (SRG)
            try {
                net.minecraft.client.renderer.LightTexture lt = mc.gameRenderer.lightTexture();
                Field ltField = findField(lt.getClass(), "lightTexture", "a");
                if (ltField == null) {
                    // Brute force: find the DynamicTexture field
                    for (Field f : lt.getClass().getDeclaredFields()) {
                        if (net.minecraft.client.renderer.texture.DynamicTexture.class
                                .isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            ltField = f;
                            break;
                        }
                    }
                }
                if (ltField == null) throw new NoSuchFieldException("lightTexture/a");
                ltField.setAccessible(true);
                net.minecraft.client.renderer.texture.DynamicTexture dynTex =
                        (net.minecraft.client.renderer.texture.DynamicTexture) ltField.get(lt);
                int lightTexId = dynTex.getId();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, lightTexId);
                int lw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
                int lh = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
                if (lw > 0 && lh > 0) {
                    int dataSize = lw * lh * 4;
                    ByteBuffer pixels = ByteBuffer.allocateDirect(dataSize);
                    pixels.order(ByteOrder.nativeOrder());
                    GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                                       GL11.GL_UNSIGNED_BYTE, pixels);
                    pixels.flip();
                    MetalBridge.terrainImportTexture(1, lw, lh, pixels, dataSize);
                }
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            } catch (NoSuchFieldException e) {
                LOGGER.warn("[METAL-TERRAIN] Could not access lightmap texture field");
            }

            texturesImported = true;
        } catch (Exception e) {
            LOGGER.error("[METAL-TERRAIN] Texture import failed", e);
        }
    }

    /**
     * Convert a Matrix4f to a 16-float array (column-major).
     */
    private float[] matrix4fToArray(Matrix4f mat) {
        float[] arr = new float[16];
        // Matrix4f stores values in m[row][col] order
        // Metal expects column-major, so we need:
        // arr[col*4+row] = mat.m[row][col]
        // But Minecraft's Matrix4f is already column-major in its internal buffer
        // We need to use the store method or access the fields directly
        try {
            // Try using reflection to access the float fields
            // Matrix4f in Minecraft 1.16.5 has m00, m01, m02, m03, m10, ... fields
            Field m00 = Matrix4f.class.getDeclaredField("m00");
            m00.setAccessible(true);
            // The fields are stored as m[row][col] but we need column-major output
            String[] fieldNames = {
                "m00", "m10", "m20", "m30",  // column 0
                "m01", "m11", "m21", "m31",  // column 1
                "m02", "m12", "m22", "m32",  // column 2
                "m03", "m13", "m23", "m33"   // column 3
            };
            for (int i = 0; i < 16; i++) {
                Field f = Matrix4f.class.getDeclaredField(fieldNames[i]);
                f.setAccessible(true);
                arr[i] = f.getFloat(mat);
            }
        } catch (Exception e) {
            // Fallback: identity matrix
            arr[0] = arr[5] = arr[10] = arr[15] = 1.0f;
        }
        return arr;
    }

    /** Enable Metal terrain rendering. */
    public void activate() {
        active = true;
        MetalBridge.setTerrainActive(true);
        LOGGER.info("[METAL-TERRAIN] Active -- GL terrain suppressed via mixin");
    }

    /** Disable Metal terrain, re-enable GL terrain rendering. */
    public void deactivate() {
        active = false;
        MetalBridge.setTerrainActive(false);
        LOGGER.info("[METAL-TERRAIN] Deactivated -- GL terrain restored");
    }

    /** Whether Metal terrain is active (suppresses GL terrain). */
    public static boolean isActive() {
        return active;
    }

    // --- Stats accessors ---

    public long getMetalGPUTimeNanos() { return lastMetalGPUTimeNanos; }
    public int getMetalDrawCount() { return lastMetalDrawCount; }
    public int getMetalVertexCount() { return lastMetalVertexCount; }
    public int getChunksUploaded() { return chunksUploaded; }
    public int getChunksCached() { return chunksCached; }
    public long getUploadTimeNanos() { return uploadTimeNanos; }
    public int getCacheSize() { return vboCache.size(); }

    /** Per-render-type CPU time in nanoseconds. */
    public long getRTCpuNanos(int rt) { return rt >= 0 && rt < 4 ? rtCpuNanos[rt] : 0; }
    /** Per-render-type draw count. */
    public int getRTDrawCount(int rt) { return rt >= 0 && rt < 4 ? rtDrawCount[rt] : 0; }
    /** Per-render-type vertex count. */
    public int getRTVertexCount(int rt) { return rt >= 0 && rt < 4 ? rtVertexCount[rt] : 0; }
    /** Render type name. */
    public static String getRTName(int rt) { return rt >= 0 && rt < 4 ? RT_NAMES[rt] : "?"; }

    /** Clear the VBO cache (call on world unload). */
    public void clearCache() {
        vboCache.clear();
        texturesImported = false;
        lastAtlasTexId = -1;
    }
}
