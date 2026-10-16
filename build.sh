#!/bin/sh
# Build Undo_History.jar from src/ and install it (plus the startup macro) into Fiji.
# Usage: ./build.sh [/path/to/Fiji.app] [--no-install]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ARG="$1"; [ "$ARG" = "--no-install" ] && ARG=""
. "$HERE/fiji-env.sh" "$ARG"
VERSION=$(cat "$HERE/VERSION")

rm -rf "$HERE/build/classes" && mkdir -p "$HERE/build/classes"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$HERE/build/classes" "$HERE"/src/undohistory/*.java
cp "$HERE/src/plugins.config" "$HERE/build/classes/"
printf 'Implementation-Title: Undo History\nImplementation-Version: %s\n' "$VERSION" > "$HERE/build/manifest.txt"
"$JAVA_BIN/jar" cfm "$HERE/build/Undo_History.jar" "$HERE/build/manifest.txt" -C "$HERE/build/classes" .
echo "Built $HERE/build/Undo_History.jar (version $VERSION)"

case " $* " in *" --no-install "*) exit 0 ;; esac
cp "$HERE/build/Undo_History.jar" "$FIJI/plugins/Undo_History.jar"
mkdir -p "$FIJI/macros/AutoRun"
cp "$HERE/src/Undo_History_Startup.ijm" "$FIJI/macros/AutoRun/Undo_History_Startup.ijm"
echo "Installed into $FIJI (plugins/Undo_History.jar, macros/AutoRun/Undo_History_Startup.ijm) - restart Fiji."
