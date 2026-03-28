#!/bin/bash
# Optimized launch script for SkyFactory One + Metal terrain renderer
# arm64 Java 17 + ZGC + LWJGL 3.3.1 arm64 natives
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java"
GAME_DIR="$HOME/Documents/curseforge/minecraft/Instances/SkyFactory One"
ASSETS_DIR="$HOME/Library/Application Support/minecraft/assets"
CF_LIBS="$HOME/Documents/curseforge/minecraft/Install/libraries"
MC_LIBS="$HOME/Library/Application Support/minecraft/libraries"
VERSIONS_DIR="$HOME/Documents/curseforge/minecraft/Install/versions"
NATIVES_DIR="/tmp/mc-natives-arm64"
LWJGL="/tmp/lwjgl-arm64"

# Resolve library from CurseForge or vanilla launcher
resolve_lib() {
  local rel="$1"
  if [ -f "$CF_LIBS/$rel" ]; then echo "$CF_LIBS/$rel"
  elif [ -f "$MC_LIBS/$rel" ]; then echo "$MC_LIBS/$rel"
  else echo "ERROR: Missing library: $rel" >&2; exit 1
  fi
}

# Auth: extract from CurseForge launcher or use refresh_token.py
if [ -f "$SCRIPT_DIR/refresh_token.py" ]; then
    AUTH_OUTPUT=$(python3 "$SCRIPT_DIR/refresh_token.py")
    TOKEN=$(echo "$AUTH_OUTPUT" | grep "^TOKEN=" | cut -d= -f2-)
    UUID=$(echo "$AUTH_OUTPUT" | grep "^UUID=" | cut -d= -f2-)
    NAME=$(echo "$AUTH_OUTPUT" | grep "^NAME=" | cut -d= -f2-)
elif [ -n "$MC_TOKEN" ] && [ -n "$MC_UUID" ] && [ -n "$MC_NAME" ]; then
    TOKEN="$MC_TOKEN"
    UUID="$MC_UUID"
    NAME="$MC_NAME"
else
    echo "ERROR: No auth credentials found."
    echo ""
    echo "Option 1: Create refresh_token.py with your credentials"
    echo "Option 2: Export MC_TOKEN, MC_UUID, MC_NAME env vars"
    echo "Option 3: Launch from CurseForge once, then check launcher logs for credentials"
    echo ""
    echo "To extract from a running CurseForge session:"
    echo "  ps aux | grep java | grep -oP '(?<=--accessToken )[^ ]+'"
    exit 1
fi

if [ -z "$TOKEN" ] || [ -z "$UUID" ]; then
    echo "ERROR: Auth failed. Check refresh_token.py or env vars."
    exit 1
fi
echo "Authenticated as $NAME"

cd "$GAME_DIR"

