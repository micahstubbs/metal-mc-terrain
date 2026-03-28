#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <QuartzCore/CAMetalLayer.h>
#import <Cocoa/Cocoa.h>
#import <IOSurface/IOSurface.h>

// ============================================================
// Metal Renderer - manages device, pipelines, and rendering
//
// Compositing strategy: IOSurface sharing.
// CAMetalLayer approaches all fail because _NSOpenGLViewBackingLayer
// (used by GLFW/LWJGL on macOS) draws OVER any subviews, sublayers,
// or even child windows. Instead, we render Metal to an offscreen
// texture backed by an IOSurface, then the Java side binds it as a
// GL texture and draws a fullscreen quad within the GL context.
// ============================================================

// Singleton state
static id<MTLDevice> g_device = nil;
static id<MTLCommandQueue> g_commandQueue = nil;

// IOSurface-backed shared texture (Metal renders here, GL reads it)
static IOSurfaceRef g_ioSurface = nil;
static id<MTLTexture> g_sharedColorTexture = nil;
static id<MTLTexture> g_sharedDepthTexture = nil;
static int g_surfaceWidth = 0;
static int g_surfaceHeight = 0;

// Legacy CAMetalLayer (kept for API compatibility, not used for compositing)
static CAMetalLayer *g_metalLayer = nil;
static NSView *g_metalView = nil;

// Phase B: triangle pipeline
static id<MTLRenderPipelineState> g_trianglePipeline = nil;
static id<MTLBuffer> g_triangleVertexBuffer = nil;
static id<MTLLibrary> g_library = nil;

// Frame timing
static id<MTLCommandBuffer> g_lastCommandBuffer = nil;
static uint64_t g_gpuTimeNanos = 0;

// When terrain v0.2 is active, skip test triangle rendering
static bool g_terrainActive = false;

// Forward declarations
static bool metal_renderer_compile_shaders(void);
static bool metal_renderer_create_triangle_pipeline(void);
static bool metal_renderer_create_shared_surface(int width, int height);

// Triangle vertex data (kept for debug)
static const float kTriangleVertices[] = {
     0.0f,  0.3f,  1.0f, 0.2f, 0.2f, 0.7f,
    -0.3f, -0.3f,  0.2f, 1.0f, 0.2f, 0.7f,
     0.3f, -0.3f,  0.2f, 0.2f, 1.0f, 0.7f,
};

// ============================================================
// IOSurface creation
// ============================================================

static bool metal_renderer_create_shared_surface(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    if (g_ioSurface && g_surfaceWidth == width && g_surfaceHeight == height) return true;

    // Release old surface
    if (g_ioSurface) {
        CFRelease(g_ioSurface);
        g_ioSurface = nil;
    }
    g_sharedColorTexture = nil;

    // Create IOSurface properties
    NSDictionary *props = @{
        (id)kIOSurfaceWidth: @(width),
        (id)kIOSurfaceHeight: @(height),
        (id)kIOSurfaceBytesPerElement: @(4),
        (id)kIOSurfacePixelFormat: @((uint32_t)'BGRA'),
        (id)kIOSurfaceBytesPerRow: @(width * 4),
    };

    g_ioSurface = IOSurfaceCreate((__bridge CFDictionaryRef)props);
    if (!g_ioSurface) {
        NSLog(@"[METAL] Failed to create IOSurface %dx%d", width, height);
        return false;
    }

    // Create Metal texture backed by the IOSurface
    MTLTextureDescriptor *td = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
        width:width height:height mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModeShared;

    g_sharedColorTexture = [g_device newTextureWithDescriptor:td
                                                   iosurface:g_ioSurface
                                                       plane:0];
    if (!g_sharedColorTexture) {
        NSLog(@"[METAL] Failed to create Metal texture from IOSurface");
        CFRelease(g_ioSurface);
        g_ioSurface = nil;
        return false;
    }

    // Create depth texture (not shared, Metal-only)
    MTLTextureDescriptor *depthTd = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float
        width:width height:height mipmapped:NO];
    depthTd.usage = MTLTextureUsageRenderTarget;
    depthTd.storageMode = MTLStorageModePrivate;
    g_sharedDepthTexture = [g_device newTextureWithDescriptor:depthTd];

    g_surfaceWidth = width;
    g_surfaceHeight = height;

    NSLog(@"[METAL] IOSurface created: %dx%d (id=%u)", width, height,
          IOSurfaceGetID(g_ioSurface));
    return true;
}

// ============================================================
// Init / Shutdown
// ============================================================

