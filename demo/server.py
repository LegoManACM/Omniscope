"""Tiny local server for the Omniscope demo.

Serves the static page and proxies GET requests to an allowlist of keyless
APIs (avoids CORS issues, sets a proper User-Agent, caches for 5 minutes).

    python server.py            -> http://localhost:8765
"""
import functools
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "8765"))
ROOT = os.path.dirname(os.path.abspath(__file__))
UA = "omniscope-demo/0.1 (local prototype)"
CACHE_TTL = 300
_cache: dict[str, tuple[float, int, str, bytes]] = {}

ALLOWED_HOSTS = {
    "nominatim.openstreetmap.org",
    "api.open-meteo.com",
    "air-quality-api.open-meteo.com",
    "api.sunrise-sunset.org",
    "macrostrat.org",
    "en.wikipedia.org",
    "commons.wikimedia.org",
    "api.inaturalist.org",
    "earthquake.usgs.gov",
    "api.adsb.lol",
    "api.airplanes.live",
    "api.wheretheiss.at",
    "services.swpc.noaa.gov",
    "restcountries.com",
    "date.nager.at",
    "de1.api.radio-browser.info",
    "all.api.radio-browser.info",
    "overpass-api.de",
    "overpass.private.coffee",
    "api.weather.gov",
    "api.windy.com",
}


def windy_key() -> str:
    """Windy Webcams key from $WINDY_WEBCAMS_KEY or demo/windy_key.txt (read per request, no restart needed)."""
    key = os.environ.get("WINDY_WEBCAMS_KEY", "").strip()
    if not key:
        try:
            with open(os.path.join(ROOT, "windy_key.txt"), encoding="utf-8") as f:
                key = f.read().strip()
        except OSError:
            pass
    return key

# Real-time sources: never cache.
LIVE_HOSTS = {"api.adsb.lol", "api.airplanes.live", "api.wheretheiss.at"}


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("%s\n" % (fmt % args))

    def do_HEAD(self):
        self._send(405, "text/plain", b"")

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            return super().do_GET()
        if parsed.path != "/api":  # serve nothing else: keeps windy_key.txt / server.py private
            return self._send(404, "text/plain", b"not found")

        target = urllib.parse.parse_qs(parsed.query).get("u", [""])[0]
        host = urllib.parse.urlparse(target).hostname or ""
        if not target.startswith("https://") or host not in ALLOWED_HOSTS:
            return self._send(403, "application/json", json.dumps({"error": f"host not allowed: {host}"}).encode())

        hit = _cache.get(target)
        if hit and time.time() - hit[0] < CACHE_TTL:
            return self._send(hit[1], hit[2], hit[3])

        headers = {"User-Agent": UA, "Accept": "application/json, application/geo+json, */*"}
        if host == "api.windy.com":
            key = windy_key()
            if not key:
                return self._send(401, "application/json", b'{"error": "no_key"}')
            headers["x-windy-api-key"] = key  # stays server-side, never sent to the browser
        req = urllib.request.Request(target, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                status, ctype, body = r.status, r.headers.get("Content-Type", "application/json"), r.read()
        except urllib.error.HTTPError as e:
            status, ctype, body = e.code, e.headers.get("Content-Type", "text/plain"), e.read()
        except Exception as e:  # timeouts, DNS, etc.
            return self._send(502, "application/json", json.dumps({"error": str(e)}).encode())

        if status == 200 and host not in LIVE_HOSTS:
            _cache[target] = (time.time(), status, ctype, body)
        self._send(status, ctype, body)

    def _send(self, status, ctype, body):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), functools.partial(Handler, directory=ROOT))
    print(f"Omniscope on http://localhost:{PORT}", flush=True)
    server.serve_forever()
