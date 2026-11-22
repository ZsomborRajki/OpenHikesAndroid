# Architecture Reference

Supporting architectural detail for OpenHikes on Android. Enforceable rules,
toolchain pins, and settled decisions live in [AGENTS.md](../AGENTS.md).

---

## Module layout

Vertical feature slices on top of focused core modules, modeled on
`android/nowinandroid`:

```text
OpenHikesAndroid/
├── build-logic/                 # Gradle convention plugins (composite build)
├── gradle/
│   └── libs.versions.toml       # Single dependency & plugin catalog
├── app/                         # Composition root, MainActivity, Nav3 host,
│                                #   adaptive map shell, debug launch harness
├── core/
│   ├── model/                   # Pure JVM: Hike, HikeWalk, TrailPoint,
│   │                            #   RouteProfile, RouteBoundary, coordinates,
│   │                            #   tile/offline manifest models (zero Android deps)
│   ├── common/                  # Pure JVM: Coroutine dispatchers, Clock,
│   │                            #   Result/error types, geometry, GPX codecs,
│   │                            #   grapheme-safe HikeTitle/BoundedText utilities
│   ├── designsystem/            # MaterialExpressiveTheme, color schemes,
│   │                            #   typography, motion tokens, reusable M3E chrome
│   ├── ui/                      # Shared hiking UI components (HikeRow, elevation
│   │                            #   profile plot, stats rows, map overlays/controls,
│   │                            #   shared OpenHikesTestTags)
│   ├── database/                # Room database(s), DAOs, local-vs-portable entities
│   ├── datastore/               # Proto DataStore for user settings
│   ├── network/                 # OkHttp clients, PMTiles range reader, manifests,
│   │                            #   Overpass/tile network policy
│   ├── data/                    # Repositories (single source of truth), GPX
│   │                            #   import/export, file stores, orphan sweeps
│   ├── location/                # Location feed abstraction, FGS recording engine,
│   │                            #   crash-safe TrackJournal, AutoPauseWatch,
│   │                            #   RecordingFixPolicy, Live Updates controller
│   ├── map/                     # Map surface contract + MapLibre Compose adapter,
│   │                            #   PMTiles/offline country source, route renderers
│   ├── testing/                 # Pure JVM: TestClock, TestDispatchers, domain &
│   │                            #   repository fakes, bundled GPX/graph fixtures
│   └── testing-android/         # Android/Compose: TrailMapHost fake, Robolectric
│                                #   & Compose UI helpers (depends on :core:testing)
└── feature/
    ├── map/                     # Map controls, map modes sheet, hiking-routes toggle
    ├── hikes/                   # Library sheet list, search, hike detail, rename/
    │                            #   delete, stats/elevation, route style, GPX import
    ├── recording/               # Active recording card/screen, pause/resume/stop/
    │                            #   discard flows, auto-pause UI
    ├── offlinemaps/             # Country/region catalog, Extra Detail toggle,
    │                            #   download state & storage management
    └── settings/                # App settings, units, auto-save, About
```

Additional `:feature:*` modules (e.g., `:feature:walks`, `:feature:trailmaker`,
`:feature:routeedit`, `:feature:places`, `:feature:photos`, `:feature:community`)
join only when their vertical slice begins — never add empty placeholder modules.

---

## Storage, backup & device-transfer layout

Android Auto Backup defaults to enabled and includes Room databases, DataStore
files, and internal app files. In addition, on Android 12+ (`targetSdk >= 31`),
`android:allowBackup="false"` disables cloud backup but still permits direct
device-to-device (D2D) transfers unless explicitly excluded.

From Phase 0, configure `app/src/main/AndroidManifest.xml` with:
- `android:allowBackup="false"`
- `android:dataExtractionRules="@xml/data_extraction_rules"`

Rules for `res/xml/data_extraction_rules.xml` and on-disk storage:
- **`<cloud-backup>`:** Exclude all domains (`root`, `file`, `database`,
  `sharedpref`, `external`, `device_root`, `device_file`, `device_database`,
  `device_sharedpref`) so a hiker’s private library, preferences, and local
  drafts are never automatically uploaded to cloud backup.
- **`<device-transfer>`:** Exclude all device-bound state — active recording
  drafts (`TrackJournal`), `HikeLocalState` (walk progress, draft ownership,
  offline claims, local paths), and offline country PMTiles archives plus their
  atomic index. Until an explicit portable-only transfer story exists, exclude
  the entire app from `<device-transfer>` as well; if portable hike data is ever
  opted into local D2D transfer, isolate `HikeLocalState` in a separate Room
  database file excluded from `<device-transfer>`.
- **No-backup directory:** Store non-transferable files (offline country PMTiles
  archives, atomic offline index, `TrackJournal` files, tile/routing caches) in
  `Context.noBackupFilesDir` (or cache dir where appropriate) as defense in
  depth alongside `dataExtractionRules`.

---

## Shared domain & server formats (aligned with iOS OpenHikes)

Keep core hiking semantics aligned across iOS and Android unless an explicit
decision diverges them:

- **Title bounding (`HikeTitle` / `BoundedText`):** Mirror iOS’s `HikeTitle`
  rule in pure-JVM `:core:common` using extended grapheme cluster segmentation
  (`java.text.BreakIterator.getCharacterInstance()`, which implements Unicode
  UAX #29 extended grapheme clusters on JDK 20+ and Android ICU without pulling
  any `android.*` dependency into `:core:common`): trim surrounding whitespace,
  cap at **128 extended grapheme clusters** and **512 UTF-8 bytes**, and
  truncate strictly on whole extended grapheme cluster boundaries so 128
  four-byte emoji fit within 512 bytes and flags, combining marks, or ZWJ
  sequences are never split. Apply to user-entered titles, renamed titles, and
  imported GPX track names including the filename stem fallback.
