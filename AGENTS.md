# AGENTS.md

How code is written in this repository: the architectural harness, reference
baselines, toolchain, conventions, testing model, and settled decisions for the
Android port of **OpenHikes**. Read this file before creating any module,
writing code, or changing build configuration.

The sibling iOS repository (`/Users/zsomborrajki/Documents/OpenHikes`) is the
**product and domain reference** — not a code or UI template. We port OpenHikes's
hiking domain, local-first privacy posture, data formats, and user-facing
use cases, while building a genuinely native Android application from scratch.

> **Repository status:** Greenfield bootstrap. Commands and module paths below
> define the target harness that Phase 0 scaffolding must establish. Keep this
> file updated as rules harden; remove any note that becomes obsolete once
> scaffolded.

---

## Product north star

- **Google Maps's hiking-oriented little brother.** Map-first, fast, calm, and
  legible outdoors. The interactive map is the permanent primary surface; hikes,
  recording, search, route details, and offline maps live in an adaptive sheet
  or side panel over/beside it.
- **Private and local-first, with no account.** Hikes live on the device and
  work with zero signal. Never introduce mandatory sign-in, telemetry, or a
  server that holds a hiker's private library.
- **Foundations before feature parity.** Do not port every iOS feature in a
  rush. Build clean module boundaries, deterministic test seams, adaptive
  navigation, offline map rendering, and rock-solid recording first; add
  features as vertical slices backed by UI and unit tests.
- **Native Material 3 Expressive — never iOS gloss.** Do not port Apple's
  Liquid Glass, blur-heavy overlays, iOS navigation bars, or SF Symbols. Follow
  Android's Material 3 Expressive design system throughout (see *Design system*).

---

## Reference baselines

Follow Google's official Android reference repositories the way iOS OpenHikes
follows Apple's *Food Truck* and *Backyard Birds*:

