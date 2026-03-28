# Headless / Offscreen Minecraft Rendering on macOS

Research date: 2026-03-27

## Executive Summary

There is no single turnkey solution for truly headless Minecraft rendering on macOS Apple Silicon. However, several viable approaches exist depending on the goal: (1) in-mod programmatic screenshots using the Forge rendering pipeline, (2) hidden-window offscreen rendering via GLFW, (3) external macOS screen capture tools, and (4) CI-oriented frameworks that stub out the display. Each is detailed below.

---

## Approach 1: In-Mod Programmatic Screenshots (Forge API)

**Best for: automated rendering verification from within a running Minecraft instance**

### How It Works

Minecraft Forge exposes `ScreenShotHelper.saveScreenshot()` which reads the current OpenGL framebuffer and writes a PNG. In 1.16.5 the relevant class is `net.minecraft.util.ScreenShotHelper` and the framebuffer is obtained via `Minecraft.getInstance().getMainRenderTarget()` (MCP) or `getFramebuffer()` (older mappings).

### Implementation

```java
import net.minecraft.client.Minecraft;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public class ScreenshotCapture {
    private boolean shouldCapture = false;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (shouldCapture) {
            Minecraft mc = Minecraft.getInstance();
            // saveScreenshot(gameDir, screenshotName, width, height, framebuffer)
            ScreenShotHelper.saveScreenshot(
                mc.gameDirectory,
                "automated_capture.png",
                mc.getWindow().getWidth(),
                mc.getWindow().getHeight(),
                mc.getMainRenderTarget()
            );
            shouldCapture = false;
        }
    }
}
```

### Lower-Level: Direct glReadPixels

For custom framebuffer sizes or render-to-texture workflows:

```java
import org.lwjgl.opengl.GL11;
import java.nio.ByteBuffer;

// After rendering to your FBO:
GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
// Then write buffer to PNG using NativeImage or ImageIO
```

The `NativeImage` class in 1.16.5 (`net.minecraft.client.renderer.texture.NativeImage`) can be created from framebuffer data and saved directly:

```java
NativeImage image = new NativeImage(width, height, false);
// NativeImage.downloadFromFramebuffer() reads the current framebuffer
image.downloadFromFramebuffer(mc.getMainRenderTarget());
image.writeToFile(outputPath);
```

### Mineshot Revived

