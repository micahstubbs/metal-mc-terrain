# Minecraft Window Position and Size Persistence

## Requirement

The Minecraft game window must launch at a consistent position and size across restarts. This is critical for:
- Autonomous testing (screencapture needs predictable window bounds)
- Metal terrain debugging (IOSurface dimensions must match)
- Multi-monitor workflows (window should stay on the correct display)

## Implementation

### Window Size

Minecraft window size is controlled by two mechanisms:

1. **Minecraft `options.txt`** - `overrideWidth` and `overrideHeight` fields
   - Path: `~/Documents/curseforge/minecraft/Instances/SkyFactory One/options.txt`
   - When set to 0, Minecraft uses its default size (854x480 on macOS)
   - When set to specific values, forces that resolution

2. **GLFW window hints** via JVM properties (not supported by Minecraft directly)

### Window Position

Minecraft does NOT persist window position natively. The window opens at whatever position the OS assigns.

To force a specific position, the mod includes auto-positioning code in `ExampleMod.java` that:
- Reads position from JVM system properties `-Dmetal.window.x` and `-Dmetal.window.y`
- Sets the GLFW window position on first render tick
- Defaults to (100, 100) if not specified

### Launch Script Configuration

The `launch-skyfactory.sh` script includes these properties:

```bash
-Dmetal.window.x=100 \
-Dmetal.window.y=100 \
-Dmetal.window.width=854 \
-Dmetal.window.height=480 \
```

### Current Values (as of 2026-03-27)

| Property | Value | Notes |
|----------|-------|-------|
| Window position | 100, 100 | Top-left of primary display |
| Window size (points) | 854 x 480 | Default Minecraft size |
| Window size (pixels) | 1708 x 960 | At 2x retina scale |
| Retina scale | 2.0 | M1 Pro built-in display |
| Fullscreen | false | Windowed mode for debugging |

### Modifying

To change the window position or size, edit `launch-skyfactory.sh`:

```bash
-Dmetal.window.x=200 \    # X position (pixels from left of screen)
-Dmetal.window.y=200 \    # Y position (pixels from top of screen)
```

Or set `overrideWidth` and `overrideHeight` in `options.txt` for the window size:

```
overrideWidth:1280
overrideHeight:720
```
