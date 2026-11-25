# Privacy Policy — OpenHikes for Android

*Last updated: November 2026*

OpenHikes for Android is designed to keep your hiking data private and on your device:

## 1. No Accounts, No Analytics, No Tracking

- OpenHikes does not require an account or sign-in.
- OpenHikes does not include advertising SDKs, behavioral analytics, or crash telemetry servers controlled by the developer.
- Preferences (such as units, overlay toggles, and map settings) stay stored locally in app-private storage.
- Automatic Android cloud backup (`android:allowBackup="false"`) and automatic device-to-device data extraction (`data_extraction_rules.xml`) are disabled for app data.

## 2. Online Map Tile & Style Requests

When you view maps online without Android Data Saver restricting metered background/foreground access, the app fetches map styles and vector/raster tiles directly from public map providers:

- **OpenHikes Tile Server (`tiles.openhikes.app`)** — serves the default OpenHikes vector basemap, hiking extras, and optional offline country downloads.
- **OpenFreeMap (`tiles.openfreemap.org`)** — serves the optional `Liberty` vector basemap style when selected in Map Modes.
- **OpenStreetMap (`tile.openstreetmap.org`)** — serves the optional standard OpenStreetMap raster map when selected in Map Modes, and fixed-sample preview tiles when opening Map Modes online.
- **Waymarked Trails (`tile.waymarkedtrails.org`)** — serves the optional marked hiking-routes tile overlay when Hiking Routes are enabled.

Local debug builds configured with optional developer API keys may additionally contact **Stadia Maps (`tiles.stadiamaps.com`)** or **Thunderforest (`tile.thunderforest.com`)** when their styles are selected or previewed in Map Modes; distributed release builds do not embed these keys.

Map viewing requests naturally include your IP address and the approximate geographic viewport (tile coordinates `z/x/y`) being displayed on screen, plus standard HTTP headers including the OpenHikes app `User-Agent`. Automatic Map Modes preview cards always sample a fixed Alpine valley rather than sending your device’s GPS location to third-party providers. OpenHikes does not attach user identifiers, account tokens, or advertising IDs to map requests. Requests are blocked when offline or when Android Data Saver restricts metered connections.

## 3. Contact

Questions or privacy reports can be submitted via the [OpenHikesAndroid GitHub repository](https://github.com/ZsomborRajki/OpenHikesAndroid/issues).