- **Shared public infrastructure & archive formats:**
  - `tiles.openhikes.app` `manifest.json` (basemap + extras PMTiles archives;
    HTTP `410` triggers manifest refresh).
  - `countries/manifest.json` and per-country/region PMTiles archives (ISO 3166
    area codes; US/CA/RU split by state/province/region; z14 base + z15 *Extra
    Detail*; verified by byte length + SHA-256 before atomic move into place).
  - `routing` PMTiles graph archives (`TrailGraphSquareFile` format).
  - Standard GPX 1.1 import/export (one hike per track, places as waypoints,
    `<trkseg>` preserved as `RouteBoundary`).
- **Offline country ownership:** Own downloaded PMTiles through an atomic index
  file beside the archives (never a Room query on map render threads). An
  unreadable index sweeps nothing; filenames from the network are validated
  against a strict safe-filename allowlist before touching the filesystem.

---

## Map & offline tiles (`:core:map`)

- **Engine:** MapLibre Native / **MapLibre Compose** (`org.maplibre.compose`)
  renders OpenHikes’s PMTiles vector tiles, custom Carto/outdoor styles,
  Waymarked-style hiking-route overlays, 3D terrain, and offline country
  archives natively on GPU.
- **Seam:** Expose `MapCameraState`, `MapOverlayState`, `MapInteractionEvents`,
  and `TrailMapHost` from `:core:map`. In JVM/Robolectric UI tests,
  `:core:testing-android` binds a lightweight semantic fake exposing
  `testTag("trail-map")` and observable camera/overlay state.
- **Provider & network policy:** Respect tile provider terms (never
  bulk-download OSMF raster tiles; OpenHikes map downloads by country). Split
  network requests into:
  - **Interactive** (user viewing the map) — allowed on cellular, blocked when
    offline or under Data Saver.
  - **Speculative** (offline country downloads & prefetching) — unmetered/Wi-Fi
    only, paused under Battery Saver or elevated thermal status.
- **Render-thread discipline:** Never perform synchronous disk I/O or network
  calls on map render threads; decode and prepare route polylines off the main
  thread.
- **Attribution:** Render OpenStreetMap (ODbL), ESA WorldCover (CC BY 4.0),
  Waymarked Trails style credit, and any enabled optional tile provider
  attributions visibly in map chrome and Map Modes.

---

## Background recording & Android system integration

- **Foreground Service (`foregroundServiceType="location"`):** Owns active
  recording and background trail following independently of UI lifecycle.
- **Crash-safe `TrackJournal`:** Append fixes, pauses, and resumes atomically on
  disk during recording; write the final `Hike` route at Stop (or recover on
  next launch after process death). Only a draft with positive local ownership
  is eligible for orphan cleanup.
- **Live Updates & Android 16.0 compatibility:**
  - Declare `android.permission.POST_PROMOTED_NOTIFICATIONS` in the manifest.
  - Build ongoing foreground-service notifications with
    `Notification.ProgressStyle` (API `36.0`) / `NotificationCompat.ProgressStyle`
    and **never** custom `RemoteViews`.
  - Because platform `Notification.Builder.setRequestPromotedOngoing(boolean)`
    was added in API `36.1` while `minSdk = 36` allows Android `16.0` (`36.0`)
    devices, request promotion via
    `NotificationCompat.Builder.setRequestPromotedOngoing(true)` (from
    `androidx.core:core` `1.17+`, or `Notification.EXTRA_REQUEST_PROMOTED_ONGOING`
    / a `Build.VERSION.SDK_INT_FULL` check for `36.1+`), keeping the regular
    ongoing foreground-service notification fully functional on Android 16.0.
  - Rate-limit notification updates and expose Pause/Resume actions only — never
    a one-tap destructive Stop from the lock screen.
- **Energy discipline:** Stop GPS updates completely when paused with movement
  reminders off. Adapt location interval/accuracy under Battery Saver or thermal
  throttling. Do not tick UI timers or redraw map overlays while backgrounded.

---

## Material 3 Expressive component mapping

- **Surface hierarchy:** Separate controls and sheets from the map using M3
  tonal surface containers (`surfaceContainerLow`..`surfaceContainerHighest`),
  crisp elevation shadows, and rounded M3 Expressive shapes — no blur/glass.
- **Fallback palette:** High-contrast, outdoor-legible nature palette (forest /
  moss primary, warm trail-blaze tertiary, neutral stone surfaces) used when
  Dynamic Color is off and pinned in screenshot tests.
- **Expressive widgets:**
  - `FloatingToolbar` (horizontal/vertical) for floating map controls.
  - `SplitButton` and `ButtonGroup` / shape-morphing toggle buttons for primary
    actions (Record / Follow, Map Modes, route style swatches).
  - Material 3 `SearchBar` / docked search in the sheet or side panel.
  - Wavy progress indicators / `LoadingIndicator` for downloads and routing.
  - **Material Symbols** vector drawables — never copied SF Symbols.
- **Copy & formatting:** Keep user-visible copy to 1–2 crisp sentences, American
  English in `values/strings.xml`, typographic apostrophes (`’`), `<plurals>`
  (never `if (count == 1)`), and locale-aware `NumberFormatter` / `MeasureFormat`.
