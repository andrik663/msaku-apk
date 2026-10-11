# msaku.id APK branding

`msaku-mark.svg` is an exact copy of the canonical web Lapak M source. Do not redraw its geometry. The brand tile is a white mark on `#2962FF`.

Regenerate using an existing sharp installation, with no installs:

```sh
SHARP_MODULE=/absolute/path/to/node_modules/sharp node scripts/generate-brand-assets.mjs
```

Or run `node scripts/generate-brand-assets.mjs` when sharp is already resolvable locally. Banner text uses DejaVu Sans (ensure that font is available for identical text rendering).

Generated assets:
- Legacy launcher and round icons: 48dp, density-scaled to 36/48/72/96/144/192px.
- Adaptive foreground: 108dp transparent canvas, density-scaled to 81/108/162/216/324/432px. Canonical 28-unit geometry is centered at scale 1.85. Stroke-inclusive endpoints stay within radius 25dp (including raster antialiasing), inside Android's 33dp safe circle.
- Background raster/vector/color resources: brand blue.
- All existing portrait/landscape/day/night splash PNGs: original dimensions retained.
- Download icons: 192/256/512px. Banner: 1024x360px, labeled `msaku.id`.
- Public/www fallback pages, offline pages and favicon SVGs.

Both adaptive launcher XML files use the generated `@mipmap/ic_launcher_foreground` and brand-blue color. The launch theme explicitly uses the branded launcher for the Android 12+ splash icon, and the generated splash PNG for its existing background.

## Build/release notes

Android version: versionCode 5, versionName 1.4. Application identity and signing are unchanged.

The existing `.github/workflows/main.yml` runs on pushes to `main`, manual workflow dispatch, and published releases. It installs existing Capacitor dependencies, uses Java 21 (despite a step labeled JDK 17), builds `assembleDebug`, uploads artifact `msaku-apk-debug` containing `msaku-debug.apk`, and attaches that APK only for a published-release event. A main push does not create/update a release or its latest-download asset.

A local build requires Java, the Android SDK (compile/target SDK 36), Gradle wrapper downloads and the APK repo's Capacitor node_modules. The workflow copies `www/.` to `android/app/src/main/assets/public/` before building, packaging the branded index, offline page and favicon. It does not run `cap sync`, so no generated native/plugin configuration is changed. Do not run a sync casually: generated native/plugin files may change.

This branding change does not configure production signing. The existing debug build is retained. The existing release Gradle path requires GOOGLE_MAPS_API_KEY. No release, push or signing change is part of asset generation.
