# TROVE brand assets

*Your library. Your server. Your books.*

- `trove-mark-gradient.svg`, `trove-mark-mono.svg` — the vector master of the TROVE symbol (a cube
  whose left face is book spines and whose right face is server slots), on a 124×124 box. The two
  status dots are holes, so the path uses `fill-rule="evenodd"`.
- `generate-mark.py` — builds the symbol from edge lines measured off the approved brand sheet
  (`exports/logo-primary-light.png`) and writes the two SVGs plus the raw path data.
- `exports/` — the approved raster exports from the branding sheet, as supplied. They're small
  crops, so they're reference material, not production icons.

The web app uses the same path data (`booklore-ui/src/app/shared/components/trove-mark/trove-mark.ts`):
the `accent` variant fills it with the theme's accent colour, as the brand's dynamic-accent system
intends, and the `gradient` variant uses the brand gradient `#0a66ff → #10a6ee → #1ed3bf`. App icons
put the gradient mark at 58% on `#0f1a23`, so it stays inside the maskable safe zone.