# Build classpath from Forge libraries
CP=""
for rel in \
  "net/minecraftforge/forge/1.16.5-36.2.34/forge-1.16.5-36.2.34.jar" \
  "org/ow2/asm/asm/9.1/asm-9.1.jar" \
  "org/ow2/asm/asm-commons/9.1/asm-commons-9.1.jar" \
  "org/ow2/asm/asm-tree/9.1/asm-tree-9.1.jar" \
  "org/ow2/asm/asm-util/9.1/asm-util-9.1.jar" \
  "org/ow2/asm/asm-analysis/9.1/asm-analysis-9.1.jar" \
  "cpw/mods/modlauncher/8.1.3/modlauncher-8.1.3.jar" \
  "cpw/mods/grossjava9hacks/1.3.3/grossjava9hacks-1.3.3.jar" \
  "net/minecraftforge/accesstransformers/3.0.1/accesstransformers-3.0.1.jar" \
  "org/antlr/antlr4-runtime/4.9.1/antlr4-runtime-4.9.1.jar" \
  "net/minecraftforge/eventbus/4.0.0/eventbus-4.0.0.jar" \
  "net/minecraftforge/forgespi/3.2.0/forgespi-3.2.0.jar" \
  "net/minecraftforge/coremods/4.0.6/coremods-4.0.6.jar" \
  "net/minecraftforge/unsafe/0.2.0/unsafe-0.2.0.jar" \
  "com/electronwill/night-config/core/3.6.3/core-3.6.3.jar" \
  "com/electronwill/night-config/toml/3.6.3/toml-3.6.3.jar" \
  "org/jline/jline/3.12.1/jline-3.12.1.jar" \
  "org/apache/maven/maven-artifact/3.6.3/maven-artifact-3.6.3.jar" \
  "net/jodah/typetools/0.8.3/typetools-0.8.3.jar" \
  "org/apache/logging/log4j/log4j-api/2.15.0/log4j-api-2.15.0.jar" \
  "org/apache/logging/log4j/log4j-core/2.15.0/log4j-core-2.15.0.jar" \
  "org/apache/logging/log4j/log4j-slf4j18-impl/2.15.0/log4j-slf4j18-impl-2.15.0.jar" \
  "net/minecrell/terminalconsoleappender/1.2.0/terminalconsoleappender-1.2.0.jar" \
  "net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar" \
  "org/spongepowered/mixin/0.8.4/mixin-0.8.4.jar" \
  "net/minecraftforge/nashorn-core-compat/15.1.1.1/nashorn-core-compat-15.1.1.1.jar" \
  "com/mojang/patchy/1.3.9/patchy-1.3.9.jar" \
  "com/ibm/icu/icu4j/66.1/icu4j-66.1.jar" \
  "com/mojang/javabridge/1.0.22/javabridge-1.0.22.jar" \
  "io/netty/netty-all/4.1.25.Final/netty-all-4.1.25.Final.jar" \
  "com/google/guava/guava/21.0/guava-21.0.jar" \
  "org/apache/commons/commons-lang3/3.5/commons-lang3-3.5.jar" \
  "commons-io/commons-io/2.5/commons-io-2.5.jar" \
  "commons-codec/commons-codec/1.10/commons-codec-1.10.jar" \
  "net/java/jinput/jinput/2.0.5/jinput-2.0.5.jar" \
  "net/java/jutils/jutils/1.0.0/jutils-1.0.0.jar" \
  "com/mojang/brigadier/1.0.17/brigadier-1.0.17.jar" \
  "com/mojang/datafixerupper/4.0.26/datafixerupper-4.0.26.jar" \
  "com/google/code/gson/gson/2.8.0/gson-2.8.0.jar" \
  "com/mojang/authlib/2.1.28/authlib-2.1.28.jar" \
  "org/apache/commons/commons-compress/1.8.1/commons-compress-1.8.1.jar" \
  "org/apache/httpcomponents/httpclient/4.3.3/httpclient-4.3.3.jar" \
  "commons-logging/commons-logging/1.1.3/commons-logging-1.1.3.jar" \
  "org/apache/httpcomponents/httpcore/4.3.2/httpcore-4.3.2.jar" \
  "it/unimi/dsi/fastutil/8.5.15/fastutil-8.5.15.jar" \
  "com/mojang/text2speech/1.11.3/text2speech-1.11.3.jar" \
  "ca/weblite/java-objc-bridge/1.0.0/java-objc-bridge-1.0.0.jar" \
; do
  CP="$CP:$(resolve_lib "$rel")"
done

# Add LWJGL 3.3.1 arm64 jars (replaces bundled x86_64 LWJGL 3.2.1)
for jar in "$LWJGL"/*.jar; do
  CP="$CP:$jar"
done

# Add vanilla client jar
CP="$CP:$VERSIONS_DIR/1.16.5/1.16.5.jar"

echo "Launching SkyFactory One with Metal terrain renderer..."
echo "  Java: arm64 17 + ZGC"
echo "  LWJGL: 3.3.1 arm64"
echo "  Metal: enabled"
echo ""
echo "In-game controls:"
echo "  F6 = toggle profiler overlay"
echo "  F8 = toggle Metal terrain (A/B test)"
echo ""

exec "$JAVA" \
  -XstartOnFirstThread \
  -Xmx6G -Xms4G \
  -XX:+UseZGC \
  -XX:ConcGCThreads=4 \
  -XX:+AlwaysPreTouch \
  -XX:+ParallelRefProcEnabled \
  -Dfml.earlyprogresswindow=false \
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED \
  --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.net=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.security=ALL-UNNAMED \
  --add-opens java.base/sun.security.ssl=ALL-UNNAMED \
  --add-exports java.base/sun.security.util=ALL-UNNAMED \
  --add-opens java.base/sun.security.util=ALL-UNNAMED \
  --add-opens java.base/java.util.jar=ALL-UNNAMED \
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
  -Djava.library.path="$NATIVES_DIR" \
  -Dforge.logging.console.level=info \
  -Dmetal.autoload=true \
  -Dmetal.autoload.world="New World" \
  -Dmetal.window.x=100 \
  -Dmetal.window.y=100 \
  -cp "${CP#:}" \
  cpw.mods.modlauncher.Launcher \
  --launchTarget fmlclient \
  --fml.forgeVersion 36.2.34 \
  --fml.mcVersion 1.16.5 \
  --fml.forgeGroup net.minecraftforge \
  --fml.mcpVersion 20210115.111550 \
  --gameDir "$GAME_DIR" \
  --assetsDir "$ASSETS_DIR" \
  --assetIndex 1.16 \
  --username "$NAME" \
  --uuid "$UUID" \
  --accessToken "$TOKEN" \
  --userType msa \
  --version forge-36.2.34 \
  --versionType release