bool metal_renderer_init(long nsWindowPtr) {
    @autoreleasepool {
        NSWindow *window = (__bridge NSWindow *)(void *)nsWindowPtr;
        if (!window) {
            NSLog(@"[METAL] NSWindow pointer is null");
            return false;
        }

        // Get the default Metal device
        g_device = MTLCreateSystemDefaultDevice();
        if (!g_device) {
            NSLog(@"[METAL] No Metal device available");
            return false;
        }
        NSLog(@"[METAL] Device: %@", g_device.name);

        // Create command queue
        g_commandQueue = [g_device newCommandQueue];
        if (!g_commandQueue) {
            NSLog(@"[METAL] Failed to create command queue");
            return false;
        }

        // Create initial shared surface at window size
        NSView *contentView = [window contentView];
        NSRect bounds = contentView.bounds;
        CGFloat scale = window.backingScaleFactor;
        int w = (int)(bounds.size.width * scale);
        int h = (int)(bounds.size.height * scale);

        if (!metal_renderer_create_shared_surface(w, h)) {
            NSLog(@"[METAL] Failed to create shared surface");
            return false;
        }

        // Keep a reference to the view for size queries
        g_metalView = contentView;

        // Create a dummy CAMetalLayer for API compatibility
        // (terrain code calls metal_renderer_get_layer, but we won't use it for display)
        g_metalLayer = [CAMetalLayer layer];
        g_metalLayer.device = g_device;
        g_metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        g_metalLayer.framebufferOnly = NO;
        g_metalLayer.drawableSize = CGSizeMake(w, h);

        NSLog(@"[METAL] Surface size: %dx%d (scale %.1f)", w, h, scale);

        // Compile shaders
        if (!metal_renderer_compile_shaders()) {
            NSLog(@"[METAL] Shader compilation failed");
            return false;
        }

        // Create triangle pipeline
        if (!metal_renderer_create_triangle_pipeline()) {
            NSLog(@"[METAL] Triangle pipeline creation failed");
            return false;
        }

        NSLog(@"[METAL] Renderer initialized successfully (IOSurface mode)");
        return true;
    }
}

void metal_renderer_shutdown(void) {
    @autoreleasepool {
        g_metalView = nil;
        g_triangleVertexBuffer = nil;
        g_trianglePipeline = nil;
        g_library = nil;
        g_metalLayer = nil;
        g_sharedColorTexture = nil;
        g_sharedDepthTexture = nil;
        if (g_ioSurface) {
            CFRelease(g_ioSurface);
            g_ioSurface = nil;
        }
        g_commandQueue = nil;
        g_device = nil;
        NSLog(@"[METAL] Renderer shut down");
    }
}

// ============================================================
// Shader compilation
// ============================================================

static bool metal_renderer_compile_shaders(void) {
    NSError *error = nil;

    NSString *shaderSource = @
        "#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "\n"
        "struct TriangleVertex {\n"
        "    float2 position [[attribute(0)]];\n"
        "    float4 color    [[attribute(1)]];\n"
        "};\n"
        "\n"
        "struct TriangleOut {\n"
        "    float4 position [[position]];\n"
        "    float4 color;\n"
        "};\n"
        "\n"
        "vertex TriangleOut triangle_vertex(\n"
        "    TriangleVertex in [[stage_in]],\n"
        "    constant float &alpha [[buffer(1)]]\n"
        ") {\n"
        "    TriangleOut out;\n"
        "    out.position = float4(in.position, 0.0, 1.0);\n"
        "    out.color = float4(in.color.rgb, in.color.a * alpha);\n"
        "    return out;\n"
        "}\n"
        "\n"
        "fragment float4 triangle_fragment(TriangleOut in [[stage_in]]) {\n"
        "    return in.color;\n"
        "}\n";

    MTLCompileOptions *opts = [[MTLCompileOptions alloc] init];
    opts.languageVersion = MTLLanguageVersion2_4;

    g_library = [g_device newLibraryWithSource:shaderSource options:opts error:&error];
    if (!g_library) {
        NSLog(@"[METAL] Shader compilation error: %@", error);
        return false;
    }

    NSLog(@"[METAL] Compiled shaders from source");
    return true;
}

// ============================================================
// Pipeline creation
// ============================================================

