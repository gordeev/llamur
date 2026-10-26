#!/usr/bin/env bash
# Resource resizing only; original and extracted character are preserved.
set -euo pipefail
cd "$(dirname "$0")/.."
source_icon=docs/branding/llamur-foreground.png
res=app/src/main/res
for entry in mdpi:48:108 hdpi:72:162 xhdpi:96:216 xxhdpi:144:324 xxxhdpi:192:432; do
    IFS=: read -r density legacy layer <<< "$entry"
    mkdir -p "$res/mipmap-$density"
    # The character is 60dp within 108dp. Its face/ears/tail fit the central
    # 66dp safe circle; no pre-rounded tile or baked shadow surrounds it.
    artwork=$((layer * 5 / 9))
    magick "$source_icon" \
        -resize "${artwork}x${artwork}" -background none -gravity center \
        -extent "${layer}x${layer}" -strip "$res/mipmap-$density/ic_launcher_foreground.png"
    viewport=$((layer * 2 / 3))
    magick -size "${layer}x${layer}" canvas:'#11111F' \
        "$res/mipmap-$density/ic_launcher_foreground.png" -composite \
        -gravity center -crop "${viewport}x${viewport}+0+0" +repage \
        -resize "${legacy}x${legacy}" -strip "$res/mipmap-$density/ic_launcher.png"
done
