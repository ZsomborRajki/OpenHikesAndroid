# Testing Strategy & Bootstrap Roadmap

Supporting testing workflow, static analysis wiring, and vertical-slice roadmap
for OpenHikes on Android. Enforceable rules and verification commands live in
[AGENTS.md](../AGENTS.md).

---

## Multi-tier testing pyramid

We use an **Acceptance-Test-Driven, vertical-slice workflow** adapted from iOS
OpenHikes’s `OpenHikesUITests`:

1. **Tier 1 — Fast JVM Unit Tests (`src/test`, pure Kotlin / JUnit + Truth/kotlin.test + Turbine):**
   - Covers `:core:model`, `:core:common`, repositories, Room/DAO logic,
     ViewModels, `RecordingFixPolicy`, `AutoPauseWatch`, GPX codecs,
     `HikeTitle` grapheme bounding, and PMTiles/manifest parsers.
   - Runs in seconds on every change via `./gradlew test testDebugUnitTest`
     (`test` runs pure JVM modules; `testDebugUnitTest` runs Android modules).
   - **Fakes over mocks:** Share pure-JVM fakes in `:core:testing` and
     Android/Compose fakes in `:core:testing-android` rather than brittle mock
     frameworks asserting call order.
2. **Tier 2 — Fast JVM Compose UI Scenario & Screenshot Tests (`src/test` + Robolectric + Hilt + `createAndroidComposeRule`):**
   - **Home for the majority of ported `OpenHikesUITests` scenarios.**
   - Drives real Material 3 Expressive screens, real Nav3 back stack, in-memory
     Room DB, and deterministic fake map/location/offline-catalog seams via
     Compose semantics — without booting an emulator. Requires **JDK 21+** for
     Robolectric’s API 36/37 runtimes.
   - Paired with **AGP 9.5+ native Compose screenshot test suites**
     (`src/screenshotTest/kotlin`, suite `screenshotTest`, default target,
     `debug` variant) executed via `./gradlew testDebugScreenshotTestDefaultTestSuite`
     and refreshed via `./gradlew updateDebugScreenshotTestDefaultTestSuite`
     across light/dark themes, compact phone + expanded tablet windows, and up
     to `200%` font scale.
3. **Tier 3 — Instrumented E2E & Accessibility Tests (`src/androidTest` on Gradle Managed Devices):**
   - Focused journeys requiring a real Android OS runtime: MapLibre rendering,
     Foreground Service + location + Live Update notification lifecycle
     (including Android 16.0 compatibility), predictive back & configuration/
     fold/split-screen resizes, document picker intents, and automated
     Accessibility Test Framework checks (`composeTestRule.enableAccessibilityChecks()`).
   - Executed reproducibly on **Gradle Managed Devices** (Pixel Phone + Pixel
     Tablet/Foldable virtual devices defined in `build-logic`).

---

## Porting use cases from iOS `OpenHikesUITests`

- **One vertical slice at a time:** Write Tier 1 domain tests and Tier 2 Compose
  scenario tests *first* from the matching iOS use cases, then implement the
  Android architecture and UI until green. Do not pre-write failing tests for
  unbuilt slices.
- **Shared fixtures (`:core:testing`):** Reuse the exact bundled fixtures from
  iOS (`ThumseeLoopFast.gpx`, `ThumseeWalkWithStop.gpx`, `ThumseeRidgePath`,
  `ThumseeTwinPaths`, `ThumseeLoopTrails`, seeded offline country catalog) in
  pure-JVM `:core:testing` so both pure JVM tests and Android/Compose tests
  assert identical coordinates, distances, elevation profiles, and route-matching
  edge cases.
- **Shared semantic tag vocabulary (`:core:ui`):** Keep kebab-case `testTag`
  constants aligned with iOS accessibility identifiers where concepts match
  (`trail-map`, `map-sheet`, `map-search`, `clear-search-button`,
  `settings-button`, `settings-screen`, `import-gpx-button`, `hike-title-field`,
  `recording-phase`, `recording-point-count`, `offline-country-AT`,
  `offline-maps-search`, etc.) inside a central `OpenHikesTestTags` object in
  `:core:ui`. Enable `Modifier.semantics { testTagsAsResourceId = true }` once
  at the app root so UiAutomator and Macrobenchmark share the same tags.
