#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/headless-test-classes"
mkdir -p "$OUT"
"$JAVA_BIN/javac" --release 8 -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE/test/undohistory/MacroTests.java"
"$JAVA_BIN/java" -Djava.awt.headless=true -cp "$OUT:$IJ" undohistory.MacroTests
