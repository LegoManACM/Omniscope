# FreeApiExplorer

An Android app that shows what free data sources know about where you are and what you're pointing at, as
**AR** (camera view with a reticle you aim at things), a **map**, a **list**, and an **MCP server** that Claude and
other agents can use to read your surroundings.

Everything is a **plugin**: weather, Wikipedia, aircraft overhead, wildlife sightings, peaks, licence-plate cameras,
3D buildings, barcode and text scanning, and more. Most plugins are small JSON files in [`plugins/`](plugins/), so
anyone can add one with a pull request, and the app can search and install them straight from this repo.

- **Free core, no limits.** Sensors, compass (with magnetic declination), AR projection, maps (OpenFreeMap), caching
  and scanning (ML Kit / ZXing) all run on the phone. Only plugins call out, and only freemium/paid plugins have quotas.
- **No Google Play Services** needed.

## Layout

| Path | What |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose, CameraX, MapLibre) |
| `plugins/` | Plugin manifests: `official/` (preloaded in the app) and `community/` |
| `docs/PLUGINS.md` | How to write a plugin |
| `docs/DESIGN.md`, `docs/PLAN.md` | Design notes |
| `tools/` | Plugin validation and index builder (also run by CI) |
| `demo/` | The original browser demo (a tiny Python server) |
| `free-location-apis.md` | The research list of free location APIs this started from |

## Build

```bash
cd android && ./gradlew assembleDebug
```

Needs the Android SDK (compileSdk 36) and JDK 17+; Android Studio's bundled JDK works.