- **Debug-only launch harness (`LaunchConfig`):** Mirror iOS’s
  `AppLaunchEnvironment` for instrumented runs (in-memory DB, isolated
  DataStore, pre-imported GPX fixture, seeded offline countries, forced
  save/delete failures, simulated offline mode) compiled **only in `debug`
  builds**. `release` builds return `LaunchConfig.Production` with zero test
  hooks compiled in.
- **Port invariants, not iOS workarounds:** Never port tests that only guarded
  SwiftUI/UIKit quirks (e.g., ghost nav-bar height offsets, `.fileImporter` on
  sheets, `isHittable` workarounds); assert the underlying Android/M3 user
  outcome instead.

---

## Static analysis standards

- **Ktlint:** Zero-config formatting gate (`./gradlew ktlintCheck` /
  `./gradlew ktlintFormat`); run before every commit/PR.
- **Type-resolved Detekt 2.0 (`dev.detekt`):** Plain `./gradlew detekt` runs
  syntax-only without type resolution and skips type-aware rules. Wire a root
  aggregate task `./gradlew detektAll` in `build-logic` (`openhikes.lint`) that
  depends on the type-resolved JVM source-set tasks (`detektMain`, `detektTest`)
  and Android variant tasks (`detektDebug` and any registered debug test variant
  tasks), enforcing complexity ceilings, file/function length hygiene, coroutine
  rules, and no wildcard/unused imports.
- **Android Lint + `com.slack.lint.compose:compose-lint-checks`:** Enforces
  Compose state hoisting, modifier forwarding/reuse, slot rules, missing content
  descriptions, and Android API safety with `warningsAsErrors = true`.
- **No silent skips:** CI must verify test tasks actually executed tests
  (including both `test` and `testDebugUnitTest`) and never silently matched
  zero classes.

---

## Architectural bootstrap roadmap

Build the harness in verifiable vertical slices. *(Track concrete tasks in
GitHub issues once created.)*

1. **Phase 0 — Harness & CI skeleton:**
   - Gradle 9.6+ wrapper, AGP 9.5+, JDK 21+ toolchain/CI runtime,
     `libs.versions.toml`, `build-logic` convention plugins.
   - Initial modules: `:app`, `:core:model`, `:core:common`,
     `:core:designsystem`, `:core:ui`, `:core:testing` (pure JVM),
     `:core:testing-android` (Android/Compose).
   - `AndroidManifest.xml` backup/extraction policy (`allowBackup="false"`,
     `dataExtractionRules="@xml/data_extraction_rules"`).
   - Ktlint, type-resolved `detektAll`, Android Lint + Slack Compose lints,
     AGP native `screenshotTest` suite wiring, GitHub Actions CI running
     `ktlintCheck detektAll lintDebug test testDebugUnitTest testDebugScreenshotTestDefaultTestSuite assembleDebug assembleDebugAndroidTest`, and
     passing pure-JVM + Android smoke tests.
2. **Phase 1 — Adaptive Map-First Shell & Settings (Slice 1):**
   - `MaterialExpressiveTheme` (dynamic + outdoor nature fallback).
   - Nav3 `NavBackStack` + adaptive bottom sheet / docked side panel driven by
     the **current app window’s `WindowSizeClass`** (Medium/Expanded width *or*
     Compact height, reacting live to rotation, fold, and split-screen resize).
   - `:core:map` seam with MapLibre Compose + fake test map in
     `:core:testing-android`, `LaunchConfig` debug harness, `:feature:settings`
     backed by Proto DataStore, and Tier 2 + Tier 3 UI/a11y tests matching iOS’s
     launch, orientation/adaptivity, and settings use cases.
