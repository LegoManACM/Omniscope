# Free APIs for a "know everything about where I am / what I'm pointing at" app

Compiled 2026-11-19 from six parallel research agents (forums, GitHub lists, vendor docs).

**Legend:** 🔓 = no key / no signup · 🔑 = free key or account · 📱 = runs on-device (no quota) · ⚠️ = restrictive terms or shaky
**Caveat:** the agents hit search rate limits, so many quotas came from third-party pages. Anything marked *(unverified)* — check the vendor page before building on it. Free tiers change often.

---

## 1. Where am I — geocoding, places, boundaries

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Nominatim (OSM) | Reverse geocode → full address, admin boundaries | 1 req/s total for your whole app; must cache, real User-Agent ⚠️ | 🔓 |
| Overpass API (OSM) | Query *anything* in OSM around a point: shops, benches, historic sites, land use, parks | Shared fair use, 429 when busy ⚠️ — self-host for production | 🔓 |
| Photon (Komoot) | OSM geocoder + autocomplete + reverse | Demo/testing only ⚠️ (self-hostable) | 🔓 |
| BigDataCloud client reverse geocode | Lat/lon → city/locality/country, IP fallback | "Fair use", no hard limit | 🔓 |
| GeoNames | Nearby places, timezone, elevation, postal codes | ~1 req/s, few thousand/day *(unverified)* | 🔑 username |
| LocationIQ | Geocode, reverse, timezone, POI | 5,000/day, 2 req/s | 🔑 |
| Geoapify | Geocode, places/POI, routing, isochrones, tiles | ~3,000 credits/day | 🔑 |
| OpenCage | Geocoding + timezone/currency/sun annotations | 2,500/day, testing only ⚠️ | 🔑 |
| Pelias / geocode.earth | OSM geocoder | Free self-host; hosted trial limited | 🔑 / self-host |
| Mapbox Geocoding | Geocoding, POI | ~100k/month *(unverified)* | 🔑 |
| Google Maps Platform | Geocoding, Places, Elevation, Time Zone | Per-SKU monthly free allowance (since Mar 2025) | 🔑 + billing |
| Foursquare Places | POI search, categories, details | 500–10,000 calls (sources conflict) | 🔑 |
| FSQ OS Places | Open Foursquare POI dataset (Apache 2.0) — bulk download, not live | Free | 🔓 |
| US Census Geocoder | Address ↔ coords + census geographies | No published limit | 🔓 (US) |
| FCC Census Block API | Lat/lon → census block / FIPS | *(unverified)* | 🔓 (US) |
| Zippopotam.us | Postal code → place + coords | Free | 🔓 |
| what3words | 3-word addresses | 100 conversions/month | 🔑 |
| Open Location Code (Plus Codes) | Address-like codes, offline library | Unlimited | 📱 |
| time.now | Timezone lookup | Free, commercial OK *(unverified)* | 🔓 |
| Regrid | US land parcels, owner, zoning | Limited free tier *(unverified)* | 🔑 (US) |
| ArcGIS Hub / city ArcGIS REST | Parcels, zoning, schools, hospitals per city | Free for public layers | 🔓 (per city) |
| Socrata SODA | City open data: crime, permits, 311, property | Free; app token raises limits | 🔓 (US cities) |

## 2. The ground — elevation, geology, land, soil

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Open-Topo Data | Elevation (SRTM, ASTER, etc.), batch 100 pts | 1 call/s, 1,000/day; self-hostable | 🔓 |
| USGS EPQS | High-res US elevation (3DEP) | No stated limit | 🔓 (US) |
| Open-Elevation | Elevation | Public instance unreliable ⚠️ | 🔓 |
| **Macrostrat** | Geologic map unit + rock column under your feet | Free, CC BY 4.0 | 🔓 |
| **Paleobiology Database** | Fossils found near here, by era | Free | 🔓 |
| ISRIC SoilGrids | Soil texture, pH, carbon, clay at 250 m | Free | 🔓 |
| **NOAA geomagnetic calculators** | Magnetic declination / full field at your spot (fixes compass heading!) | Free | 🔑 registration |
| ESA WorldCover | 10 m global land cover (download, not API) | Free | 🔑 account |

## 3. Weather & atmosphere