static bool metal_renderer_create_triangle_pipeline(void) {
    NSError *error = nil;

    id<MTLFunction> vertexFunc = [g_library newFunctionWithName:@"triangle_vertex"];
    id<MTLFunction> fragmentFunc = [g_library newFunctionWithName:@"triangle_fragment"];

    if (!vertexFunc || !fragmentFunc) {
        NSLog(@"[METAL] Could not find triangle shader functions");
        return false;
    }

    MTLVertexDescriptor *vertexDesc = [[MTLVertexDescriptor alloc] init];
    vertexDesc.attributes[0].format = MTLVertexFormatFloat2;
    vertexDesc.attributes[0].offset = 0;
    vertexDesc.attributes[0].bufferIndex = 0;
    vertexDesc.attributes[1].format = MTLVertexFormatFloat4;
    vertexDesc.attributes[1].offset = 8;
    vertexDesc.attributes[1].bufferIndex = 0;
    vertexDesc.layouts[0].stride = 24;
    vertexDesc.layouts[0].stepFunction = MTLVertexStepFunctionPerVertex;

    MTLRenderPipelineDescriptor *pipeDesc = [[MTLRenderPipelineDescriptor alloc] init];
    pipeDesc.vertexFunction = vertexFunc;
    pipeDesc.fragmentFunction = fragmentFunc;
    pipeDesc.vertexDescriptor = vertexDesc;
    pipeDesc.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
    pipeDesc.colorAttachments[0].blendingEnabled = YES;
    pipeDesc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    pipeDesc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    pipeDesc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    pipeDesc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;

    g_trianglePipeline = [g_device newRenderPipelineStateWithDescriptor:pipeDesc error:&error];
    if (!g_trianglePipeline) {
        NSLog(@"[METAL] Pipeline state creation failed: %@", error);
        return false;
    }

    g_triangleVertexBuffer = [g_device newBufferWithBytes:kTriangleVertices
                                                   length:sizeof(kTriangleVertices)
                                                  options:MTLResourceStorageModeShared];

    NSLog(@"[METAL] Triangle pipeline created");
    return true;
}

// ============================================================
// Frame rendering (Phase B: triangle - not used when terrain active)
// ============================================================

static float g_frameCounter = 0.0f;

void metal_renderer_render_frame(void) {
    @autoreleasepool {
        if (!g_sharedColorTexture || !g_trianglePipeline) return;
        if (g_terrainActive) return;

        // Render triangle to shared texture (for debug)
        MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
        passDesc.colorAttachments[0].texture = g_sharedColorTexture;
        passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
        passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
        passDesc.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);

        id<MTLCommandBuffer> commandBuffer = [g_commandQueue commandBuffer];
        id<MTLRenderCommandEncoder> encoder =
            [commandBuffer renderCommandEncoderWithDescriptor:passDesc];

        [encoder setRenderPipelineState:g_trianglePipeline];
        [encoder setVertexBuffer:g_triangleVertexBuffer offset:0 atIndex:0];

        g_frameCounter += 0.02f;
        float alpha = 0.4f + 0.3f * sinf(g_frameCounter);
        [encoder setVertexBytes:&alpha length:sizeof(float) atIndex:1];

        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [encoder endEncoding];

        [commandBuffer commit];
    }
}

// ============================================================
// Diagnostics
// ============================================================

const char* metal_renderer_get_device_name(void) {
    if (!g_device) return "No Metal device";
    return [g_device.name UTF8String];
}

uint64_t metal_renderer_get_gpu_time_nanos(void) {
    return g_gpuTimeNanos;
}

// ============================================================
// Accessors
// ============================================================

id<MTLDevice> metal_renderer_get_device(void) {
    return g_device;
}

id<MTLCommandQueue> metal_renderer_get_queue(void) {
    return g_commandQueue;
}

CAMetalLayer* metal_renderer_get_layer(void) {
    return g_metalLayer;
}

NSView* metal_renderer_get_view(void) {
    return g_metalView;
}

void metal_renderer_set_terrain_active(bool active) {
    g_terrainActive = active;
}

// ============================================================
// IOSurface shared texture accessors (for terrain rendering)
// ============================================================

id<MTLTexture> metal_renderer_get_shared_color_texture(void) {
    return g_sharedColorTexture;
}

id<MTLTexture> metal_renderer_get_shared_depth_texture(void) {
    return g_sharedDepthTexture;
}

// Returns the IOSurface ID for GL binding. Returns 0 if not available.
uint32_t metal_renderer_get_iosurface_id(void) {
    if (!g_ioSurface) return 0;
    return IOSurfaceGetID(g_ioSurface);
}

// Returns the IOSurfaceRef for direct GL binding via CGLTexImageIOSurface2D
IOSurfaceRef metal_renderer_get_iosurface(void) {
    return g_ioSurface;
}

// Ensure the shared surface matches the requested size.
// Called by terrain begin_frame to resize if window changed.
bool metal_renderer_ensure_surface(int width, int height) {
    return metal_renderer_create_shared_surface(width, height);
}

int metal_renderer_get_surface_width(void) { return g_surfaceWidth; }
int metal_renderer_get_surface_height(void) { return g_surfaceHeight; }
