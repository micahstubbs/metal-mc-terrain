# IOSurface: Metal-to-OpenGL Texture Sharing on macOS

Research compiled 2026-03-27.

---

## Overview

IOSurface provides zero-copy GPU buffer sharing across APIs and processes on macOS. Multiple textures (Metal, OpenGL, CoreVideo) can be bound to the same IOSurface, sharing exactly the same GPU memory. No copies, no latency from memory transfers.

There are **two approaches** to Metal/OpenGL interop via shared surfaces:

1. **Direct IOSurface** -- create an IOSurface manually, then bind it to both a Metal texture and an OpenGL texture directly.
2. **CVPixelBuffer (Apple's recommended approach)** -- create a CVPixelBuffer with Metal+OpenGL compatibility flags, then use CoreVideo texture caches to get both Metal and OpenGL texture handles. This is what Apple's official sample code uses.

---

## Approach 1: Direct IOSurface

### Step 1: Create the IOSurface

```objc
#import <IOSurface/IOSurface.h>

IOSurfaceRef CreateIOSurface(int width, int height) {
    NSDictionary *props = @{
        (__bridge NSString *)kIOSurfaceWidth:           @(width),
        (__bridge NSString *)kIOSurfaceHeight:          @(height),
        (__bridge NSString *)kIOSurfaceBytesPerElement:  @(4),
        (__bridge NSString *)kIOSurfacePixelFormat:      @('BGRA'),
    };
    return IOSurfaceCreate((__bridge CFDictionaryRef)props);
}
```

If you need cross-process sharing (e.g., to a compositor), add:
```objc
(__bridge NSString *)kIOSurfaceIsGlobal: @YES,
```

### Step 2: Create a Metal Texture from the IOSurface

```objc
// API signature:
// - (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor *)descriptor
//                                  iosurface:(IOSurfaceRef)iosurface
//                                      plane:(NSUInteger)plane;
// Swift: device.makeTexture(descriptor:iosurface:plane:)

MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                width:width
                                                                               height:height
                                                                            mipmapped:NO];
desc.storageMode = MTLStorageModeManaged; // or MTLStorageModeShared
desc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;

id<MTLTexture> metalTexture = [device newTextureWithDescriptor:desc
                                                     iosurface:surface
                                                         plane:0];
```

Swift equivalent:
```swift
let desc = MTLTextureDescriptor.texture2DDescriptor(
    pixelFormat: .bgra8Unorm,
    width: width,
    height: height,
    mipmapped: false
)
desc.storageMode = .managed
desc.usage = [.renderTarget, .shaderRead]

let metalTexture = device.makeTexture(descriptor: desc, iosurface: surface, plane: 0)
```

### Step 3: Create an OpenGL Texture from the Same IOSurface

```objc
// API signature:
// CGLError CGLTexImageIOSurface2D(
//     CGLContextObj ctx,
//     GLenum target,
//     GLenum internal_format,
//     GLsizei width,
//     GLsizei height,
//     GLenum format,
//     GLenum type,
//     IOSurfaceRef ioSurface,
//     GLuint plane
// );

GLuint texture;
glGenTextures(1, &texture);
glBindTexture(GL_TEXTURE_RECTANGLE_ARB, texture);

CGLError err = CGLTexImageIOSurface2D(
    cglContext,                      // CGLContextObj
    GL_TEXTURE_RECTANGLE_ARB,        // target -- MUST be RECTANGLE for IOSurface
    GL_RGBA,                         // internal format
    (GLsizei)IOSurfaceGetWidth(surface),
    (GLsizei)IOSurfaceGetHeight(surface),
    GL_BGRA,                         // format -- matches the IOSurface pixel layout
    GL_UNSIGNED_INT_8_8_8_8_REV,     // type
    surface,
    0                                // plane
);

glBindTexture(GL_TEXTURE_RECTANGLE_ARB, 0);
```

### Step 4: (Optional) Attach as Framebuffer for Rendering Into

```objc
GLuint fbo;
glGenFramebuffers(1, &fbo);
glBindFramebuffer(GL_FRAMEBUFFER, fbo);
glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_RECTANGLE_ARB, texture, 0);
GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
// status should be GL_FRAMEBUFFER_COMPLETE
```

---

## Approach 2: CVPixelBuffer (Apple's Official Sample)

This is from Apple's "Mixing Metal and OpenGL Rendering in a View" sample code.

### Pixel Format Table

The critical mapping between CoreVideo, Metal, and OpenGL formats:

```objc
// CV Pixel Format                         Metal Format                   GL internal   GL format   GL type
// -----------------------------------------------------------------------------------------------------------
// kCVPixelFormatType_32BGRA               MTLPixelFormatBGRA8Unorm       GL_RGBA       GL_BGRA_EXT GL_UNSIGNED_INT_8_8_8_8_REV
// kCVPixelFormatType_32BGRA               MTLPixelFormatBGRA8Unorm_sRGB  GL_SRGB8_ALPHA8 GL_BGRA   GL_UNSIGNED_INT_8_8_8_8_REV
// kCVPixelFormatType_ARGB2101010LEPacked   MTLPixelFormatBGR10A2Unorm     GL_RGB10_A2   GL_BGRA     GL_UNSIGNED_INT_2_10_10_10_REV
// kCVPixelFormatType_64RGBAHalf           MTLPixelFormatRGBA16Float      GL_RGBA       GL_RGBA     GL_HALF_FLOAT
```

### Step 1: Create CVPixelBuffer with Dual Compatibility

```objc
NSDictionary *bufferProps = @{
    (__bridge NSString *)kCVPixelBufferOpenGLCompatibilityKey: @YES,
    (__bridge NSString *)kCVPixelBufferMetalCompatibilityKey:  @YES,
};

CVPixelBufferRef pixelBuffer;
CVReturn cvret = CVPixelBufferCreate(
    kCFAllocatorDefault,
    width, height,
    kCVPixelFormatType_32BGRA,   // matches both Metal BGRA8Unorm and GL BGRA
    (__bridge CFDictionaryRef)bufferProps,
    &pixelBuffer
);
```

### Step 2: Create Metal Texture via CVMetalTextureCache

```objc
CVMetalTextureCacheRef metalTextureCache;
CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, metalDevice, nil, &metalTextureCache);

CVMetalTextureRef cvMTLTexture;
CVMetalTextureCacheCreateTextureFromImage(
    kCFAllocatorDefault,
    metalTextureCache,
    pixelBuffer,
    nil,
    MTLPixelFormatBGRA8Unorm,   // or MTLPixelFormatBGRA8Unorm_sRGB
    width, height,
    0,                           // plane
    &cvMTLTexture
);

id<MTLTexture> metalTexture = CVMetalTextureGetTexture(cvMTLTexture);
```

### Step 3: Create OpenGL Texture via CVOpenGLTextureCache (macOS)

```objc
CVOpenGLTextureCacheRef glTextureCache;
CVOpenGLTextureCacheCreate(
    kCFAllocatorDefault,
    nil,
    cglContext,         // NSOpenGLContext.CGLContextObj
    cglPixelFormat,     // NSOpenGLPixelFormat.CGLPixelFormatObj
    nil,
    &glTextureCache
);

CVOpenGLTextureRef cvGLTexture;
CVOpenGLTextureCacheCreateTextureFromImage(
    kCFAllocatorDefault,
    glTextureCache,
    pixelBuffer,
    nil,
    &cvGLTexture
);

GLuint openGLTexture = CVOpenGLTextureGetName(cvGLTexture);
```

---

## Synchronization

### Critical: glFlush() Before Metal Reads

When OpenGL renders into the shared surface and Metal needs to read it:

```objc
// After OpenGL rendering completes:
glFlush();  // Ensures all GL commands are submitted to GPU

// Now safe to encode Metal commands that read from the shared texture
```

### IOSurface Lock/Unlock (for CPU access)

Only needed when the CPU touches the surface (e.g., CoreGraphics drawing):

```objc
IOSurfaceLock(surface, 0, NULL);
void *data = IOSurfaceGetBaseAddress(surface);
size_t stride = IOSurfaceGetBytesPerRow(surface);
// ... CPU drawing ...
IOSurfaceUnlock(surface, 0, NULL);
```

For read-only CPU access:
```objc
IOSurfaceLock(surface, kIOSurfaceLockReadOnly, nil);
// ... read ...
IOSurfaceUnlock(surface, kIOSurfaceLockReadOnly, nil);
```

---

## Gotchas and Important Notes

### 1. GL_TEXTURE_RECTANGLE is Required

IOSurface-backed OpenGL textures **must** use `GL_TEXTURE_RECTANGLE_ARB`, not `GL_TEXTURE_2D`. This means:
- Texture coordinates are in **pixel coordinates** (0..width, 0..height), not normalized (0..1)
- Fragment shaders must use `sampler2DRect` instead of `sampler2D`
- `texture()` calls use pixel coords: `texture(tex, vec2(pixelX, pixelY))`

### 2. Pixel Format Matching: Metal BGRA vs GL BGRA

The formats are:
- **Metal**: `MTLPixelFormatBGRA8Unorm` (B=byte0, G=byte1, R=byte2, A=byte3)
- **OpenGL internal format**: `GL_RGBA` (yes, RGBA, not BGRA)
- **OpenGL pixel format**: `GL_BGRA` (or `GL_BGRA_EXT` on iOS)
- **OpenGL type**: `GL_UNSIGNED_INT_8_8_8_8_REV`

The internal format is `GL_RGBA` while the transfer/external format is `GL_BGRA`. This is not a typo -- this is how OpenGL specifies that the GPU storage is RGBA-ordered but the data source is BGRA-ordered. Since the IOSurface is already on the GPU, the driver handles this transparently.

### 3. sRGB Considerations

If using `MTLPixelFormatBGRA8Unorm_sRGB` on the Metal side, use `GL_SRGB8_ALPHA8` as the GL internal format (not `GL_RGBA`). Mismatch here causes washed-out or too-dark colors.

### 4. Multi-GPU Macs

On systems with multiple GPUs (e.g., MacBook Pro with discrete + integrated):
- You **must** render on the same GPU as the compositor
- Use `CGDirectDisplayCopyCurrentMetalDevice()` or similar to get the correct device
- IOSurface transfer across GPUs incurs a copy, defeating the zero-copy benefit

### 5. Color Space: SCNDisableLinearSpaceRendering

When mixing SceneKit rendering with custom compute passes writing directly to drawables, color space matters. Setting `SCNDisableLinearSpaceRendering = YES` in Info.plist can fix mismatched gamma between the Metal and GL rendering paths.

### 6. CVPixelBuffer Approach vs Direct IOSurface

- **CVPixelBuffer** (Approach 2) is Apple's recommended path -- it handles format negotiation and is more portable across iOS/macOS
- **Direct IOSurface** (Approach 1) gives you more control and is simpler when you only target macOS
- Under the hood, CVPixelBuffer is backed by an IOSurface; you can get it with `CVPixelBufferGetIOSurface()`

### 7. Deprecated APIs

- `kIOSurfaceIsGlobal` is deprecated in newer macOS versions. For cross-process sharing, use `IOSurfaceLookup()` with the surface ID instead, or pass the IOSurface via XPC/Mach ports.
- OpenGL itself is deprecated on macOS since 10.14, but still functional.

---

## Sources

- [Mixing Metal and OpenGL Rendering in a View -- Apple Developer](https://developer.apple.com/documentation/metal/metal_sample_code_library/mixing_metal_and_opengl_rendering_in_a_view)
- [makeTexture(descriptor:iosurface:plane:) -- Apple Developer](https://developer.apple.com/documentation/metal/mtldevice/1433378-newtexturewithdescriptor)
- [MTLTexture.iosurface -- Apple Developer](https://developer.apple.com/documentation/metal/mtltexture/1516104-iosurface)
- [macos-compositing IOSurface example -- mstange/GitHub](https://github.com/mstange/macos-compositing/blob/master/iosurface-compositing/OpenGLContextView.m)
- [IOSurface gist -- mstange/GitHub](https://gist.github.com/mstange/7b91f9fabbbdda54673c551410193c8b)
- [Rendering macOS in VR -- Oskar Groth](https://oskargroth.com/blog/rendering-macos-in-vr)
- [fujunwei/mixing-metal-opengl -- GitHub](https://github.com/fujunwei/mixing-metal-opengl) (Apple sample code mirror)
- [Metal for OpenGL Developers -- WWDC18](https://developer.apple.com/videos/play/wwdc2018/604/)
