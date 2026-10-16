#!/bin/sh
# Build the release zip: build/Undo_History-<version>.zip
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
"$HERE/build.sh" "$1" --no-install
VERSION=$(cat "$HERE/VERSION")
D="$HERE/build/Undo_History-$VERSION"
rm -rf "$D" "$D.zip" && mkdir -p "$D"
cp "$HERE/build/Undo_History.jar" "$HERE/src/Undo_History_Startup.ijm" "$HERE/README.md" "$HERE/LICENSE" "$D/"
(cd "$HERE/build" && zip -qr "Undo_History-$VERSION.zip" "Undo_History-$VERSION")
echo "Packaged $D.zip"