| API | What you get | Free tier | Auth |
|---|---|---|---|
| **Open-Meteo** (the MVP) | Forecast, current, UV, historical back to 1940, climate projections, elevation; also Air Quality, Marine, Flood sub-APIs | 10k calls/day non-commercial, CC BY 4.0 | 🔓 |
| NWS api.weather.gov | US forecasts, observations, **alerts by point** | User-Agent required | 🔓 (US) |
| MET Norway Locationforecast | Global hourly forecast | Fair use, identify yourself, honor caching | 🔓 |
| RainViewer | Radar + satellite precip tiles | Free | 🔓 |
| Meteoalarm | EU national weather warnings (CAP feeds) | Free | 🔓 (EU) |
| ECMWF Open Data | Raw global model output (IFS, AIFS) | Free | 🔓 |
| NASA POWER | Solar radiation, temp, precip climatology | Free | 🔓 |
| Copernicus CDS (ERA5, CAMS) | Reanalysis, air-quality forecasts | Free account | 🔑 |
| Sunrise-Sunset.org | Sunrise/sunset/twilight | Free | 🔓 |
| US Naval Observatory | Sun/moon rise/set, phase, eclipses | Free | 🔓 |
| Hong Kong Observatory | Weather, quakes, climate | Free | 🔓 (HK) |

## 4. Air, hazards, disasters

| API | What you get | Free tier | Auth |
|---|---|---|---|
| OpenAQ v3 | Ground-station PM2.5, O3, NO2… near you | 60/min, 2,000/hr | 🔑 |
| WAQI / AQICN | Global AQI by geo | Free token, quota applies | 🔑 |
| PurpleAir | Hyperlocal low-cost sensor PM2.5 | 1M free points then paid *(unverified)* | 🔑 |
| IQAir AirVisual | Nearest city AQI + weather | Community tier *(unverified)* | 🔑 |
| Ambee | Pollen, AQ, weather | Free tier *(unverified)* | 🔑 |
| USGS Earthquakes (GeoJSON + FDSN radius search) | Live & historic quakes near you | Free | 🔓 |
| EMSC | Euro-Med quake alerts | Free | 🔓 |
| NASA FIRMS | Active fire detections (satellite) | 5,000 txn / 10 min | 🔑 MAP_KEY |
| NASA EONET v3 | Wildfires, storms, volcanoes, floods w/ geometry | Free | 🔓 |
| GDACS | Global disaster alerts with severity | Free | 🔓 |
| **Safecast** | Citizen radiation readings, CC0 | Free, no signup | 🔓 |
| US Drought Monitor | Weekly drought category | Free downloads | 🔓 (US) |

## 5. Water, tides, snow

| API | What you get | Free tier | Auth |
|---|---|---|---|
| NOAA CO-OPS Tides & Currents | Tide predictions, water levels, currents | Free | 🔓 (US) |
| NOAA NDBC buoys | Wave height, wind, sea temp | Free | 🔓 |
| Open-Meteo Marine / Flood | Waves, SST; river discharge (GloFAS) | See Open-Meteo | 🔓 |
| USGS Water Services | Stream flow, gauge height every 15 min | Free | 🔓 (US) |
| NRCS SNOTEL (AWDB) | Snowpack, SWE, soil moisture | Free | 🔓 (US West) |
| WorldTides | Global tides | Trial credits then paid | 🔑 |

## 6. Sky & space