| Reference repository | What we adopt from it |
|---|---|
| [`android/nowinandroid`](https://github.com/android/nowinandroid) | Primary architectural blueprint: `:app` + `:core:*` + `:feature:*` multi-module graph, `build-logic` Gradle convention plugins, offline-first Repository pattern, Unidirectional Data Flow (UDF), Hilt DI, fakes-over-mocks testing, and strict separation of data/domain/UI. |
| [`android/androidify`](https://github.com/android/androidify) | Modern Compose showcase: **Material 3 Expressive** theming/motion and **Jetpack Navigation 3 (`Nav3`)** in a real greenfield app. |
| [`android/nav3-recipes`](https://github.com/android/nav3-recipes) | Canonical Navigation 3 patterns: app-owned `SnapshotStateList` back stack, multi-module entry providers via DI multibindings, ViewModel scoping per entry, and adaptive multi-pane scenes. |

---

## House rules

- **Stay inside the module graph.** `:feature:*` modules never depend on each
  other. Shared state, contracts, and models flow through `:core:*`; `:app` is
  the sole composition root.
- **No legacy Android compatibility baggage.** We target modern Android only
  (see *Platform floor & toolchain*). Do not add XML layouts, Fragments,
  `Navigation 2` (`NavController`/`NavHost`), `SharedPreferences`, KAPT, or
  pre-Android-15 compatibility shims.
- **No glossy/glassmorphism chrome.** Use Material 3 Expressive tonal surface
  containers, dynamic color, and spring motion (`MotionScheme.expressive()`).
- **Never commit secrets.** Optional map provider keys (Stadia, Thunderforest)
  live in gitignored `local.properties` / `secrets.properties` injected via
  `BuildConfig` at build time, never checked into git. A build with no keys must
  compile, run, and render the free OpenHikes map cleanly.
- **Recomposition isolation is enforced on hot paths.** High-frequency state
  (GPS fixes, recording timer ticks, chart scrub position, camera bounds) must
  be read only in leaf composables or deferred to layout/draw phases — never
  hoisted into root screen recompositions.
- **Tests never touch the network, real GPS, wall clock, or singletons.** Every
  external dependency sits behind an injectable seam in `:core:*` with a fake
  implementation in `:core:testing`.
- **No fixed sleeps as barriers.** Never use `Thread.sleep` or arbitrary
  `delay(...)` to wait for UI or async work. Use Compose clock/semantics
  synchronization, `waitUntil`, `TestDispatcher`, and Turbine.
- **One focused test class per file**, mirroring the production package and
  module layout.
- **Accessibility is part of every screen's contract.** Meaningful content
  descriptions, merged composite rows, `48dp` minimum touch targets, semantic
  `testTag`s on leaves (never containers), and `enableAccessibilityChecks()` on
  instrumented suites.
- **Port use cases, not iOS UI mechanics.** When translating a scenario from
  `OpenHikesUITests`, preserve the user goal and domain invariant, not iOS
  gestures, sheet workarounds, or UIKit/SwiftUI chrome.
- **Each fact lives in one place.** Architectural rules live here; feature
  design rationale lives in KDoc headers of the owning types; open work lives
  in GitHub issues, never in checked-in TODO lists.

---

## Platform floor & toolchain

Older Android API support is explicitly out of scope. We use the latest stable
and vetted beta/experimental Jetpack APIs directly.

- **SDK levels:**
  - `compileSdk = 37` (Android 17)
  - `targetSdk = 37` (Android 17 — enforces adaptive resizability at `sw >= 600dp`
    with no opt-out)
  - `minSdk = 36` (Android 16 — guarantees edge-to-edge, predictive back,
    native Live Updates via `Notification.ProgressStyle`, modern Photo Picker,
    and zero legacy permission/UI fallbacks). *If a physical test device requires
    Android 15, `minSdk` may be lowered to `35` in `libs.versions.toml` with a
    single wrapper around Live Updates; do not drop below 35.*
- **Build system:**
  - **Gradle 9.x** + **Android Gradle Plugin (AGP) 9.5+** (built-in Kotlin
    support — **never** apply the legacy `org.jetbrains.kotlin.android` plugin;
    enables native AGP Compose screenshot test suites).
  - **Version Catalog** (`gradle/libs.versions.toml`) as the single source of
    truth for versions and coordinates.
  - **`build-logic/` composite build** providing convention plugins (following
    `nowinandroid`):
    - `openhikes.android.application` / `openhikes.android.application.compose`
    - `openhikes.android.library` / `openhikes.android.library.compose`
    - `openhikes.android.feature` (wires Compose, Material 3 Expressive, Nav3,
      Hilt, Lifecycle, and standard test dependencies)
    - `openhikes.jvm.library` (pure Kotlin modules — fast builds, zero Android
      framework coupling)
    - `openhikes.hilt` (KSP-backed Hilt — never KAPT)
    - `openhikes.lint` (Android Lint + Compose lints + Detekt + Ktlint)
- **Language & compiler:**
  - **Kotlin 2.3+** with the bundled Compose Compiler plugin (`alias(libs.plugins.compose.compiler)`),
    Strong Skipping Mode enabled (default), and `allWarningsAsErrors = true` on
    all project modules.
  - **Beta/experimental opt-ins:** Turning on Jetpack beta/alpha flags and
    `@OptIn` APIs (`ExperimentalMaterial3ExpressiveApi`, `ExperimentalMaterial3AdaptiveApi`,
    Nav3 / Compose experimental APIs) is encouraged when it gives cleaner
    architecture. Prefer explicit `@OptIn(...)` at the call site or owning
    design-system wrapper rather than blanket module-wide suppression, so
    experimental surfaces stay visible and auditable.
- **Core libraries:**
  - **UI:** Jetpack Compose BOM (latest), Material 3 `1.5+` (M3 Expressive),
    `material3-adaptive`, `androidx.activity:activity-compose`.
  - **Navigation:** Jetpack Navigation 3 (`androidx.navigation3:navigation3-runtime`,
    `navigation3-ui`, `lifecycle-viewmodel-navigation3`, `adaptive-navigation3`).
  - **DI:** Hilt with KSP (`hilt-android`, `hilt-lifecycle-viewmodel-compose`,
    `hilt-work`).
  - **Async:** Kotlin Coroutines & Flow, `kotlinx-datetime` / `java.time`.
  - **Persistence:** Room (KSP) for structured entities; Proto DataStore for
    typed preferences; atomic file I/O for track journals, photos, and PMTiles.
  - **Networking & serialization:** OkHttp + `kotlinx.serialization` (JSON/Proto)
    — lean, HTTP/2 + byte-range friendly for PMTiles and manifests.
  - **Maps:** MapLibre Native / **MapLibre Compose** (`org.maplibre.compose`)
    isolated behind `:core:map` (see *Map & offline tiles*).
  - **Background:** `ForegroundService` (`location`), `WorkManager`, Android 16+
    Live Updates (`Notification.ProgressStyle`).

---

## Architecture

### Module layout

Vertical feature slices on top of focused core modules, modeled directly on
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
│   │                            #   Result/error types, geometry & GPX codecs
│   ├── designsystem/            # MaterialExpressiveTheme, color schemes,
│   │                            #   typography, motion tokens, reusable M3E chrome
│   ├── ui/                      # Shared hiking UI components (HikeRow, elevation
│   │                            #   profile plot, stats rows, map overlays/controls)
│   ├── database/                # Room database, DAOs, local-vs-portable entities
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
│   └── testing/                 # Shared fakes, TestClock, TestDispatchers,
│                                #   in-memory stores, bundled GPX/graph fixtures,
│                                #   shared UI test helpers & semantic tag registry
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
join only when their vertical slice begins — do not create empty placeholder
modules ahead of need.

### Dependency rules

1. `:core:model` and `:core:common` are **pure Kotlin JVM** modules
   (`openhikes.jvm.library`). They import nothing from `android.*` or `androidx.*`.
   Every domain rule that can be verified in milliseconds (GPX import/export,
   `RouteProfile`, `RouteBoundary` pause math, `RecordingFixPolicy`,
   `AutoPauseWatch`, `OfflineCountryManifest` parsing, PMTiles header/directory
   decoding) belongs here.
2. `:core:data` depends on `:core:database`, `:core:datastore`, `:core:network`,
   `:core:model`, `:core:common`, and exposes clean **repository interfaces**
   returning `Flow` and main-safe `suspend` functions.
3. `:feature:*` modules depend only on `:core:designsystem`, `:core:ui`,
   `:core:model`, `:core:data` (interfaces), `:core:location` (interfaces),
   `:core:map` (interfaces), and Navigation 3 runtime/UI.
   - Features **never** depend on another `:feature:*` module.
   - Features **never** import Room DAOs, OkHttp types, or MapLibre SDK types.
   - Features **never** depend on `:app`.
4. `:app` wires the concrete implementations via Hilt, hosts the root adaptive
   map-and-sheet scaffold, and collects Nav3 `EntryProvider` multibindings from
   every `:feature:*` module.

### Unidirectional Data Flow (UDF)

- Every screen has a route-level composable (collects `StateFlow` via
  `collectAsStateWithLifecycle()`, forwards events to `ViewModel`) and a
  stateless screen composable that accepts immutable UI state + lambdas.
- Stateless screen composables are what `@Preview`, screenshot tests, and fast
  component tests render directly — never pass a `ViewModel` into a stateless
  composable.
- Expose UI state as a single immutable `sealed interface` or `data class` per
  screen (`StateFlow` with `SharingStarted.WhileSubscribed(5_000)`), keeping
  high-frequency streams (live GPS coordinates, elapsed recording seconds, chart
  scrub index) in separate narrow state holders so fast ticks do not recreate
  screen-wide state objects.
- One-off user-visible events (snackbars, error prompts, navigation triggers)
  are modeled as state or explicit consumed callbacks — never `SharedFlow`
  fire-and-forget events that drop across configuration changes.

### Navigation 3 & adaptive map-first shell

- **You own the back stack.** The primary shell holds a `NavBackStack` (a
  state-backed `SnapshotStateList<OpenHikesRoute>`) of `@Serializable` route
  keys owned at the app shell level (`rememberNavBackStack`).
- **One live map, two primary chrome shapes:**
  - **Compact width (portrait phone):** Persistent bottom sheet over the live
    map with distinct resting detents (peek / half / expanded) plus floating
    Material 3 Expressive map controls (`FloatingToolbar`, search entry,
    my-location, map modes).
  - **Medium/Expanded width or compact height (landscape phone, unfolded
    foldable, tablet — required by Android 17 at `sw >= 600dp`):** Docked side
    panel beside the live map, rendering the **exact same `NavDisplay` and back
    stack**. Rotating or unfolding a device moves the host container without
    recreating routes, losing ViewModels, or dropping open dialogs.
- **Navigation-scoped state:** Use `lifecycle-viewmodel-navigation3` so each
  pushed route gets its own `ViewModelStoreOwner` that survives rotation/folding
  and clears deterministically on pop.
- **Deepest screen claims the map.** A dedicated map-claim coordinator (mirroring
  iOS's `ScreenClaims`) gives map camera/overlay ownership to the top active
  route on the back stack — never "last composable to enter composition".
- **Predictive back is first-class.** Every pushed route, dialog, search bar,
  and bottom sheet expansion handles system back cleanly through Nav3 and
  Material 3's built-in predictive back hooks.
- **Deleting a hike cleans navigation atomically.** Deleting a hike clears map
  selection if it was selected and strips every route on the back stack tied to
  that `hikeId` in one state update.

### Domain contracts shared with iOS OpenHikes

Keep core hiking semantics aligned across iOS and Android unless a deliberate
decision diverges them:

- **Pause / segment boundaries (`RouteBoundary`):** A recording pause (manual or
  auto-pause) or a GPX `<trkseg>` break is **not** distance and **not** trail.
  Length, elevation stats, GPS projection, and surface/difficulty breakdowns
  never bridge across a boundary.
- **Hike truth vs. device-local state:** Separate portable hike data (`Hike`,
  `HikeWalk`, `TrailPoint` — what a hike *is*, exportable/syncable) from
  device-local state (`HikeLocalState` — active walk progress, recording draft
  ownership, offline tile claims, local file paths). Future cloud backup or
  sync must never leak device-local state onto another device.
- **Bounded titles:** Cap hike titles where entered (128 characters / 512 UTF-8
  bytes, matching iOS's `HikeTitle.bounded`).
- **Recording outranks trail following on every surface.** A recording holds
  irreplaceable user data; a followed trail can be re-derived from the next GPS
  fix. Notifications, Live Updates, and widgets must obey this priority rule.
- **Shared server & archive formats:** Reuse OpenHikes's existing public
  infrastructure rather than inventing parallel backends:
  - `tiles.openhikes.app` `manifest.json` (basemap + extras PMTiles archives,
    410 triggers manifest refresh)
  - `countries/manifest.json` and per-country/region PMTiles archives (ISO 3166
    area codes; US/CA/RU split by state/province/region; z14 base + z15 *Extra
    Detail*; verified by size + SHA-256 before moving into place)
  - `routing` PMTiles graph archives (`TrailGraphSquareFile` format)
  - Standard GPX 1.1 import/export (one hike per track, places as waypoints,
    `<trkseg>` preserved as `RouteBoundary`)
- **Offline country ownership:** Own downloaded PMTiles through an atomic index
  file beside the archives (never a Room row read on map render threads). An
  unreadable index sweeps nothing; a name from the network is validated against
  a strict safe-filename allowlist before touching the filesystem.

### Map & offline tiles (`:core:map`)

- **Engine:** Use **MapLibre Native / MapLibre Compose** (`maplibre-compose`) as
  the map renderer. It renders OpenHikes's PMTiles vector tiles, custom Carto/
  outdoor styles, Waymarked-style hiking-route overlays, 3D terrain, and offline
  country archives natively on GPU without proprietary map lock-in.
- **Strict isolation:** Wrap MapLibre behind `:core:map` interfaces
  (`MapCameraState`, `MapOverlayState`, `MapInteractionEvents`, `TrailMapHost`).
  No `:feature:*` module imports `org.maplibre.*`. In JVM/Robolectric UI tests,
  `:core:testing` swaps `TrailMapHost` for a lightweight semantic fake exposing
  `testTag("trail-map")` and observable camera/overlay state.
- **Provider & network policy:** Respect tile provider terms (never bulk-download
  OSMF raster tiles; OpenHikes map downloads by country). Split network requests
  into **interactive** (user viewing map — allowed on cellular, blocked offline/
  Data Saver) and **speculative** (offline country downloads & prefetching —
  Wi-Fi/unmetered only, paused under Battery Saver or thermal throttling).
- **Render-thread discipline:** Never perform synchronous disk I/O or network
  calls on map render threads. Read route polylines off the main thread.
- **Attribution:** Always render required map and data attributions
  (OpenStreetMap ODbL, ESA WorldCover CC BY 4.0, Waymarked Trails style credit,
  and any enabled paid tile provider) visibly in map chrome and Map Modes.

### Background recording & Android system integration

- **Foreground Service (`foregroundServiceType="location"`):** Owns active
  recording and background trail following. Never rely on UI lifecycle to keep
  GPS alive.
- **Crash-safe `TrackJournal`:** Append fixes, pauses, and resumes atomically to
  an on-disk journal during recording; write the final `Hike` route only at Stop
  (or recover cleanly on next launch after process death). Only a draft with
  positive local ownership is eligible for orphan cleanup.
- **Android 16+ Live Updates:** Use `Notification.ProgressStyle` with
  `POST_PROMOTED_NOTIFICATIONS` and `setRequestPromotedOngoing(true)` for active
  recording and trail-following notifications (no custom `RemoteViews`, which
  Live Updates forbid). Rate-limit updates and surface Pause/Resume actions
  (never a one-tap destructive Stop from the lock screen).
- **Energy discipline:** When paused with movement reminders off, stop GPS
  updates completely. Adapt location interval/accuracy under Battery Saver or
  elevated thermal status. When backgrounded, do not tick UI timers or redraw
  map overlays.

---

## Design system — Material 3 Expressive

- **Theme root:** Wrap the app in `MaterialExpressiveTheme` (from
  `androidx.compose.material3` `1.5+`) with `MotionScheme.expressive()`.
- **Color:** Enable **Dynamic Color** (`dynamicLightColorScheme` /
  `dynamicDarkColorScheme`) on supported devices, paired with a high-contrast,
  outdoor-legible **OpenHikes fallback palette** (forest/moss primary, warm
  trail-blaze tertiary, neutral stone surfaces) used when dynamic color is off
  and pinned in screenshot tests for determinism. Always support light and dark
  themes from day one.
- **Hierarchy without gloss:** Establish separation over the map using Material
  3 tonal surface containers (`surfaceContainerLow`..`surfaceContainerHighest`),
  crisp elevation shadows, and rounded M3 Expressive shapes — never translucent
  glass/blur backdrops.
- **Expressive components over bespoke widgets:**
  - Use `FloatingToolbar` (horizontal/vertical) for floating map controls.
  - Use `SplitButton` and `ButtonGroup` / shape-morphing toggle buttons for
    primary actions (Record / Follow, Map Modes, route styling swatches).
  - Use Material 3 `SearchBar` / docked search in the sheet/panel.
  - Use wavy progress indicators / `LoadingIndicator` for downloads and routing.
  - Use **Material Symbols** vector drawables — never copied SF Symbols.
- **Edge-to-edge everywhere:** Call `enableEdgeToEdge()` in `MainActivity`.
  Map canvas draws behind system bars; interactive controls and sheet content
  consume `WindowInsets` (`safeDrawing` / `navigationBars` / `ime`) properly.
- **Typography & copy:** Use Material 3 Expressive type tokens with variable
  font scaling (verify up to `200%` font scale). Keep user-visible copy crisp
  (1–2 sentences), use typographic apostrophes (`’`), American English in
  `values/strings.xml`, proper `<plurals>` resources (never `if (count == 1)`),
  and locale-aware number/unit formatting (`NumberFormatter` / `MeasureFormat`).

---

## Concurrency & Compose performance

- **Structured concurrency only:** Never use `GlobalScope` or unstructured
  `CoroutineScope(...)` without explicit lifecycle ownership. Inject
  `CoroutineDispatcher` qualifiers (`@IoDispatcher`, `@DefaultDispatcher`,
  `@MainDispatcher`) and an application `@ApplicationScope` via Hilt so every
  coroutine is testable with `TestDispatcher`.
- **Main-safe repositories:** Repositories and data sources switch to
  `@IoDispatcher` / `@DefaultDispatcher` internally for disk, Room transactions,
  GPX parsing, PMTiles reads, and image decoding. Callers never need to guess
  which dispatcher a suspend function requires.
- **Compose recomposition isolation:**
  - Rely on Strong Skipping Mode by default, **but** never allocate fresh
    collections or `copy()` giant screen states on high-frequency ticks.
  - Pass state-reading lambdas (`progress: () -> Float`) or `State<T>` objects
    into leaf composables for animations, chart scrubbing, and live recording
    counters so parent layouts skip composition completely.
  - Defer scroll/drag/camera-driven transforms into `Modifier.offset { ... }`
    and `Modifier.graphicsLayer { ... }` (layout/draw phases).
  - Never compute sorted/filtered lists inline in composition without
    `remember(inputs)` or upstream Flow transformation.
  - Verify hot paths with Compose Compiler stability reports and Jetpack
    Macrobenchmark rather than speculative `@Immutable` annotations.

---

## Testing & test-oriented porting strategy

We use an **Acceptance-Test-Driven, vertical-slice workflow** inspired by iOS
OpenHikes's `OpenHikesUITests`, adapted to Android's faster multi-tier testing
pyramid.

### The three test tiers

1. **Tier 1 — Fast JVM Unit Tests (`src/test`, pure Kotlin / JUnit 5 or JUnit 4 + Truth/kotlin.test + Turbine):**
   - Covers `:core:model`, `:core:common`, repositories, Room/DAO logic (via
     Robolectric or JVM SQLite), ViewModels, `RecordingFixPolicy`,
     `AutoPauseWatch`, GPX codecs, PMTiles/manifest parsers.
   - Runs in seconds on every change and every CI job.
   - **Fakes over mocks:** Provide lightweight, reusable fakes in `:core:testing`
     (following `nowinandroid`), never brittle mock frameworks that assert call
     order instead of observable behavior.
2. **Tier 2 — Fast JVM Compose UI Scenario Tests (`src/test` + Robolectric + Hilt + `createAndroidComposeRule`):**
   - **This is where the majority of ported `OpenHikesUITests` use cases live.**
   - Drives the real Material 3 Expressive screens, real Nav3 back stack,
     in-memory Room DB, and deterministic fake map/location/offline-catalog
     seams through Compose's semantics tree — **without booting an emulator**.
   - Runs in seconds locally and on every PR in CI.
   - Pair with **Compose Screenshot Tests** (AGP 9.5+ native `screenshotTest`
     source set, or Roborazzi if multi-state interaction diffs are needed) across
     light/dark themes, compact phone + expanded tablet sizes, and large font
     scales.
3. **Tier 3 — Instrumented E2E & Accessibility Tests (`src/androidTest` on Gradle Managed Devices):**
   - Focused critical journeys that require a real Android OS runtime: real
     MapLibre rendering, real Foreground Service + location + Live Update
     notification lifecycle, predictive back & configuration/fold changes,
     document picker / system intents, and automated Accessibility Test
     Framework verification (`composeTestRule.enableAccessibilityChecks()`).
   - Executed reproducibly on **Gradle Managed Devices** (e.g., Pixel Phone +
     Pixel Tablet/Foldable virtual devices defined in `build-logic`) so local
     runs and CI never fight over shared ad-hoc emulators.

### Porting use cases from iOS `OpenHikesUITests`

- **One vertical slice at a time:** For each feature slice, write the Tier 2
  Compose scenario tests (and Tier 1 domain tests) *first* from the matching iOS
  use cases, then implement the Android architecture and UI until green. Do not
  pre-write dozens of failing tests for unbuilt slices.
- **Shared fixtures:** Reuse the exact bundled fixtures from iOS
  (`ThumseeLoopFast.gpx`, `ThumseeWalkWithStop.gpx`, `ThumseeRidgePath`,
  `ThumseeTwinPaths`, `ThumseeLoopTrails`, seeded offline country catalog) in
  `:core:testing` so both platforms assert against identical coordinates,
  distances, elevation profiles, and route-matching edge cases.
- **Shared semantic tag vocabulary:** Keep kebab-case `testTag` names aligned
  with iOS accessibility identifiers where the concept matches (`trail-map`,
  `map-sheet`, `map-search`, `clear-search-button`, `settings-button`,
  `settings-screen`, `import-gpx-button`, `hike-title-field`, `recording-phase`,
  `recording-point-count`, `offline-country-AT`, `offline-maps-search`, etc.)
  in a central `OpenHikesTestTags` object inside `:core:testing` (or `:core:ui`).
  Enable `Modifier.semantics { testTagsAsResourceId = true }` once at the app
  root so UiAutomator / Macrobenchmark / cross-platform tools can query the
  same tags.
- **Debug-only launch & scenario harness (`LaunchConfig`):** Mirror iOS's
  `AppLaunchEnvironment` for instrumented runs: accept scenario extras/flags
  (in-memory DB, isolated DataStore, pre-imported GPX fixture, seeded offline
  countries, forced save/delete failures, simulated offline mode) **compiled
  only in `debug` builds**. `release` builds return `LaunchConfig.Production`
  with zero test-hook code compiled in.
- **Port invariants, not iOS workarounds:** Never replicate tests that only
  guarded SwiftUI/UIKit bugs (e.g., ghost navigation-bar height offsets,
  `.fileImporter` attached to sheets, `isHittable` workarounds). Assert the
  underlying Android/Material 3 user outcome instead.

---

## Build, lint, and test commands

*(Target Gradle harness to be wired up in Phase 0. Every CI check maps directly
to one of these commands.)*

```sh
# Format check (Ktlint) and auto-format
./gradlew ktlintCheck
./gradlew ktlintFormat

# Static analysis: Detekt 2.0 (type-resolved) + Android Lint (incl. Slack Compose lints)
./gradlew detekt lintDebug

# Tier 1 + Tier 2: all pure JVM unit tests and Robolectric Compose UI tests
./gradlew testDebugUnitTest

# Narrow to a single module or test class
./gradlew :core:model:test
./gradlew :feature:hikes:testDebugUnitTest --tests "*.HikeLibraryScreenTest"

# Compose screenshot verification & baseline update
./gradlew validateDebugScreenshotTest
./gradlew updateDebugScreenshotTest

# Tier 3: Instrumented E2E & accessibility tests on Gradle Managed Devices
./gradlew pixelPhoneApi36DebugAndroidTest
./gradlew allDevicesDebugAndroidTest

# Full verification gate before opening a PR (fast local checks)
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest assembleDebug
```

### Static analysis standards

- **Ktlint:** Zero-config formatting gate; run before every commit/PR.
- **Detekt 2.0 (`dev.detekt`):** Enforces complexity ceilings, file/function
  length hygiene, coroutine anti-patterns, and no wildcard/unused imports.
- **Android Lint + `com.slack.lint.compose:compose-lint-checks`:** Enforces
  Compose state hoisting, modifier forwarding/reuse, slot rules, missing content
  descriptions, and Android API safety with `warningsAsErrors = true`.
- **No silent skips:** CI must verify test tasks actually executed tests and
  never silently matched zero classes.

---

## Architectural bootstrap roadmap

Build the harness in small, verifiable vertical slices rather than attempting
full iOS parity at once. *(Track concrete tasks in GitHub issues once created.)*

1. **Phase 0 — Harness & CI skeleton:** Gradle 9 + AGP 9.5+ root,
   `libs.versions.toml`, `build-logic` convention plugins, `:app`, `:core:model`,
   `:core:common`, `:core:designsystem`, `:core:testing`, Ktlint/Detekt/Android
   Lint + Compose lints, GitHub Actions CI running lint + JVM tests + debug APK
   build, and one passing smoke test.
2. **Phase 1 — Adaptive Map-First Shell & Settings (Slice 1):**
   `MaterialExpressiveTheme` (dynamic + nature fallback), Nav3 `NavBackStack` +
   adaptive bottom sheet / docked side panel (`sw >= 600dp`), `:core:map` seam
   with MapLibre Compose + fake test map, `LaunchConfig` debug harness,
   `:feature:settings` backed by Proto DataStore, and Tier 2 + Tier 3 UI/a11y
   tests matching iOS's launch, orientation/adaptivity, and settings use cases.
3. **Phase 2 — Hike Library, GPX Import & Detail (Slice 2):** Room database
   (portable vs. `HikeLocalState` split), GPX 1.1 parser/exporter with
   `RouteBoundary`, `RouteProfile`, `:feature:hikes` (list, search, detail,
   rename, delete with confirmation & stack cleanup, interactive elevation
   profile), and ported Tier 1/2/3 tests using `ThumseeLoopFast.gpx`.
4. **Phase 3 — Recording Engine & Live Updates (Slice 3):** Foreground Service
   (`location`), `RecordingFixPolicy`, crash-safe `TrackJournal`,
   `AutoPauseWatch`, Android 16+ `Notification.ProgressStyle` Live Updates,
   `:feature:recording` UI, and deterministic spoofed-track tests.
5. **Phase 4 — OpenHikes Vector Map & Offline Countries (Slice 4):** PMTiles
   reader + `tiles.openhikes.app` manifest integration in `:core:network` /
   `:core:map`, `:feature:offlinemaps` with `WorkManager` downloads, SHA-256
   verification, atomic country index, and seeded offline-map UI tests.

---

## Settled decisions — do not re-raise

Reopening any item below requires new measured evidence or an explicit owner
decision:

- **Native Kotlin + Jetpack Compose, not KMP/Flutter/React Native.** Android is
  a first-class native sibling to the Swift/SwiftUI iOS app so each platform
  delivers authentic UX (Material 3 Expressive vs. Apple HIG) without
  lowest-common-denominator abstractions.
- **Jetpack Navigation 3 (`Nav3`), never Navigation 2.** App-owned back stack
  is essential for syncing the map camera/selection with an adaptive sheet/side
  panel cleanly across rotations and folds.
- **MapLibre Compose behind `:core:map`, never Google Maps SDK as the primary
  renderer.** OpenHikes owns its offline PMTiles vector pipeline and Carto/outdoor
  styling; MapLibre renders PMTiles and offline countries natively without
  proprietary tile restrictions.
- **No Liquid Glass / frosted glass port.** Android uses Material 3 Expressive
  tonal surfaces, dynamic color, and spring motion.
- **Fakes over mocks in tests.** Keep a clean `:core:testing` module with real
  fake implementations of repositories, location feeds, clocks, and map hosts.
- **Preserve current schema during early development.** Until a public release
  must migrate live user installs, keep Room schemas clean and resettable in
  debug rather than carrying speculative migration boilerplate. Once the first
  public build ships, freeze exported Room schemas (`exportSchema = true`) and
  add tested migrations.

---

## Documentation ownership

Each fact lives in exactly one place:

| File | What it owns |
|---|---|
| `AGENTS.md` (this file) | Commands, architecture, module boundaries, conventions, testing rules, and settled decisions. |
| `README.md` | What OpenHikes for Android is, setup requirements, and high-level layout (no engineering rationale). |
| `CONTRIBUTING.md` | Short path to a first contribution; points here for rules. |
| KDoc headers on owning types | Why a subsystem is designed the way it is, measurements, and edge-case arguments. |
| GitHub Issues | Open work, bugs, and undecided product questions. |
