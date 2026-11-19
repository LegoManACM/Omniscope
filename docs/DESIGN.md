# Omniscope: design

A native Android app that shows everything free data sources know about where you are and what you're
pointing at. Plugins provide the data. The app renders it as **AR**, a **map** or a **list**, and later serves it
over **MCP** so Claude, daimon and AOA can read your surroundings.

## Principles

1. **The core is free and has no limits.** Everything that isn't a plugin runs on the phone: sensors,
   compass/declination (Android's built-in World Magnetic Model), AR projection, map rendering (OpenFreeMap
   vector tiles, no key), caching and the MCP server. No Google Play Services and no cloud localisation.
2. **Only plugins have limits.** Free plugins call public APIs politely, with a disk cache, per-host pacing and
   stale-on-error. Freemium and paid plugins also carry a daily quota meter, and a global "free only" switch turns
   them all off.
3. **One contract, many views.** Every plugin returns the same `Item` shape. AR, map, list and MCP are just
   different readers of the registry.

## Plugin contract (`core/Model.kt`)

```kotlin
interface Plugin {
    val id: String; val name: String; val icon: String; val color: String
    val description: String; val tags: List<String>
    val cost: Cost                // FREE | FREEMIUM | PAID
    val freshness: Freshness      // LIVE 15 s | SLOW 10 min | STATIC 6 h (also the cache TTL)
    val key: KeySpec?             // null = keyless; otherwise entered in the Plugins tab
    val dailyLimit: Int?          // quota meter for metered plugins
    val ambient: Boolean          // readings about "here" (weather, address), shown as cards
    suspend fun fetch(ctx: Ctx, env: PluginEnv): List<Item>
}

data class Item(id, pluginId, title, subtitle?, lat?, lon?, altM?, sky: SkyPos?, url?, image?, fields)
```

| Item has | Shows in |
|---|---|
| `lat`/`lon` (+ optional `altM`) | AR (bearing and elevation angle), map, list by distance |
| `sky` (azimuth/elevation) | AR only (Sun, Moon, later planets and satellites) |
| neither, `ambient = true` | List header cards, AR side panel (later) |

Planned capability: `identify(image | audio)` for point-at-it plugins (ML Kit on-device, Pl@ntNet, …).

## Refresh loop (`core/Registry.kt`)

Every 5 s (or when poked), each active plugin is refetched if its `freshness` has expired or you've moved more
than 300 m since its last fetch. After an error it waits 60 s before retrying. Coordinates are rounded to 4
decimals (~10 m) before any request.

## AR (`ui/ArScreen.kt`)

Orientation-only AR, with no SLAM. Everything worth labelling is far enough away that only the phone's
rotation matters:

1. `TYPE_ROTATION_VECTOR` (gyro + accel + magnetometer fusion) gives the rotation matrix *R* (device to world, magnetic north).
2. Each item gets an East-North-Up vector from you, computed via ECEF on the WGS84 ellipsoid, so Earth's curvature
   and the altitudes of aircraft, the ISS and peaks come out right.
3. That vector is rotated from true north to magnetic north by **declination** (`GeomagneticField`, offline) plus a
   **user alignment offset**.
4. `device = Rᵀ · world`. The camera looks down −Z, and the pinhole projection uses the camera's real field of view
   from Camera2 characteristics.

Heading accuracy, from cheapest to best:

| Cue | Status |
|---|---|
| Compass + declination | v1 |
| Tap-to-align ("the crosshair is on this", using the Sun, Moon or a known peak) | v1 |
| Sun/Moon computed on-device as calibration targets | v1 |
| GPS direction of travel while moving | next |
| Skyline vs elevation tiles (Copernicus GLO-30 / SRTM, cached on the phone) | later |
| Matching the camera to OSM streets and buildings in cities (OrienterNet-style) | maybe |

## MCP (planned)

A foreground service runs a small HTTP server (NanoHTTPD) speaking MCP JSON-RPC, reachable on the phone's
Tailscale address with a bearer token. Its tools are generated from the registry: `here()`, `nearby(tags, radius)`,
`plugins()`, and later `identify(image)`.

## Not now

The built-in agent (Claude/daimon over MCP covers it) and iOS.
