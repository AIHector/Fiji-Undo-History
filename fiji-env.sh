# Sourced by build.sh, test.sh and bench.sh: finds Fiji's folder, its Java and ij.jar.
# Usage: . ./fiji-env.sh [/path/to/Fiji or Fiji.app]
FIJI="${1:-${FIJI:-}}"
if [ -z "$FIJI" ]; then
  for c in /Applications/Fiji.app /Applications/Fiji "$HOME/Fiji.app" "$HOME/Applications/Fiji.app"; do
    [ -d "$c/jars" ] && { FIJI="$c"; break; }
  done
fi
# Some macOS installs keep jars/ plugins/ next to Fiji.app rather than inside it.
[ -n "$FIJI" ] && [ ! -d "$FIJI/jars" ] && [ -d "$(dirname "$FIJI")/jars" ] && FIJI=$(dirname "$FIJI")
[ -d "$FIJI/jars" ] || { echo "Fiji not found: pass its folder, e.g. ./build.sh /path/to/Fiji.app" >&2; exit 1; }
IJ=$(ls "$FIJI"/jars/ij-*.jar 2>/dev/null | head -1)
[ -n "$IJ" ] || { echo "No jars/ij-*.jar in $FIJI" >&2; exit 1; }
JAVA_BIN=""
for d in "$FIJI"/java/*/*/bin "$FIJI"/java/*/*/Contents/Home/bin; do
  [ -x "$d/javac" ] && { JAVA_BIN="$d"; break; }
done
if [ -z "$JAVA_BIN" ]; then
  command -v javac >/dev/null 2>&1 || { echo "No javac found in Fiji's Java or on PATH" >&2; exit 1; }
  JAVA_BIN=$(dirname "$(command -v javac)")
fi
