#!/bin/sh
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" "${1:-}"
OUT="$HERE/build/scope-test-classes"
mkdir -p "$OUT"
TEST_HOME=$(mktemp -d "$HERE/build/scope-test-home.XXXXXX")
trap 'rm -rf "$TEST_HOME"' EXIT
"$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE/test/undohistory/ImageScopeTest.java"
"$JAVA_BIN/java" -Duser.home="$TEST_HOME" -Dapple.awt.UIElement=true -Xmx512m -cp "$OUT:$IJ" undohistory.ImageScopeTest
