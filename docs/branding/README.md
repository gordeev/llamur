# llamur icon

`llamur-source.png` is the unmodified image supplied by the owner on 2026-10-26.
Launcher resources are generated with ImageMagick:

```sh
bash scripts/generate-launcher-icons.sh
```

`llamur-foreground.png` is the character extracted from the original using the
built-in imagegen tool; its exact edit prompt is in `foreground-prompt.txt`.
The original remains unmodified. The resource script only resizes/composites.

The adaptive foreground is a transparent character without a tile, placed at
60dp within the 108dp layer. Essential details fit the central 66dp safe circle.
The background fills the entire layer. Android 13+ gets a dedicated vector alpha
mark in `ic_launcher_monochrome.xml`, so themed icons don't auto-wrap the raster.
Legacy launchers receive density-specific opaque PNGs without a baked mask.

See [Android adaptive icon requirements](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
