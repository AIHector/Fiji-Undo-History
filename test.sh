#!/bin/sh
# Compile the plugin and run the end-to-end GUI test in a separate ImageJ (opens a few windows briefly).
# Usage: ./test.sh [/path/to/Fiji.app]
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "$1"
OUT="$HERE/build/test-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE"/test/undohistory/*.java
cp "$HERE/src/plugins.config" "$OUT/"
"$JAVA_BIN/java" -Xmx2g -Dapple.awt.UIElement=true -cp "$OUT:$IJ" undohistory.GuiTest "$HERE/build"
