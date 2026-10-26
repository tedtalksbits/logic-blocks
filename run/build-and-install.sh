#!/usr/bin/env sh
# Stops the local test server, builds and tests Logic Blocks, installs the plugin on the server and the client mod
# in Minecraft's mods folder. Options and settings: see run/DevInstall.java.
cd "$(dirname "$0")/.." || exit 1
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" run/DevInstall.java "$@"