3. **Phase 2 — Hike Library, GPX Import & Detail (Slice 2):**
   - Room database (portable entities vs. `HikeLocalState`), GPX 1.1 parser and
     exporter with `RouteBoundary`, `RouteProfile`, grapheme-cluster-safe
     `HikeTitle` / `BoundedText`, `:feature:hikes` (list, search, detail,
     rename, delete with confirmation & atomic stack cleanup, interactive
     elevation profile), and ported Tier 1/2/3 tests using `ThumseeLoopFast.gpx`.
4. **Phase 3 — Recording Engine & Live Updates (Slice 3):**
   - `location` `ForegroundService`, `RecordingFixPolicy`, crash-safe
     `TrackJournal`, `AutoPauseWatch`, Android 16.0-safe Live Updates
     (`Notification.ProgressStyle` + `NotificationCompat.Builder.setRequestPromotedOngoing`),
     `:feature:recording` UI, and deterministic spoofed-track tests.
5. **Phase 4 — OpenHikes Vector Map & Offline Countries (Slice 4):**
   - PMTiles reader + `tiles.openhikes.app` manifest integration in
     `:core:network` / `:core:map`, `:feature:offlinemaps` with `WorkManager`
     downloads, SHA-256 verification, atomic country index in `noBackupFilesDir`,
     and seeded offline-map UI tests.
6. **Phase 5 — Trail Following & Walks (Slice 5):**
   - Pure-JVM directed walk coverage (`TrailWalkCoverage`), DIN 33466 &
     pace-calibrated ETA (`WalkTimeLeft`), `OffTrailWatch`, and spoken
     `JunctionAnnouncer` in `:core:model`.
   - Room v2 walk history + device-local `walk_in_progress_json` (`noBackupFilesDir`)
     and `junction_warnings_enabled` Proto DataStore preference.
   - `TrailWalkController`, `AndroidJunctionVoice`, dual-mode
     `RecordingForegroundService`, and Android 16.0-safe Live Updates following
     mode (with recording outranking following).
   - `:feature:walks` Hike Summary + `WalkOverlayState` map highlight,
     `:feature:hikes` Details/History M3 Expressive tabs & walk controls,
     `:app` Walk Started pill / Time-Left bubble / Junction Warnings toolbar
     button / off-trail & recording-conflict dialogs, and ported Tier 1/2/3 +
     screenshot tests.
7. **Phase 6 — Map Modes & Style Switcher (Slice 6):**
   - Pure-JVM `MapStyleCatalog`, `MapProviderKeys`, `TilePreviewFrame`, and
     `MapAttribution.forMapModes` in `:core:model` (preserving OSMF &
     Thunderforest no-bulk-download invariants and key-gated fallback).
   - Multi-style MapLibre Compose rendering (OpenHikes vector, OpenFreeMap
     Liberty vector, OpenStreetMap raster, plus local-debug-only Stadia &
     Thunderforest raster styles) and `HttpMapStylePreviewProvider` 2×2 tile
     compositing in `:core:map`.
   - `:feature:map` Map Modes screen (`MapModesRoute`) with horizontal style
     cards, stylized cartographic swatches + live tile previews, Clean Map vs.
     Hiking Routes visual cards, Offline Maps summary row, and accessible linked
     attribution footer.
   - `:app` floating toolbar Map Modes button keeping the live map visible in
     both portrait half-sheet and landscape/tablet side-panel layouts, plus
     ported Tier 1/2/3 & screenshot tests.
8. **Phase 7 — Route Editing (Slice 7):**
   - Pure-JVM route-editing domain (`RouteShape`, `RouteCarriedEnds`,
     `RouteTrim`, `RouteStep`/`RouteSteps`, `RouteEdit`, `RouteTrimLine`,
     `EditedLine`, and `RouteStretchRouter`) in `:core:model`.
   - Room v3 `hike_original_route_chunks` & `route_edit_json`, cascade-safe
     in-place hike updates, atomic `RouteEditStore` (preserving original route
     geometry, updating only recorded walks, and invalidating local elevation
     caches), and active-walk `ROUTE_CHANGED` invalidation across
     `:core:database`, `:core:data`, and `:core:location`.
   - Isolated `RouteEditOverlayState`, cut/kept/stretch/pull MapLibre layers,
     draggable map handles, and non-blocking long-press route pull in
     `:core:map`.
   - `:feature:routeedit` Material 3 Expressive trimmer chart, live figures,
     one-tap *Not on Foot* drive removal, *Change a Stretch* (*Follow Trails*,
     *Straighten*, *Remove*), *Whole Route* actions (*Reverse*, *Out and Back*,
     *Close Loop*, *Start Loop Here*), *Undo*, and *Revert to Original*, wired
     from `:feature:hikes` and `:app` with ported Tier 1/2/3 & screenshot
     tests.