The [mineshot-revived](https://github.com/pascallj/mineshot-revived) mod (supports 1.16.5) demonstrates off-screen framebuffer rendering for high-resolution screenshots. It creates a larger-than-window FBO, renders into it, and saves the result. This is a proven reference implementation.

### Limitations
- Requires a running Minecraft client with an OpenGL context (a window must exist, even if hidden)
- Works on Apple Silicon via macOS's OpenGL compatibility layer
- No special permissions needed beyond normal Minecraft operation

---

## Approach 2: Hidden GLFW Window (Offscreen Context)

**Best for: running Minecraft with a window that never appears on screen**

### How It Works

GLFW (which LWJGL/Minecraft uses) supports creating a hidden window with `GLFW_VISIBLE` set to `GLFW_FALSE`. The window is never shown, but its OpenGL context is fully functional. Rendering goes to a Framebuffer Object (FBO) rather than the default framebuffer.

From the [GLFW Context Guide](https://www.glfw.org/docs/3.3/context_guide.html):
> "The window never needs to be shown and its context can be used as a plain offscreen context. Depending on the window manager, the size of a hidden window's framebuffer may not be usable or modifiable, so framebuffer objects are recommended for rendering with such contexts."

### Implementation for Minecraft

This requires patching Minecraft's window creation. Before `glfwCreateWindow` is called:

```c
glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
// On macOS, also suppress the menu bar:
glfwWindowHint(GLFW_COCOA_MENUBAR, GLFW_FALSE);  // GLFW 3.3+
```

In a Forge mod context, you would need to use a Mixin or ASM to intercept the window creation in `com.mojang.blaze3d.platform.Window` (or the equivalent 1.16.5 class) and inject the visibility hint before the GLFW window is created.

### macOS / Apple Silicon Notes
- Works on Apple Silicon. macOS translates OpenGL calls to Metal internally.
- OpenGL is deprecated on macOS (since 10.14) but still functional as of macOS 15.x.
- The hidden window still creates a menu bar entry unless `GLFW_COCOA_MENUBAR` is set to `GLFW_FALSE`.
- FBO rendering with a hidden window is the recommended approach per GLFW docs.

### Limitations
- A window object is still created (just never shown). This is a GLFW limitation -- it cannot create a context without a window.
- Rendering performance is the same as a visible window (the GPU does the same work).
- Requires a display session (cannot run over pure SSH without a display).

---

## Approach 3: macOS External Screen Capture

**Best for: capturing screenshots of a running Minecraft window from outside the process**

### macOS `screencapture` CLI

macOS ships with `screencapture` which can capture specific windows by ID:

```bash
# Install GetWindowID (one-time)
brew install smokris/getwindowid/getwindowid

# Get Minecraft's window ID
WINDOW_ID=$(GetWindowID "java" "Minecraft")

# Capture that specific window
screencapture -l$WINDOW_ID screenshot.png
```

Alternative to get window ID without installing anything:

```bash
# Using osascript
osascript -e 'tell app "System Events" to get id of first window of process "java"'
```

### Permissions Required
- **Screen Recording permission**: Terminal (or whatever app runs the command) must be granted Screen Recording access in System Settings > Privacy & Security > Screen Recording.
- This is a one-time setup per application.

### Java Robot Class

From within a Java process (not Minecraft's process, but a helper):

```java
import java.awt.Robot;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

Robot robot = new Robot();
BufferedImage screenshot = robot.createScreenCapture(
    new Rectangle(x, y, width, height)
);
ImageIO.write(screenshot, "png", new File("screenshot.png"));
```

### Limitations
- Requires the Minecraft window to actually be visible on screen (not hidden/minimized)
- Requires Screen Recording permission
- Window must exist in a GUI session
- Works fine on Apple Silicon

---

## Approach 4: HeadlessMC / mc-runtime-test (CI Framework)

**Best for: automated CI/CD testing of Minecraft mods**

### HeadlessMC

[HeadlessMC](https://github.com/headlesshq/headlessmc) is a project specifically designed to run Minecraft without a display. It works in two modes:

1. **LWJGL Stub Mode**: Patches every LWJGL function to be a no-op or return stub values. The game runs, the world loads, game logic executes, but no actual rendering occurs.

2. **Xvfb Mode**: Uses a virtual X11 framebuffer (Linux only). Minecraft renders to a virtual display that can be captured.

### mc-runtime-test (GitHub Action)

[mc-runtime-test](https://github.com/headlesshq/mc-runtime-test) wraps HeadlessMC as a GitHub Action:

```yaml
- name: Test Minecraft Mod
  uses: headlesshq/mc-runtime-test@main
  with:
    mc-version: 1.16.5
    modloader: forge
    mod-file: build/libs/mymod.jar
```

It automatically:
- Joins a singleplayer world
- Waits for chunks to load
- Runs GameTest Framework tests (if registered)
- Exits with success/failure code

### Limitations
- **LWJGL Stub Mode**: No actual rendering occurs. You cannot verify visual output. Only game logic is tested.
- **Xvfb Mode**: Linux only. Does not work on macOS.
- Not designed for visual/rendering verification -- more for "does the mod crash on startup" testing.
- The Forge 1.16.5 support level should be verified against the mc-runtime-test compatibility matrix.

---

## Approach 5: Fabric Client GameTest (Reference)

**Best for: Fabric mods (not directly applicable to Forge 1.16.5, but worth noting)**

The [Fabric automated testing documentation](https://docs.fabricmc.net/develop/automatic-testing) describes client game tests that can:
- Spin up an actual Minecraft client
- Run rendering-related tests
- Upload screenshots as CI artifacts

This is Fabric-only and targets newer Minecraft versions, but the architecture is instructive for what a Forge equivalent could look like.

---

## Approach 6: Render to FBO + Save (Custom Mod Pipeline)

**Best for: building a dedicated rendering verification mod for your Metal terrain renderer**

This combines Approaches 1 and 2 into a purpose-built pipeline:

### Architecture

```
1. Minecraft starts with hidden window (GLFW_VISIBLE=false)
2. World loads, chunks render
3. Custom mod hooks RenderWorldLastEvent
4. Mod renders scene to a custom FBO at desired resolution
5. Mod reads FBO pixels via glReadPixels / NativeImage
6. Mod saves PNG to disk
7. External script compares PNG against reference images
8. Minecraft exits
```

### Key Code Pattern (1.16.5 Forge)

```java
@Mod("rendertest")
public class RenderTestMod {

    @SubscribeEvent
    public static void onWorldRender(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getInstance();

        // Check if we should capture (e.g., after N ticks)
        if (tickCounter >= CAPTURE_AFTER_TICKS) {
            // Option A: Use built-in screenshot helper
            ScreenShotHelper.saveScreenshot(
                mc.gameDirectory,
                "render_test_" + System.currentTimeMillis() + ".png",
                mc.getWindow().getWidth(),
                mc.getWindow().getHeight(),
                mc.getMainRenderTarget()
            );

            // Option B: Custom FBO for specific resolution
            // Create FBO, bind, render, read pixels, save

            // Exit after capture
            mc.stop();
        }
        tickCounter++;
    }
}
```

### External Comparison Script

```bash
#!/bin/bash
# Compare rendered output against reference
# Using ImageMagick
compare -metric RMSE rendered.png reference.png diff.png 2>&1
```

---

## Comparison Matrix

| Approach | Actual Rendering | No Visible Window | macOS Apple Silicon | No Permissions | CI Compatible |
|----------|-----------------|-------------------|--------------------|--------------------|---------------|
| 1. In-Mod Screenshot | Yes | No (window visible) | Yes | Yes | Partial |
| 2. Hidden GLFW Window | Yes | Yes (hidden) | Yes | Yes | Needs display session |
| 3. macOS screencapture | Yes (captures) | No (must be visible) | Yes | Screen Recording | No (needs GUI) |
| 4. HeadlessMC Stub | No (stubs) | Yes | Yes | Yes | Yes |
| 5. Fabric GameTest | Yes | Partial | Untested | Yes | Yes (Linux) |
| 6. Custom FBO Pipeline | Yes | Yes (hidden) | Yes | Yes | Needs display session |

---

## Recommended Strategy for metal-mc-terrain

Given the project goal of verifying Metal terrain rendering on macOS Apple Silicon:

### Primary: Hidden Window + In-Mod FBO Capture (Approaches 2 + 6)

1. Create a Mixin that sets `GLFW_VISIBLE` to `GLFW_FALSE` before window creation
2. The Metal rendering pipeline still runs (macOS translates OpenGL to Metal, or your native Metal code runs directly)
3. Hook `RenderWorldLastEvent` to capture the framebuffer after world loads
4. Save to PNG and compare against reference images
5. Exit Minecraft programmatically

### Fallback: Visible Window + macOS screencapture (Approaches 1 + 3)

1. Launch Minecraft normally (window visible but can be on a secondary space)
2. Use `screencapture -l$(GetWindowID ...)` from a shell script on a timer
3. Simpler to implement, but requires Screen Recording permission and a GUI session

### For CI: HeadlessMC for crash testing (Approach 4)

Use HeadlessMC in LWJGL stub mode to verify the mod loads without crashes, but do not rely on it for visual rendering verification.

---

## Sources

- [GLFW Context Guide - Offscreen Contexts](https://www.glfw.org/docs/3.3/context_guide.html)
- [GLFW Window Guide - GLFW_VISIBLE hint](https://www.glfw.org/docs/latest/window_guide.html)
- [GLFW offscreen.c example](https://github.com/glfw/glfw/blob/master/examples/offscreen.c)
- [GLFW Issue #648 - Headless rendering support](https://github.com/glfw/glfw/issues/648)
- [Apple Developer - Drawing Offscreen (OpenGL)](https://developer.apple.com/library/archive/documentation/GraphicsImaging/Conceptual/OpenGL-MacProgGuide/opengl_offscreen/opengl_offscreen.html)
- [LWJGL Issue #11 - Without display mode](https://github.com/LWJGL/lwjgl/issues/11)
- [LWJGL FBO Wiki](https://github.com/mattdesl/lwjgl-basics/wiki/FrameBufferObjects)
- [HeadlessMC](https://github.com/headlesshq/headlessmc)
- [mc-runtime-test GitHub Action](https://github.com/headlesshq/mc-runtime-test)
- [Fabric Automated Testing Docs](https://docs.fabricmc.net/develop/automatic-testing)
- [Mineshot Revived (1.16.5 FBO screenshots)](https://github.com/pascallj/mineshot-revived)
- [Forge JavaDocs - NativeImage (1.16.5)](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.16.5/net/minecraft/client/renderer/texture/class-use/NativeImage.html)
- [Forge ScreenShotHelper API](https://skmedix.github.io/ForgeJavaDocs/javadoc/forge/1.9.4-12.17.0.2051/net/minecraft/util/ScreenShotHelper.html)
- [GetWindowID for macOS](https://github.com/smokris/GetWindowID)
- [macOS screencapture reference](https://ss64.com/mac/screencapture.html)
- [MoltenVK](https://moltengl.com/moltenvk/)
- [metal-mc-terrain upstream](https://github.com/Infatoshi/metal-mc-terrain)
- [LearnOpenGL - Framebuffers](https://learnopengl.com/Advanced-OpenGL/Framebuffers)
- [GameTest Framework - Forge](https://gist.github.com/SizableShrimp/60ad4109e3d0a23107a546b3bc0d9752)
- [McTester (Sponge)](https://github.com/SpongePowered/McTester)
