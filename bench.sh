#!/bin/sh
# Size ladder: ./bench.sh 1024 2048 4096 ...   (MB; each size runs in a fresh JVM with Fiji's heap)
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/fiji-env.sh" ""
OUT="$HERE/build/test-classes"
mkdir -p "$OUT" && "$JAVA_BIN/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$IJ" -d "$OUT" "$HERE"/src/undohistory/*.java "$HERE"/test/undohistory/*.java || exit 1
for MB in "$@"; do
  "$JAVA_BIN/java" -Xmx${HEAP:-18g} -XX:+UseG1GC -Dapple.awt.UIElement=true ${PCT:+-Dpct=$PCT} ${TRACE:+-Dtrace=1} -cp "$OUT:$IJ" undohistory.Bench "$MB" ${SPILL:+"$SPILL"} 2>&1 | grep -v '^WARNING'
done
