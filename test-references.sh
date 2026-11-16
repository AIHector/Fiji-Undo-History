#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/reference-test-classes"
mkdir -p "$OUT" "$HERE/build/reference-test-home"
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE/test/undohistory/ReferenceRestoreTest.java"
"$JAVA_BIN/java" -Duser.home="$HERE/build/reference-test-home" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ" undohistory.ReferenceRestoreTest "$HERE/build"