9. **Phase 8 — Trail Maker (Slice 8):**
   - Pure-JVM OSM trail routing graph & draft domain (`TrailGraph`,
     `TrailGraphIndex`, `TrailDraftEngine`, `TrailMakerModels`,
     `TrailGraphSquareFile` `OHGS` v1 binary codec with Liang-Barsky bounds
     clipping, `SquareArchive` manifest support, `HikeTitle.drawn`, and
     bundled iOS graph fixtures `ThumseeRidgePath`, `ThumseeTwinPaths`, and
     `ThumseeLoopTrails`) across `:core:model`, `:core:common`, and
     `:core:testing`.
   - Device-local Room v3 `trail_draft` & `trail_stop_recents` tables in
     `noBackupFilesDir`, cached `HttpRoutingGraphSquareDataSource` (30-day TTL,
     32-file cap, HTTP `200`/`204`/`410`/`429` + in-flight coalescing),
     `OfflineFirstTrailDraftRepository`, `OsmTrailLegRouter`,
     `AndroidStopPlaceSearchSource`, `HikeRepository.saveDrawnTrail`, and
     production `TrailGraphRouteStretchRouter` (upgrading Phase 7 Route Editing
     stretch routing to real OSM trails) across `:core:database`,
     `:core:network`, and `:core:data`.
   - Isolated `TrailDraftOverlayState`, solid/dashed/alternative leg layers,
     time bubbles, draggable numbered stop pins, long-press pin drop with leg
     insertion hit-testing, and fake test renderer hooks across `:core:map` and
     `:core:testing-android`.
   - `:feature:trailmaker` Material 3 Expressive stop list (add, reorder,
     delete, drag, tap-to-inspect), Hiking/Walking/Cycling/Driving modes,
     Follow Paths vs. Straight toggle, alternative path picker, live figures,
     offline/unmatched notices with Retry, place card for dropped pins & placed
     stops, in-tree stop search (My Location, recents, geocoder results), and
     Save Trail naming flow wired into `:app`'s adaptive shell & root floating
     toolbar with ported Tier 1/2/3 & screenshot tests.
10. **Phase 9 — Places Along a Trail (Slice 9):**
    - Pure-JVM places, search, and Wikidata domain (`TrailPlaceSymbol`,
      `TrailPlaceOsm`, `TrailPlaceFact`, `TrailPlaceOrder`,
      `TrailPlaceHolding`, `TrailPlaceCorridorSearch`, `PlaceSquareGrid`,
      `TrailPointQuery`, `PlacesNearbyFrame`, `NearbyPlaceSuggestions`,
      `WikidataPlace`/`WikidataPhoto`, `OHPS` v2 `PlaceSquareFile`,
      `TrailPointDecoding`, `WikidataQuery`/`CommonsPhotoQuery`, GPX `<wpt>`
      `<link href>` OSM round-trip, and bundled `ThumseeLoopPlaces.gpx` fixture)
      across `:core:model`, `:core:common`, and `:core:testing`.
    - Proto DataStore `shows_place_pins` & `hidden_place_symbols`, Room v4
      `trail_points` OSM/facts/Wikidata schema, `HikePlaceStore` (deduplication,
      own-only edit guard, cascade-safe recording draft survival), in-memory
      32-entry `WikidataPlaceLookup`, `noBackupFilesDir` cached
      `HttpPlaceSquareDataSource` & `OverpassPlaceDataSource`,
      `HttpWikidataPlaceSource`, and launch-aware UI test scenarios across
      `:core:datastore`, `:core:database`, `:core:network`, and `:core:data`.
    - `TrailPlacesOverlayState`, `MapCameraTracker`, and interactive MapLibre +
      fake place pins, pale candidate pins, center placement pin, long-press
      drop, and *Search This Area* pill hooks across `:core:map` and
      `:core:testing-android`.
    - `:feature:places` Material 3 Expressive place detail (inline editing,
      OSM/Wikidata facts, Wikipedia extract + CC BY-SA 4.0 license, Wikimedia
      Commons photo + author/license credit dialog, Show on Map, Share, Remove),
      map-pan *Add Place*, *Places Around Trail* (kind chips, reach selector,
      in-place add & card preview, *Search This Area*), *Places Nearby*, and
      live recording place picker wired into `:feature:hikes`,
      `:feature:recording`, `:core:location` (`holdsKeepsakes`), and `:app`
      with Tier 1/2/3 & screenshot tests.
