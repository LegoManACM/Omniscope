# Writing an Omniscope plugin

A plugin is one JSON file, `plugins/community/<id>/plugin.json`. It says which free API to call with the user's
location and how to turn each result into something Omniscope can show in AR, on the map, in the list and over MCP.
There's no code to write and nothing runs on the phone except fetching that URL and reading the JSON that comes back.

Once it's merged, anyone can install it from **Settings → Get plugins** in the app.

## A complete example

```json
{
  "format": 1,
  "id": "gbif_occurrences",
  "version": 1,
  "name": "Species records (GBIF)",
  "description": "Recorded sightings and specimens of plants, animals and fungi near you.",
  "author": "your-github-name",
  "source": "GBIF",
  "category": "nature",
  "icon": "leaf",
  "cost": "free",
  "freshness": "static",
  "tags": ["nature", "science"],
  "radius": {"default": 2000, "min": 100, "max": 50000},
  "hosts": ["api.gbif.org"],
  "request": {
    "url": "https://api.gbif.org/v1/occurrence/search?geoDistance={lat},{lon},{radius_km:2}km&limit=100"
  },
  "items": "$.results[*]",
  "item": {
    "id": "$.key",
    "title": "$.vernacularName | cap || $.species",
    "subtitle": "{$.scientificName} · {$.eventDate | date}",
    "lat": "$.decimalLatitude",
    "lon": "$.decimalLongitude",
    "image": "$.media[0].identifier",
    "fields": [["Kingdom", "$.kingdom"], ["Recorded", "$.eventDate | date"]],
    "links": [["GBIF record", "https://www.gbif.org/occurrence/{$.key}"]]
  }
}
```

## Fields

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Always `1` for now. |
| `id` | yes | 2–40 lowercase letters, digits or `_`. Must match the folder name and be unique. |
| `version` | yes | A whole number. Bump it when you change the plugin, so installed copies are offered the update. |
| `name`, `description` | yes | Shown in the app. Keep the description to a sentence or two. |
| `author` | | Your name or GitHub handle. |
| `source` | | Where the data comes from, in words ("USGS", "OpenStreetMap via Overpass"). |
| `category` | yes | One of `conditions`, `places`, `history`, `buildings`, `nature`, `terrain`, `sky`, `transport`, `hazards`, `surveillance`, `media`, `scan`. It sets the colour and the filter group. |
| `icon` | | One of the icon names below; defaults to the category's icon. |
| `cost` | | `free` (default), `freemium` or `paid`. Anything but free is switched off by "Free only". |
| `freshness` | | How often to refetch, and how long responses are cached: `live` (15 s), `slow` (10 min, default), `static` (6 h). |
| `tags` | | Free-form words for search. |
| `ambient` | | `true` for readings about "here" (like weather): shown as cards, not as points. |
| `radius` | | `{"default", "min", "max"}` in metres: the user's search-radius slider. Leave it out if the API has no radius. |
| `key` | | `{"label", "signup"}` if the API needs a key. The user enters it in the app; use `{key}` in the request. |
| `dailyLimit` | | Stop calling after this many requests a day (for metered free tiers). |
| `preload` | | Official plugins only: `false` offers it in Get plugins instead of installing it for everyone. |
| `hosts` | yes | Every host the plugin may contact. The app refuses to call anything else. |
| `request` | yes | See below. |
| `error` | | A path that, if it has a value, is shown as the plugin's error (e.g. `"$.error"`). |
| `items` | | A path to the list of results (default `$`, the whole response as one item). |
| `limit` | | Keep at most this many results, nearest first (default 300). |
| `item` | yes | How to read each result (below). |

### `request`

```json
"request": {
  "url": "https://api.example.org/near?lat={lat}&lon={lon}&r={radius_m}",
  "headers": {"x-api-key": "{key}"}
}
```

- `url` is one URL; `urls` is a list tried in order, for fallbacks.
- `overpass` is an OpenStreetMap Overpass query. The app sends it to the main Overpass server and a mirror, so you don't
  list hosts for it.
- Placeholders: `{lat}`, `{lon}`, `{radius_m}`, `{radius_km}` (whole km), `{radius_km:3}` (3 decimals), `{radius_nm}`
  (nautical miles), `{key}`, `{date}` (today, UTC, `YYYY-MM-DD`) and `{date:-14}` (14 days ago).
- For APIs that take a bounding box instead of a radius: `{south}`, `{west}`, `{north}`, `{east}`, a box the size of
  the search radius around the user (decimal degrees).
- HTTPS only. The host part of every URL must be written out (not a placeholder) and listed in `hosts`.

### `item`

