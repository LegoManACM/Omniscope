# Build plan: UI round 1

Source: the four hand-drawn screens (AR on a point, AR on a building, Map, Nav) and the notes that came with them.

## Design rules

- **No emoji anywhere.** Every icon is a vector from **Phosphor Icons** (MIT, thin-line style that matches the
  sketches), compiled into the app as Android vector drawables, so nothing depends on the system emoji font.
- **Colour comes from the category, not the plugin.** Each plugin belongs to one category, and the category has one
  colour and one icon. That colour is used for the dot, the glow, the card border and the map marker.
- Bottom navigation: **AR · Map · List · Nav · Settings**. A **filter** button (funnel) sits bottom-left on AR and
  Map and filters by category.

## AR

- **Compass tape** across the top: cardinal and intercardinal marks that slide with your heading. Underneath it,
  **HDG** (true heading) and **ALT** (*your* altitude) readouts.
- **Items are glowing dots** in their category colour, sized by distance. No text labels.
- **Reticle** in the centre. Whatever is nearest the reticle (within ~5°, with hysteresis and a short dwell so it
  doesn't flicker) becomes the *target*:
  - above the reticle: the target's **GPS coordinates**
  - left: the target's **ALT** (altitude); right: **DIST**
  - below: the target's name on a short leader line
  - an **info card** at the bottom: title, subtitle, a few fields, a thumbnail
  - **tap anywhere** while a card is up to open the full item page
- **Buildings**: faint outlines within ~150 m. The building the reticle ray hits first is highlighted with a
  translucent fill and gets its own card: name (from a place inside it), address (house number + nearest street,
  from the same vector tiles), height, places inside, photos nearby, and an OpenStreetMap link.
- **Long-press anywhere** toggles **scan mode**: a subtle glow around the screen edge and a short vibration. Long-press
  again to leave.

## Scan mode: detector plugins

Detectors are plugins in a **Scan** category, so they're switched on and off in Settings like any other. Several can
run at once and their results are merged.

| Detector | Engine | Licence |
|---|---|---|
| Barcodes and QR codes | Google ML Kit (bundled, on-device) | free, closed |
| Barcodes and QR codes | ZXing | Apache 2.0 |
| Text (Latin) | Google ML Kit text recognition (bundled) | free, closed |
| Objects and scenes | Google ML Kit image labelling (bundled) | free, closed |

Everything runs on the phone with no network and no limits. A product barcode (EAN/UPC) is looked up in **Open Food
Facts** (free, keyless) to get a name and photo. Detections show as boxes on the camera view, and tapping one opens
its item page.

## Item page

A full-screen version of the info card: hero image or gallery, title, subtitle, coordinates, altitude, distance and
bearing, every field, and a **Links** section listing each source that applies (Wikipedia, OpenStreetMap,
iNaturalist, DeFlock, the place's website, the stream URL…). An **Align compass here** action stays.

## Map

- Light map (OpenFreeMap *positron*) with dots in category colours.
- You: a blue dot with a **heading cone**.
- ALPR cameras: a red **view cone** (direction ±30°, about 50 m), plus an **"In camera view"** alert when you're inside
  one.
- A **Nearby** card at the bottom: alerts first, then the nearest things. Tapping a dot swaps in that item's card,
  and tapping the card opens the item page.

## List

The same rows with category icons and colours, nearest first, and the "about here" cards on top.

## Nav: placeholder this round

Later: a driving-tuned map with large, low-noise popups and optional routing around cameras. The likely engine is
**BRouter** (open source, offline on the phone, supports *no-go* points), with its public server or DeFlock's
routing API for trials.

## Settings

- **Plugin grid**: 4 columns, as many rows as needed, grouped by category. Each tile shows the plugin icon in its
  category colour and is dimmed when off, with a badge for freemium or needs-key. Tap a tile for its detail sheet:
  switch, description, source, status, key and quota.
- General: free only, compass alignment (reset), the MCP server.

## Data model changes

- `Plugin.icon` becomes a drawable resource; `Plugin.category` is added.
- `Item` gains `links` (label → URL) and `gallery` (image URLs).
- New `Detector` plugins with `analyze(frame) → List<Detection>`.