11. **Phase 10 — Photos Along a Trail (Slice 10):**
    - Pure-JVM photo domain & archive codecs (`HikePhoto`, `HikePhotoLicense`,
      `PhotoMatchEvidence`, `OrderedHikePhotos`, `PhotoUnavailableReason`,
      `PhotoCreditPlacement`, `PhotoMapPin`, `ExportedHikeArchive`,
      `PhotoTrailAnchor`, `HikePhotoTimeline`, `RecordedRouteLegs`,
      `HikeWalkPhotoTimeline`, `HikePhotoSearchPlan`, `LibraryPhotoMatcher`,
      `PickedPhotoPlacement`, `PhotoCaptureDate`, `ImageDataFormat`,
      `HikePhotoArchiveBuilder` `.zip` exporter with GPX photo `<wpt>` links &
      `Credits.txt`, and GPX timed photo waypoint parsing) across `:core:model`
      and `:core:common`.
    - Room v5 `hike_photos` with CASCADE on hike deletion and place-unfiling on
      place removal, `DiskHikePhotoStore` (`noBackupFilesDir/hike_photos/`,
      512 px thumbnails, 32 MiB LRU cache, EXIF orientation baking, orphan
      reclamation), lossless `PhotoMetadataCodec` EXIF time/GPS/Commons credit
      stamping, `AndroidPhotoLibrarySource` with launch-config test stubbing,
      `MediaStorePhotoLibraryMirror` (`Pictures/OpenHikes`),
      `HikePhotoImportStore` (disk-first durability, dedupe/refiling, fingerprint
      guard), and 30-minute recording shutter-time anchoring across
      `:core:database`, `:core:data`, and `:core:location`.
    - `TrailPhotosOverlayState`, `PhotoCaptureController`, `HikePhotoStrip`,
      and MapLibre + fake tinted photo pin markers & 4:3 thumbnail callout
      previews across `:core:designsystem`, `:core:ui`, `:core:map`, and
      `:core:testing-android`.
    - `:feature:photos` dark full-screen swipeable gallery viewer (chronological
      paging, Show on Map & tap-to-re-expand, Share, confirmed Delete with
      auto-close on last photo, Commons license credit dialog, unavailable
      recovery views) and *Find Photos of This Hike* discovery (untimed guard,
      limited-access *Select More Photos*, live route-fingerprint refresh,
      sequential import with partial-failure retry).
    - Integrated across `:feature:hikes` (Photos section, *Share Hike with
      Photos* `.zip`, map photo pins persisting across History tab),
      `:feature:places` (place photos, staged Add Place photos, *Places Around*
      / *Places Nearby* card photos, gallery preservation on place removal),
      `:feature:recording`, `:feature:settings` (read-only *Photos* storage
      row), and `:app` (`FileProvider`, `PhotoCaptureHost`, floating camera /
      library controls, full-window landscape gallery mode, and Tier 1/2/3 &
      screenshot tests).