| Key | Meaning |
|---|---|
| `title` | Required. Results without a title are skipped. |
| `id` | Something unique per result (keeps the AR target steady between refreshes). |
| `subtitle` | One short line. |
| `lat`, `lon` | Where it is. If you give these, results without them are skipped. |
| `altM` | Height above sea level in metres (aircraft, peaks). |
| `heightM` | Height above the ground in metres (a camera on a pole). |
| `image`, `gallery` | A picture URL, and a path to more of them. |
| `url` | The main link. |
| `fields` | `[["Label", "expression"], …]`: details shown on the item page. Empty values are hidden. |
| `links` | `[["Label", "expression"], …]`: links to sources. A link with any missing part is dropped. |

## Expressions

Every value in `item` is an expression evaluated against one result:

| Form | Example | Meaning |
|---|---|---|
| Path | `$.properties.name` | A value. `$` is the current result. |
| Array index | `$.photos[0].url`, `$.list[-1]` | First, or last, element. |
| All elements | `$.photos[*].url`, `$.pages.*` | Every element of an array, or every value of an object. |
| Odd keys | `$.tags['addr:street']` | Keys with colons, spaces and so on. |
| Fallback | `$.name || $.title || 'Unnamed'` | The first alternative with a value. `'text'` is a literal. |
| Pipes | `$.alt_baro | num | mul:0.3048 | round` | Transform the value, left to right. |
| Template | `Magnitude {$.mag | round1} · {$.place}` | Text with expressions in `{…}`. Empty " · " parts are dropped. |
| Your position | `https://example.org/map/{@lat}/{@lon}` | `{@lat}` and `{@lon}` are the user's coordinates (useful in links). |

Pipes:

| Pipe | Does |
|---|---|
| `num` | Reads a number, including from text like `"1791 m"`. |
| `round`, `round1`, `thousands` | Round to a whole number, to one decimal, or with thousands separators (`3,581`). |
| `mul:x`, `div:x`, `add:x` | Arithmetic (`mul:0.3048` turns feet into metres). |
| `nonzero` | Hides 0. |
| `trim`, `upper`, `lower`, `cap` | Text case. `cap` capitalises the first letter. |
| `take:n` | Shortens to n characters with "…". |
| `replace:old=>new` | Replaces text. Quote the argument to keep spaces: `replace:'_=> '`. |
| `removeprefix:x`, `removesuffix:x`, `noext` | Trim text; `noext` drops a file extension. |
| `prefix:x`, `suffix:x` | Add text, only when there's a value (`suffix: m`). |
| `default:x` | A value to use when there isn't one. |
| `date` | Milliseconds since 1970 or an ISO date to `YYYY-MM-DD`. |
| `localtime` | An ISO date-time (or milliseconds) to `HH:mm` in the phone's time zone. |
| `wktlat`, `wktlon` | Latitude or longitude from WKT `Point(lon lat)` text (Wikidata returns these). |
| `compass` | Degrees to `N`, `NE`, … |
| `url` | Adds `https://` if a website has no scheme. |
| `wikiurl` | An OSM `wikipedia=en:Title` tag to an article link. |
| `enc` | URL-encodes. |
| `join:sep`, `first`, `count`, `limit:n` | Work on a whole list (for example after `[*]`). |

## Icons

`airplane-tilt`, `barcode`, `binoculars`, `book-open`, `broadcast`, `buildings`, `butterfly`, `camera`, `cloud-sun`,
`compass`, `crosshair`, `detective`, `eye`, `globe`, `house`, `image`, `info`, `key`, `leaf`, `link`, `map-pin`,
`map-trifold`, `moon`, `mountains`, `navigation-arrow`, `planet`, `radio`, `scan`, `security-camera`, `storefront`,
`sun`, `tag`, `text-t`, `warning`, `webcam`, `wind` (all from Phosphor Icons). Need another one? Open an issue.

## Submitting

1. Fork the repo and add `plugins/community/<your_id>/plugin.json`.
2. Run `python tools/build_index.py`. It checks your manifest the same way the app does and updates
   `plugins/index.json`. Commit both files.
3. Optionally, try it against the live API:
   `cd android && OMNISCOPE_LIVE=1 ./gradlew testDebugUnitTest --tests '*ManifestTest*'`.
4. Open a pull request. CI runs the same check. Once it's merged, it shows up in **Get plugins**.

### Rules

- **Free first.** If an API needs a key or has a paid tier, set `cost` and `key` honestly.
- **Only what you need.** List only the hosts you call. Don't send anything the API doesn't need.
- **Respect the API's terms.** Use `freshness` and a sensible default `radius` so the plugin doesn't hammer a free
  service; mention rate limits in the description.
- Official plugins (`plugins/official/`) ship with the app and are maintained in this repo. `native` plugins (code in
  the app) can only be official.

## How the app keeps installs safe

- Plugins are data, not code: they can only fetch from their listed hosts and map JSON into items.
- An install is pinned to the commit the catalogue was read at and checked against `sha256` in `index.json`, so a later
  change to the repo can't silently change what's on your phone. Updates are offered when `version` goes up.
- You can add other GitHub repos with their own `plugins/index.json` as sources in the app. Their plugins are never
  marked official.
