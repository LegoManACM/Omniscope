# Omniscope release shrinking. Libraries (Compose, CameraX, MapLibre, ML Kit) ship their own consumer rules.

# NanoHTTPD (the MCP server) is plain Java; keep it whole to be safe.
-keep class fi.iki.elonen.** { *; }

# ZXing decoders are looked up by format.
-keep class com.google.zxing.** { *; }

# Plugin objects are referenced by id at runtime through nativePlugins; keep their classes.
-keep class dev.omniscope.plugins.** { *; }
-keep class dev.omniscope.scan.** { *; }

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