12. **Phase 11 — Library Totals, Sorting & Map Library Lines (Slice 11):**
    - Pure-JVM library ordering, totals, and thinned map-line algorithms
      (`HikeListSort`, `HikeListOrder`, `RouteElevationRevision` /
      `RouteElevationMeasurement`, antimeridian-aware `LibraryFootprint`,
      balanced interval-tree `RecordedClocks`, `LibraryTotals` with same-ground
      overlap deduplication, directed walk climb, and redrawn-trail climb
      withholding, two-pass `LibraryLineOutline` thinning, and
      `LibraryLineHitTest`) across `:core:model`.
    - Proto DataStore `hike_list_sort`, transactional custom ordering on
      `HikeLocalStateDao`, walk-count observation on `HikeWalkDao`, off-main
      `LibraryMetricsStore` (totals sweep, background `Hilliest` elevation
      validation, chunk-hash-cached `LibraryLine` outlines, atomic debug batch
      seeding), and `RouteEditStore` elevation-cache upsert across
      `:core:common`, `:core:datastore`, `:core:database`, and `:core:data`.
    - `MapLibraryLineOverlay`, `buildLibraryLineRenderPlan`, and faded
      oldest-first MapLibre `LibraryLineOverlayLayers` drawn beneath selected
      routes in `:core:map`, plus `:app`'s `MapShellViewModel` selected/library
      line separation, Trail Maker suppression, and map tap-to-open hit-testing
      (disabled during active recording, photo callouts, or selected route
      taps).
    - Material 3 Expressive `HikeLibrarySortBar`, accessible reorder mode with
      pinned active hike, predictive back exit, and scroll-to-top behavior in
      `:core:ui` & `:feature:hikes`; reactive `LibraryTotalsScreen` with
      grouped `LibraryMonthChart`, clickable personal records opening
      `HikeDetailRoute`, error/retry card, and counting/redrawn footnotes,
      verified with Tier 1/2/3, Robolectric journey, and Compose screenshot
      tests.
13. **Phase 12 — Trail Analysis & Route Coloring (Slice 12):**
    - Pure-JVM trail breakdown & route-shading algorithms (`TrailSurface`,
      `TrailDifficulty` SAC T1–T6, `TrailBreakdown`, `TrailBreakdownAnalyzer`,
      `HikeTrailAnalysis`, `RouteColoring`, `RouteShade`, `RouteShadeStretch`,
      `RouteSteepness` with smoothed grade windows & short-run absorption, and
      `RouteShadeBlend` boundary sub-step blending) across `:core:model`, plus
      `FakeTrailGraphProvider` in `:core:testing`.
    - Proto DataStore `route_coloring` (defaulting to `Elevation`), Room v6
      persisted `surfaceMetersJson` / `difficultyMetersJson` columns with
      revision-checked `HikeRepository.saveTrailBreakdowns` and route-edit /
      revert clearing in `RouteEditStore`, cache-only
      `RoutingGraphSquareDataSource.readCachedRegion`, and bounded 24-region
      `ArchiveTrailGraphProvider` corridor completeness across `:core:datastore`,
      `:core:database`, `:core:network`, and `:core:data`.
    - Blended GeoJSON `ShadedRouteLineLayer` and per-shade
      `ShadedArrowheadLayers` layered over selected hikes and Trail Maker drafts
      in `:core:map` while preserving route draw order and line patterns.
    - Material 3 Expressive `TrailSurfaceSection`, `TrailDifficultySection`,
      `RouteColoringPicker`, and `RouteShadeKey` in `:core:ui`; Hike Detail
      off-main OSM breakdown analysis & capsule bar cards with coverage
      footnotes, Route Style *All Hikes* shared *Color By*, Trail Maker
      cache-only difficulty shading (`TrailDraftShadingController`) & *On the
      Map* card (with `Elevation` disabled and explanatory note), and
      `MapShellViewModel` off-main steepness / difficulty route shading across
      `:feature:hikes`, `:feature:trailmaker`, and `:app`, verified with Tier
      1/2, Robolectric journey, and Compose screenshot tests.
