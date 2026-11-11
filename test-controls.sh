#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/controls-test-classes"
rm -rf "$OUT"
mkdir -p "$OUT" "$HERE/build/controls-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE/test/undohistory/HistoryControlsTest.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/controls-test-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ" undohistory.HistoryControlsTest "$HERE/build"
