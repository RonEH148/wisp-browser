# Wisp – lightweight, private web browser for Android phones

Wisp is the phone version of my TV browser [Lumen](https://github.com/RonEH148/lumen-browser), built from the same source as a separate app (`com.sofabrowse.wisp`, own name and icon, installs next to Lumen). Lumen itself is based on [TV Bro](https://github.com/truefedex/tv-bro) by Fedir Tsapana.

**Download:** latest APK on the [Releases page](https://github.com/RonEH148/Player/releases?q=wisp&expanded=true). Wisp can also update itself: *Settings → About → Check for updates*.

## Highlights
- **Phone interface:** one slim top bar that hides while scrolling, bottom-sheet ⋮ menu, Material You colours on Android 12+
- **Private by default:** HTTPS first, third-party cookies blocked, no Google Play services
- **Lightweight:** 32-bit ARM build, small APK
- **Extras:** GIF maker for WebM/WebP videos, site info and permissions sheet, tab management
- **Kept from Lumen:** ad and pop-up blocker, error pages, video player features

Full details: [WISP.md](WISP.md) (Wisp) and [LUMEN.md](LUMEN.md) (shared features).

## Build
Kotlin / Gradle (Kotlin DSL); built with **GitHub Actions** in my build repository [RonEH148/Player](https://github.com/RonEH148/Player).
Release signing reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment. No keys are stored in this repository.

## Licence
Based on TV Bro © Fedir Tsapana – see [LICENSE.md](LICENSE.md). Original TV Bro README: [TVBRO_README.md](TVBRO_README.md).
