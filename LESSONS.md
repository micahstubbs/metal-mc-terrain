# Lessons Learned

Append-only log of debugging insights from the metal-mc-terrain project.

---

## 2026-03-28T00:15 - CAMetalLayer Cannot Overlay GLFW's _NSOpenGLViewBackingLayer

**Problem**: Metal terrain rendered correctly to a CAMetalLayer but was completely invisible on screen. Tried three compositing approaches: subview with zPosition, sublayer of contentView, child NSWindow overlay. All failed.

**Root Cause**: GLFW on macOS creates a `GLFWContentView` with `_NSOpenGLViewBackingLayer` (a private Apple class) that draws OVER any sibling views, sublayers, or even child windows during its display cycle. The GL backing layer operates outside the normal layer compositing tree.

**Lesson**: On macOS with GLFW/LWJGL, you cannot composite a CAMetalLayer on top of GL content using any view/layer/window hierarchy approach. The only reliable method is IOSurface sharing: render Metal to an IOSurface-backed texture, then bind it as a GL texture via `CGLTexImageIOSurface2D` and draw a fullscreen GL quad within the existing GL context.

**Solution**: IOSurface compositing pipeline - Metal renders to shared GPU surface, Java blits it to GL as a textured quad.

**Prevention**: When mixing Metal and OpenGL on macOS, always use IOSurface sharing for compositing. Don't waste time on layer hierarchy approaches.

---

## 2026-03-28T00:15 - Forge SRG Field Names Don't Match Mojang Names at Runtime

**Problem**: `Matrix4f` field access via reflection returned identity matrix. The code tried field names like `m00`, `m01`, etc. (Mojang names) but at runtime Forge uses SRG names like `field_226575_a_` through `field_226590_p_`.

**Root Cause**: ForgeGradle's `official` mappings channel uses Mojang method/field names in development, but the production jar uses SRG (Searge) intermediary names. Reflection at runtime must use SRG names or iterate fields by type.

**Lesson**: Never hardcode Mojang field names for runtime reflection in Forge mods. Use type-based field discovery (`getDeclaredFields()` filtered by type) or read values from GL state directly when possible.

**Solution**: Read projection matrix from `GL11.glGetFloatv(GL_PROJECTION_MATRIX)` and build view matrix from camera quaternion directly, bypassing Matrix4f reflection entirely.

**Prevention**: For any Forge mod that needs runtime reflection, always test with the production (reobfuscated) jar, not the dev environment. Prefer API methods or GL state reads over reflection.

---

## 2026-03-28T00:15 - Forge VertexBuffer Field Mapping is Reversed from SRG Expectations

**Problem**: VBO data read as all zeros. `glGetBufferSubData` returned empty data for what we thought was the VBO ID.

**Root Cause**: `field_177364_c` (SRG name suggesting "id") was actually the vertex count (value: 104), and `field_177365_a` (SRG name suggesting "vertexCount") was actually the GL VBO ID (value: 425). Verified by calling `glIsBuffer()` and `GL_BUFFER_SIZE` on both values.

**Lesson**: SRG field names do not reliably indicate field semantics. Always verify field identity empirically using runtime checks (e.g., `glIsBuffer()` for GL buffer IDs, checking if `value * stride == GL_BUFFER_SIZE` for vertex counts).

**Code Issue**:
```java
// Before (broken) - trusting SRG name mapping
if (f.getName().equals("field_177364_c")) idCandidate = f;      // WRONG: this is vertexCount
if (f.getName().equals("field_177365_a")) countCandidate = f;   // WRONG: this is the VBO ID

// After (fixed) - verified via glIsBuffer + GL_BUFFER_SIZE
if (f.getName().equals("field_177365_a")) idCandidate = f;      // Verified: glIsBuffer=true, has data
if (f.getName().equals("field_177364_c")) countCandidate = f;   // Verified: value*32 == GL_BUFFER_SIZE
```

**Prevention**: For any GL object ID discovered via reflection, validate with `glIsBuffer()`, `glIsTexture()`, etc. before using. For counts, verify `count * stride == buffer_size`.

---

## 2026-03-28T00:15 - RenderWorldLastEvent Timing: Visible Chunk List and GL State

**Problem**: Two separate timing issues: (1) vanilla `field_175009_l` (renderChunksInFrustum) was always empty, (2) GL modelview matrix was identity.

**Root Cause**: By the time `RenderWorldLastEvent` fires, Minecraft has: (a) cleared the vanilla visible chunks set, (b) reset the GL modelview to identity. Forge 36.2.34 keeps chunks in `field_72755_R` (ObjectArrayList of LocalRenderInformationContainer) which persists through the event. The GL projection matrix is NOT reset.

**Lesson**: At `RenderWorldLastEvent` time in Forge 36.2.34: use `field_72755_R` for visible chunks (not `field_175009_l`), use `GL_PROJECTION_MATRIX` for projection (still valid), and get view rotation from MatrixStack or camera quaternion (GL modelview is identity).

**Prevention**: When hooking into late render events, always verify which data structures are still valid at that point in the frame lifecycle by logging their state.

---

## 2026-03-28T00:15 - terrainEndFrame Must Be in Finally Block

**Problem**: After the first exception during Metal terrain rendering, all subsequent frames showed 0 draws forever.

**Root Cause**: `terrainEndFrame()` was inside the try block. Any exception during chunk upload/render skipped `endFrame`, leaving native `t_frameActive=true` permanently. Every subsequent `beginFrame` returned false because it checks `if (t_frameActive) return false`.

**Lesson**: Any begin/end frame API where `begin` sets a flag and `end` clears it MUST have the `end` call in a finally block. A single exception permanently breaks the pipeline otherwise.

**Code Issue**:
```java
// Before (broken)
try {
    beginFrame();
    render();      // exception here = endFrame never called
    endFrame();
} catch (...) {}

// After (fixed)
boolean started = false;
try {
    started = beginFrame();
    render();
} finally {
    if (started) endFrame();  // always called
}
```

**Prevention**: Any paired begin/end, lock/unlock, or open/close API should use try/finally by default.

---

## 2026-03-28T00:15 - IOSurface bytesPerRow Must Be 16-Byte Aligned

**Problem**: IOSurface creation failed with "bytesPerRow must be aligned to 16 bytes".

**Root Cause**: `width * 4` (bytes per row for BGRA) was not a multiple of 16 for certain window sizes.

**Lesson**: IOSurface requires 16-byte row alignment. Always round up: `bytesPerRow = ((width * 4 + 15) / 16) * 16`.

**Prevention**: When creating IOSurfaces, always align bytesPerRow to 16 bytes regardless of pixel format.

---

## Meta-Lessons

- **Verify empirically, don't trust names**: SRG field names, API docs, and even code comments can be wrong. Use runtime diagnostics (glIsBuffer, pixel reads, buffer size checks) to verify assumptions.
- **IOSurface pixel reads are the definitive test**: Reading pixels from the IOSurface after Metal renders definitively proves whether the GPU pipeline works, independent of display compositing.
- **Automated screenshots enable autonomous debugging**: Using `ScreenShotHelper.grab()` from within the mod captures the GL framebuffer to PNG, enabling verification without human interaction.
- **Systematic debugging with one-variable-at-a-time**: The debug magenta fragment shader + fullscreen triangle vertex shader isolated the pipeline stages. When the fullscreen triangle showed magenta in the IOSurface, it proved Metal+IOSurface works and narrowed the bug to vertex data.
