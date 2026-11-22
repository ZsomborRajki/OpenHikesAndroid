# Privacy Policy — OpenHikes for Android

*Last updated: November 2026*

OpenHikes for Android is designed to keep your hiking data private and on your device:

## 1. No Accounts, No Analytics, No Tracking

- OpenHikes does not require an account or sign-in.
- OpenHikes does not include advertising SDKs, behavioral analytics, or crash telemetry servers controlled by the developer.
- Preferences (such as units, overlay toggles, and map settings) stay stored locally in app-private storage.
- Automatic Android cloud backup (`android:allowBackup="false"`) and automatic device-to-device data extraction (`data_extraction_rules.xml`) are disabled for app data.

## 2. Online Map Tile & Style Requests

When you view maps online without Android Data Saver restricting metered background/foreground access, the app fetches map styles and vector/raster tiles directly from third-party public map providers:

- **OpenFreeMap (`tiles.openfreemap.org`)** — serves the default vector basemap style (`Liberty`, built using OpenMapTiles schema and OpenStreetMap data).
- **Waymarked Trails (`tile.waymarkedtrails.org`)** — serves the optional marked hiking-routes tile overlay when hiking routes are enabled.

These requests naturally include your IP address and the approximate geographic viewport (tile coordinates `z/x/y`) being displayed on screen, plus standard HTTP headers. OpenHikes does not attach user identifiers, account tokens, or advertising IDs to map requests. Requests are blocked when offline or when Android Data Saver restricts metered connections.

## 3. Contact

Questions or privacy reports can be submitted via the [OpenHikesAndroid GitHub repository](https://github.com/ZsomborRajki/OpenHikesAndroid/issues).