| API | What you get | Free tier | Auth |
|---|---|---|---|
| NOAA SWPC JSON | **Aurora forecast (Ovation)**, Kp index, space-weather alerts | Free | 🔓 |
| CelesTrak GP data | Orbital elements for every satellite (compute what's overhead yourself) | Free, cache politely | 🔓 |
| N2YO | Satellite passes over your location, visible passes | ~1,000/hr *(unverified)* | 🔑 |
| Open Notify | ISS position, people in space | Free | 🔓 |
| Where the ISS at | ISS position, footprint, visibility | Free | 🔓 |
| Space-Track.org | Authoritative TLEs, conjunctions | Strict rate limits | 🔑 |
| NASA api.nasa.gov | APOD, near-Earth asteroids, DONKI solar flares/CMEs | Free key (~1,000/hr) | 🔑 |
| Astronomy API | Sun/moon/planet positions, star charts | Monthly quota *(unverified)* | 🔑 |
| **Astrometry.net** | Photo of the night sky → exact stars/constellations (plate solving) | Free | 🔑 |
| NASA GIBS | 1,000+ satellite imagery layers by date (WMTS) | Free | 🔓 |
| Copernicus Data Space / Sentinel Hub | Sentinel satellite imagery, time series | ~10k processing units/month | 🔑 |

## 7. What's moving around me — aircraft, ships, balloons, radio

| API | What you get | Free tier | Auth |
|---|---|---|---|
| adsb.lol | Aircraft within radius of a point (incl. military) | Free; key-for-feeders planned | 🔓 |
| airplanes.live | Aircraft around a point | ~1 req/s, non-commercial | 🔓 |
| adsb.fi | Aircraft positions | Non-commercial *(unverified)* | 🔓 |
| OpenSky Network | Aircraft state vectors, history | 400 credits/day anon, 4,000 registered | 🔓/🔑 |
| ADS-B Exchange | Unfiltered aircraft incl. military | Non-commercial, key | 🔑 |
| aisstream.io | Live ship positions via WebSocket, bounding box | Free beta, no SLA | 🔑 |
| AISHub | Ship positions | Only if you contribute a feed ⚠️ | 🔑 |
| **SondeHub** | Live weather-balloon (radiosonde) tracking | Free *(unverified)* | 🔓 |
| **aprs.fi** | Ham radio APRS stations/trackers near you | Free non-commercial, attribution | 🔑 |
| **Radio Browser** | Internet radio stations with coordinates — "stations from around here" | Free, open source | 🔓 |
| OpenAIP | Airspace, airports, navaids (drone no-fly context) | Non-commercial *(unverified)* | 🔑 |

## 8. Getting around — transit, bikes, EV, traffic

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Transitland v2 | Agencies, stops, departures, GTFS-RT worldwide | Free plan, rate-limited; commercial paid | 🔑 |
| Mobility Database | Catalog of every GTFS / GTFS-RT feed URL | Free | 🔓 |
| GBFS feeds | Live bike/scooter share availability, ~700–900 systems | Free | 🔓 |
| Navitia | Journey planning, realtime, isochrones | 5,000 req/month | 🔑 |
| TfL Unified | London everything | ~500/min with key | 🔑 (London) |
| MBTA v3 | Boston predictions, vehicles | ~1,000/min with key | 🔑 (Boston) |
| BART | Bay Area departures | Public dev key | 🔑 (SF) |
| Open Charge Map | EV chargers worldwide | Free key | 🔑 |
| NREL Alt Fuel Stations | EV/CNG/hydrogen stations | ~1,000/hr | 🔑 (US) |
| EIA | Weekly US gas/diesel prices (regional, not per-station) | Free | 🔑 (US) |
| TomTom Traffic | Traffic flow & incidents | ~2,500/day *(unverified)* | 🔑 |

## 9. Point the camera — on-device (free, offline, no quota) 📱

| Tool | What it identifies |
|---|---|
| Google ML Kit | ~400 image labels, barcodes, OCR, object detection, custom TFLite models |
| Apple Vision | Classification, OCR, barcodes, objects, faces |
| MediaPipe | Classification, detection, segmentation model bundles |
| TensorFlow Lite models (Kaggle/TF Hub) | MobileNet, iNat-trained plant/insect/bird classifiers (check licenses) |
| **BirdNET** (Cornell) | Bird species **from audio**, runs locally |
| Tesseract OCR | Printed text |

## 10. Point the camera — cloud recognition

| API | What it identifies | Free tier | Auth |
|---|---|---|---|
| **Pl@ntNet** | Plants (leaf/flower/fruit/bark) | 500 IDs/day | 🔑 |
| Plant.id (Kindwise) | Plants + disease diagnosis | 100 credits on signup | 🔑 |
| Google Cloud Vision | Labels, **landmarks**, logos, OCR, objects | 1,000 units/month per feature | 🔑 |
| Azure AI Vision | Tags, landmarks, captions, OCR | 5,000/month, 20/min | 🔑 |
| Clarifai | General concepts, custom models | 1,000 ops/month | 🔑 |
| Hugging Face Inference | Any hosted vision model (CLIP, ViT, species classifiers) | Small monthly credit | 🔑 |
| Roboflow | Detection/classification models from Universe | 10 credits/month (or self-host free) | 🔑 |
| Sightengine | Moderation, AI-image detection | 2,000 ops/month | 🔑 |
| OCR.space | OCR images/PDFs | 25,000/month | 🔑 |
| Plate Recognizer | License plates (+ make/model add-on) | 2,500/month | 🔑 |
| ⚠️ iNaturalist CV | Species suggestion — **not public**, fee-based only | — | — |

## 11. Look up what you scanned — products, books, art

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Open Food Facts | Food by barcode: nutrition, Nutri-Score, allergens | Free, User-Agent | 🔓 |
| Open Beauty Facts / Open Products Facts | Cosmetics / household products by barcode | Free | 🔓 |
| UPCitemdb | Generic UPC/EAN lookup | 100/day trial | 🔓 |
| Open Library | Books by ISBN, covers | Free | 🔓 |
| Google Books | Books by ISBN | Key optional | 🔓/🔑 |
| Met Museum | Artworks + images | Free | 🔓 |
| Art Institute of Chicago | Artworks | Free | 🔓 |
| Harvard Art Museums / Rijksmuseum / Smithsonian / Europeana | Artworks & objects | Free key | 🔑 |
| Wikipedia REST | Summary + image for any recognized name | Free | 🔓 |

## 12. Nature — what lives here

| API | What you get | Free tier | Auth |
|---|---|---|---|
| iNaturalist API (data) | Observations & species within radius | ~60–100 req/min | 🔓 |
| GBIF | Species occurrence records in a polygon | Free | 🔓 |
| eBird | Recent & notable bird sightings near you | Free | 🔑 |
| **xeno-canto** | Bird sound recordings by location/species | Free (CC per recording) | 🔑 (v3) |
| BirdWeather | Live detections from BirdNET listening stations | Free | 🔓 |
| **Movebank** | Animal tracking / migration data | Per-study permissions | 🔓 |
| FishWatch | Fish species info | Free | 🔓 |
| IUCN Red List | Conservation status | Non-commercial | 🔑 |

## 13. History, photos & the story of this place

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Wikipedia GeoSearch | Articles within radius of you | Free | 🔓 |
| Wikimedia Commons geosearch | Photos taken near here | Free | 🔓 |
| Wikidata SPARQL | Every geo-tagged entity nearby + facts | 60 s compute/min, timeouts ⚠️ | 🔓 |
| Library of Congress JSON | Historic maps, photos, newspapers | Free | 🔓 |
| Smithsonian Open Access | Millions of CC0 objects/images | Free (api.data.gov key) | 🔑 |
| Europeana | European heritage items, distance filter | Free | 🔑 |
| NYPL Digital Collections | Historic maps/photos (NYC) | Free token | 🔑 |
| **Historypin** | Geotagged historic photos & stories | Free account | 🔑 |
| Flickr API | Geotagged photos incl. Commons historic | Non-commercial | 🔑 |
| Mapillary v4 | Street-level imagery near you | Free token | 🔑 |
| **Panoramax** | Open federated Street View alternative | No published limits | 🔓 |
| KartaView | Street-level imagery | *(unverified)* | 🔑 |
| **Freesound** | Geotagged sound recordings — the soundscape of a place | Free | 🔑 |
| **Windy Webcams** | Webcams near you | ~500/day testing key | 🔑 |
| HMdb | Historical markers | No confirmed public API ⚠️ | — |

## 14. Civic, demographics, crime

| API | What you get | Free tier | Auth |
|---|---|---|---|
| US Census Bureau | Population, income, demographics by geography | Free | 🔑 (US) |
| World Bank | Country indicators | Free | 🔓 |
| Eurostat | EU statistics | Free | 🔓 (EU) |
| data.gov CKAN | Catalog of US federal datasets | Free | 🔓 |
| police.uk | Street-level crime by lat/lon | Free | 🔓 (UK) |
| OpenStates v3 | State legislators by location | Daily limit | 🔑 (US) |
| ProPublica Congress | Members, bills, votes | 5,000/day, non-commercial | 🔑 (US) |
| ⚠️ Google Civic Info | Representatives lookup **shut down Apr 2025** (district IDs only now) | — | 🔑 |
| GDELT DOC 2.0 | Geotagged global news | Free | 🔓 |

## 15. Things to do — events, food, outdoors

| API | What you get | Free tier | Auth |
|---|---|---|---|
| Ticketmaster Discovery | Events by lat/lon + radius | ~5,000/day | 🔑 |
| SeatGeek | Events by location | Free key | 🔑 |
| PredictHQ | Events + holidays with impact scores | Dev tier *(unverified)* | 🔑 |
| Yelp Fusion | Restaurants/businesses nearby | Trial tier, daily cap | 🔑 |
| NPS API | National parks, alerts, events | Free | 🔑 (US) |
| Recreation.gov RIDB | Campgrounds, trails, rec areas | Free | 🔑 (US) |
| MusicBrainz | Venues & artists | 1 req/s | 🔓 |
| ⚠️ Eventbrite | Public event search **retired** | — | — |
| ⚠️ Geocaching | Partners only | — | — |

## 16. Context utilities — country, time, calendars

| API | What you get | Auth |
|---|---|---|
| REST Countries | Language, currency, flag, capital | 🔓 |
| Nager.Date | Public holidays, 90+ countries | 🔓 |
| Frankfurter | Currency rates (ECB) | 🔓 |
| Hebcal | Jewish calendar, Shabbat times by location | 🔓 |
| Aladhan | Islamic prayer times by location | 🔓 |

## 17. Invisible signals — Wi-Fi & cell

| API | What you get | Free tier | Auth |
|---|---|---|---|
| OpenCelliD | Cell tower locations (CC BY-SA) | Free token | 🔑 |
| beaconDB | Wi-Fi/cell geolocation (Mozilla Location Service successor) | Free, thin coverage | 🔓 |
| WiGLE | Crowd-sourced Wi-Fi networks by area | Daily quota, EULA | 🔑 |
| ⚠️ Mozilla Location Service | Dead for third parties since June 2024 | — | — |

---

## Gaps — no good free API exists

- **Lightning:** Blitzortung has no public API (private/entertainment use only); Xweather is paid.
- **Light pollution / dark sky:** no read API — derive from raw NASA VIIRS night-lights data.
- **Avalanche danger:** no public API found.
- **Noise levels:** none; approximate from OSM road/rail/airport proximity.
- **Mushrooms, wine labels, fonts, reverse image search, music ID (Shazam):** no free public API. (TinEye paid; ACRCloud trial only.)
- **Ride share (Uber/Lyft):** partner-only. **Per-station gas prices:** none free in US (Tankerkoenig in Germany).

## Architecture takeaways

1. **On-device first for the camera:** ML Kit / Apple Vision for barcodes, text and labels, with no quota. Use cloud only for species (Pl@ntNet) and landmarks (Cloud Vision / Azure free tiers).
2. **Open-Meteo + NWS + USGS + NOAA SWPC** cover most of the environment layer, with no keys needed.
3. **OSM (Overpass + Nominatim) + Wikipedia/Wikidata/Commons geosearch** cover most of the "what's here" layer, but public instances are for prototyping only. Self-host or use LocationIQ/Geoapify in production. ODbL attribution is required.
4. **Keep keys server-side:** put a small backend proxy in front of everything that needs a key. It also lets you cache results, which you'll need to stay under the free limits.
5. **Watch "non-commercial" clauses** (OpenSky, airplanes.live, ADS-B Exchange, Open-Meteo, ProPublica, Flickr) if you'll monetize.

## Curated lists to bookmark

- github.com/public-apis/public-apis — use the Geocoding, Science, Environment and Animals sections
- github.com/ripienaar/free-for-dev
- awesome-gis / awesome-geospatial on GitHub
- wiki.openstreetmap.org/wiki/Street-level_imagery — compares Mapillary, Panoramax and KartaView
- wiki.openstreetmap.org/wiki/Overpass_API

*Not yet mined, worth a pass:* Reddit/HN "favorite free API" threads, apilist.fun, publicapis.dev, data.europa.eu, and state 511/DOT feeds (traffic cameras, parking).
