# AGENTS.md

Architectural harness, toolchain, conventions, testing gates, and settled
decisions for the Android port of **OpenHikes**. Read this file before creating
modules, writing code, or changing build configuration.

The sibling iOS repository (`/Users/zsomborrajki/Documents/OpenHikes`) is the
**product and domain reference** — not a code or UI template. We port OpenHikes’s
hiking domain, local-first privacy posture, data formats, and user-facing use
cases as a genuinely native Android app.

Supporting reference docs:
- [docs/architecture.md](docs/architecture.md) — full module tree, storage and
  backup layout, shared iOS archive formats, MapLibre/network rules, Live
  Updates details, and Material 3 Expressive component mapping.
- [docs/testing-and-roadmap.md](docs/testing-and-roadmap.md) — 3-tier test
  pyramid, porting `OpenHikesUITests` fixtures/tags, static analysis wiring, and
  Phase 0–4 bootstrap roadmap.

> **Repository status:** Phase 3 complete (`:core:location` recording engine, crash-safe
> `TrackJournal`, `RecordingFixPolicy`, `AutoPauseWatch`, thermal/battery energy policy,
> `RecordingForegroundService`, Android 16.0-safe Live Updates, `:feature:recording` UI,
> and deterministic Tier 1/2/3 + screenshot coverage). Phase 4 (OpenHikes Vector Map &
> Offline Countries) is next.

---

## Product north star

- **Google Maps’s hiking-oriented little brother.** Map-first, fast, calm, and
  legible outdoors. The live map is the permanent primary surface; hikes,
  recording, search, route details, and offline maps live in an adaptive sheet
  or side panel over/beside it.
- **Private and local-first, with no account.** Hikes live on the device and
  work with zero signal. Never introduce mandatory sign-in, telemetry, or a
  server holding a hiker’s private library. Keep the private hike library out of
  Android Auto Backup and exclude device-bound state from device-to-device
  transfers (see *Storage & backup policy*).
- **Foundations before feature parity.** Build clean module boundaries,
  deterministic test seams, adaptive navigation, offline map rendering, and
  rock-solid recording first; add features as tested vertical slices.
- **Native Material 3 Expressive — never iOS gloss.** Do not port Apple’s Liquid
  Glass, blur-heavy overlays, iOS navigation bars, or SF Symbols.

---

## Reference baselines

| Reference repository | What we adopt |
|---|---|
| [`android/nowinandroid`](https://github.com/android/nowinandroid) | Multi-module `:app` + `:core:*` + `:feature:*` graph, `build-logic` convention plugins, offline-first Repositories, UDF, Hilt DI, fakes-over-mocks testing. |
| [`android/androidify`](https://github.com/android/androidify) | **Material 3 Expressive** theming/motion and **Jetpack Navigation 3 (`Nav3`)** in a modern greenfield app. |
| [`android/nav3-recipes`](https://github.com/android/nav3-recipes) | App-owned `SnapshotStateList` back stack, DI-multibound multi-module entry providers, per-entry ViewModel scoping, adaptive scenes. |

---

## House rules

- **Stay inside the module graph.** `:feature:*` modules never depend on each
  other or `:app`. Shared state, contracts, and models flow through `:core:*`;
  `:app` is the sole composition root.
- **No legacy Android baggage.** Target modern Android only. Never add XML
  layouts, Fragments, `Navigation 2` (`NavController`/`NavHost`),
  `SharedPreferences`, KAPT, or pre-Android-15 compatibility shims.
- **No glassmorphism chrome.** Use Material 3 Expressive tonal surface
  containers, dynamic color, and spring motion (`MotionScheme.expressive()`).
- **Secrets & distributed APK hygiene.** Optional developer map keys (Stadia,
  Thunderforest) live in gitignored `local.properties` / `secrets.properties`
  injected via `BuildConfig` for **local builds only**. Never commit secrets,
  and never embed private or unrestricted provider credentials into distributed
  APKs — distributed builds may include only credentials the provider explicitly
  supports for public mobile clients with appropriate restrictions. A build with
  no keys must compile, run, and render the free OpenHikes map cleanly.
- **Recomposition isolation on hot paths.** High-frequency state (GPS fixes,
  recording timer ticks, chart scrub position, camera bounds) must be read only
  in leaf composables or deferred to layout/draw phases — never hoisted into
  root screen recompositions.
- **Deterministic test seams.** Tests never touch the network, real GPS, wall
  clock, or singletons. Every external dependency sits behind an injectable seam
  in `:core:*` with fakes in `:core:testing` (pure JVM) or
  `:core:testing-android` (Android/Compose).
- **No fixed sleeps as barriers.** Never use `Thread.sleep` or arbitrary
  `delay(...)` to wait for UI or async work. Use Compose clock/semantics
  synchronization, `waitUntil`, `TestDispatcher`, and Turbine.
- **One focused test class per file**, mirroring production packages/modules.
- **Accessibility is part of every screen’s contract.** Meaningful content
  descriptions, merged composite rows, `48dp` minimum touch targets, semantic
  `testTag`s on leaves (never containers), and `enableAccessibilityChecks()` on
  instrumented suites.
- **Port use cases, not iOS mechanics.** Preserve user goals and domain
  invariants from `OpenHikesUITests`, never SwiftUI/UIKit workarounds.
- **Each fact lives in one place.** Follow *Documentation ownership* below; open
  work lives in GitHub issues, never checked-in TODO lists.

---

## Platform floor & toolchain

Older Android API support is out of scope. Use modern stable and vetted
beta/experimental Jetpack APIs directly.

- **SDK levels:**
  - `compileSdk = 37` (Android 17)
  - `targetSdk = 37` (Android 17 — enforces adaptive resizability at `sw >= 600dp`
    with no opt-out)
  - `minSdk = 36` (Android 16 — edge-to-edge, predictive back,
    `Notification.ProgressStyle`, modern Photo Picker, zero legacy fallbacks).
    *If a physical test device requires Android 15, `minSdk` may be lowered to
    `35` in `libs.versions.toml` with a single wrapper around Live Updates; never
    drop below 35.*
- **Build system & runtime:**
  - **Gradle 9.6+** + **Android Gradle Plugin (AGP) 9.5+** (AGP 9.5.0 requires
    Gradle 9.6.0+; uses built-in Kotlin — **never** apply the legacy
    `org.jetbrains.kotlin.android` plugin; enables native AGP Compose screenshot
    test suites).
  - **JDK 21+** for local builds and CI (AGP’s floor is JDK 17, but
    Robolectric’s API 36 and 37 test runtimes require Java 21+).
  - **Version Catalog** (`gradle/libs.versions.toml`) as the single source of
    truth for versions and coordinates.
  - **`build-logic/` composite build** convention plugins:
    - `openhikes.android.application` / `openhikes.android.application.compose`
    - `openhikes.android.library` / `openhikes.android.library.compose`
    - `openhikes.android.feature` (Compose, M3E, Nav3, Hilt, Lifecycle, tests)
    - `openhikes.jvm.library` (pure Kotlin JVM — zero Android coupling)
    - `openhikes.hilt` (KSP-backed Hilt — never KAPT)
    - `openhikes.lint` (Ktlint + type-resolved Detekt 2.0 + Android/Compose lint)
- **Language & compiler:**
  - **Kotlin 2.3+** with `alias(libs.plugins.compose.compiler)`, Strong Skipping
    Mode enabled, and `allWarningsAsErrors = true` on all project modules.
  - Prefer explicit `@OptIn(...)` at the call site or owning wrapper
    (`ExperimentalMaterial3ExpressiveApi`, `ExperimentalMaterial3AdaptiveApi`,
    Nav3 APIs) over blanket module-wide opt-ins.
- **Core stack:** Jetpack Compose BOM, Material 3 `1.5+` (M3 Expressive),
  `material3-adaptive`, `activity-compose`, Jetpack Navigation 3
  (`navigation3-runtime`, `navigation3-ui`, `lifecycle-viewmodel-navigation3`,
  `adaptive-navigation3`), Hilt (KSP), Coroutines & Flow, `kotlinx-datetime` /
  `java.time`, Room (KSP), Proto DataStore, OkHttp + `kotlinx.serialization`,
  MapLibre Compose (`org.maplibre.compose`) behind `:core:map`,
  `ForegroundService` (`location`), `WorkManager`, and Android 16+ Live Updates.

---

## Architecture & enforceable contracts

### Module dependency rules

See [docs/architecture.md](docs/architecture.md#module-layout) for the full
directory tree. Do not create placeholder `:feature:*` modules ahead of need.

1. `:core:model`, `:core:common`, and `:core:testing` are **pure Kotlin JVM**
   modules (`openhikes.jvm.library`) with zero `android.*` / `androidx.*`
   imports. Domain rules (GPX codecs, `RouteProfile`, `RouteBoundary`,
   `RecordingFixPolicy`, `AutoPauseWatch`, manifest/PMTiles parsing) belong in
   `:core:model` / `:core:common`; shared JVM fakes (`TestClock`,
   `TestDispatchers`, bundled fixtures) belong in `:core:testing` so pure JVM
   modules can consume them as plain JARs.
2. `:core:testing-android` is an Android library depending on `:core:testing`
   and `:core:map` for Android/Compose test fakes (`TrailMapHost` fake,
   Robolectric/Compose helpers). Shared `OpenHikesTestTags` live in `:core:ui`.
3. `:core:data` depends on `:core:database`, `:core:datastore`, `:core:network`,
   `:core:model`, `:core:common`, exposing main-safe repository interfaces
   returning `Flow` and `suspend` functions.
4. `:feature:*` modules depend only on `:core:designsystem`, `:core:ui`,
   `:core:model`, `:core:data` (interfaces), `:core:location` (interfaces),
   `:core:map` (interfaces), and Nav3 runtime/UI — never on another `:feature:*`,
   Room DAOs, OkHttp, MapLibre SDK types, or `:app`.
5. `:app` is the sole composition root wiring Hilt bindings, the adaptive shell,
   and Nav3 `EntryProvider` multibindings.

### Storage & backup policy

- Set `android:allowBackup="false"` and
  `android:dataExtractionRules="@xml/data_extraction_rules"` in `AndroidManifest.xml`
  from Phase 0 (Android Auto Backup defaults to enabled, and on Android 12+
  `allowBackup="false"` alone still allows device-to-device transfers).
- Exclude all app databases, DataStore files, and storage from `<cloud-backup>`
  so private hikes are never uploaded automatically.
- Exclude device-bound state (`TrackJournal` drafts, `HikeLocalState`, offline
  country PMTiles archives, atomic country index) from `<device-transfer>` as
  well, storing non-transferable caches/archives in `noBackupFilesDir` or
  explicitly excluded paths — and keep device-local Room tables in a separate
  excluded DB file if portable data is ever opted into local device transfer.

### Unidirectional Data Flow (UDF)

- Split every screen into a route composable (`collectAsStateWithLifecycle()` +
  `ViewModel` event forwarding) and a stateless screen composable taking
  immutable state + lambdas (used directly by `@Preview`, screenshot tests, and
  component tests; never pass a `ViewModel` into a stateless screen).
- Expose immutable `StateFlow` UI state (`SharingStarted.WhileSubscribed(5_000)`),
  isolating high-frequency streams (GPS, recording clock, scrub index) in narrow
  state holders.
- Model one-off user-visible events (snackbars, prompts, navigation) as state or
  explicit consumed callbacks — never fire-and-forget `SharedFlow`.

### Navigation 3 & adaptive map-first shell

- **App-owned back stack:** Hold a `NavBackStack` (`SnapshotStateList<OpenHikesRoute>`)
  of `@Serializable` route keys via `rememberNavBackStack`, with per-entry
  `ViewModelStoreOwner` via `lifecycle-viewmodel-navigation3`.
- **Window-size-driven chrome:** Choose bottom sheet vs. docked side panel from
  the **current app window’s `WindowSizeClass`** (including split-screen and
  resizes), not a static `sw >= 600dp` cutoff:
  - **Compact width AND non-compact height (portrait phone / narrow split):**
    Persistent bottom sheet with distinct detents (peek / half / expanded) +
    floating Material 3 Expressive map controls.
  - **Medium/Expanded width OR compact height (landscape phone such as
    800×400dp, unfolded foldable, tablet):** Docked side panel beside the live
    map hosting the **exact same `NavDisplay` and back stack** so rotating,
    unfolding, or resizing preserves routes, ViewModels, and open dialogs.
- **Deepest screen claims the map:** A map-claim coordinator gives camera/overlay
  ownership to the top active back-stack entry, never “last composable entered”.
- **Predictive back & atomic delete:** Every pushed route, dialog, search bar,
  and expanded sheet handles predictive back cleanly. Deleting a hike clears map
  selection and strips all back-stack routes tied to that `hikeId` atomically.

### Core domain, map & recording invariants

Details live in [docs/architecture.md](docs/architecture.md). Enforce these
contracts across every slice:

- **Pause / segment boundaries (`RouteBoundary`):** Recording pauses and GPX
  `<trkseg>` breaks are never distance or trail; length, elevation, projection,
  and surface stats never bridge across a boundary.
- **Hike truth vs. device-local state:** Separate portable hike data (`Hike`,
  `HikeWalk`, `TrailPoint`) from `HikeLocalState` (walk progress, draft
  ownership, offline claims, local paths).
- **Bounded titles (grapheme-cluster safe):** Cap hike titles at **128 extended
  grapheme clusters** and **512 UTF-8 bytes**, truncating only on whole extended
  grapheme cluster boundaries (never UTF-16 `String.take(128)`, which cuts 4-byte
  emoji in half and can split flags or ZWJ sequences). Apply the exact same rule
  to typed titles and GPX track names, including filename fallbacks.
- **Recording outranks trail following** on notifications, Live Updates, and
  widgets.
- **Map isolation & attribution:** Keep all `org.maplibre.*` usage inside
  `:core:map` behind `TrailMapHost`; never do synchronous disk/network I/O on
  render threads; always render required OSM/WorldCover/Waymarked/provider
  attributions.
- **Foreground Service & Android 16.0-safe Live Updates:** Active recording and
  background following run in a `location` `ForegroundService` with an atomic
  crash-safe `TrackJournal`. Keep the standard ongoing foreground-service
  notification working on Android 16.0 (`36.0`): `Notification.ProgressStyle` is
  available at API `36.0`, whereas platform
  `Notification.Builder.setRequestPromotedOngoing()` was added in API `36.1`.
  Request Live Update promotion through
  `NotificationCompat.Builder.setRequestPromotedOngoing(true)` (or
  `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` / an explicit
  `Build.VERSION.SDK_INT_FULL` guard for 36.1+), never an unguarded platform
  call, and never custom `RemoteViews` or one-tap lock-screen Stop.

---

## Design system — Material 3 Expressive

- Wrap the app in `MaterialExpressiveTheme` (`material3` `1.5+`) with
  `MotionScheme.expressive()`, supporting light and dark themes from day one.
- Enable Dynamic Color with a high-contrast, outdoor-legible OpenHikes fallback
  palette (pinned in screenshot tests for determinism).
- Call `enableEdgeToEdge()` in `MainActivity`; draw the map behind system bars
  and consume `WindowInsets` (`safeDrawing` / `navigationBars` / `ime`) in
  chrome and sheets.
- Prefer expressive M3 components (`FloatingToolbar`, `SplitButton`,
  `ButtonGroup`, `SearchBar`, wavy progress indicators) and Material Symbols
  over bespoke widgets. Verify typography up to `200%` font scale; use crisp
  American English copy in `values/strings.xml`, typographic apostrophes (`’`),
  `<plurals>`, and locale-aware unit/number formatting.

---

## Concurrency & Compose performance

- **Structured concurrency only:** Never use `GlobalScope` or unowned
  `CoroutineScope(...)`. Inject `@IoDispatcher`, `@DefaultDispatcher`,
  `@MainDispatcher`, and `@ApplicationScope` via Hilt.
- **Main-safe repositories:** Switch to `@IoDispatcher` / `@DefaultDispatcher`
  inside repositories and data sources for disk, Room, GPX, PMTiles, and image
  work.
- **Hot-path Compose hygiene:** Never allocate fresh collections or `copy()`
  screen-wide state on high-frequency ticks; pass state-reading lambdas or
  narrow `State<T>` into leaf composables; defer motion/camera transforms to
  `Modifier.offset { ... }` / `Modifier.graphicsLayer { ... }`; cache derived
  lists with `remember(inputs)` or upstream `Flow`.

---

## Build, lint, and test commands

*(CI gates in `.github/workflows/ci.yml` map 1:1 to these commands. See
[docs/testing-and-roadmap.md](docs/testing-and-roadmap.md) for the 3-tier test
strategy and static analysis details.)*

```sh
# Format check (Ktlint) and auto-format
./gradlew ktlintCheck
./gradlew ktlintFormat

# Static analysis: type-resolved Detekt 2.0 aggregate (runs JVM detektMain/detektTest
# and Android variant detektDebug tasks; plain `detekt` skips type resolution) +
# Android/Slack Compose lint
./gradlew detektAll lintDebug

# Tier 1 + Tier 2: pure JVM unit tests (`test`) + Android/Robolectric tests (`testDebugUnitTest`)
./gradlew test testDebugUnitTest

# Narrow to a single module or test class
./gradlew :core:model:test
./gradlew :feature:hikes:testDebugUnitTest --tests "*.HikeLibraryScreenTest"

# AGP 9.5+ native Compose screenshot test suite (suite: screenshotTest, target: default, variant: debug)
./gradlew testDebugScreenshotTestDefaultTestSuite
./gradlew updateDebugScreenshotTestDefaultTestSuite

# Tier 3: Instrumented E2E & accessibility tests on Gradle Managed Devices
./gradlew pixelPhoneApi36DebugAndroidTest
./gradlew allDevicesDebugAndroidTest

# Full local & CI verification gate before opening a PR
./gradlew ktlintCheck detektAll lintDebug test testDebugUnitTest testDebugScreenshotTestDefaultTestSuite assembleDebug assembleDebugAndroidTest
```

---

## Settled decisions — do not re-raise

Reopening any item below requires new measured evidence or an explicit owner
decision:

- **Native Kotlin + Jetpack Compose, not KMP/Flutter/React Native.** Authentic
  Android UX (Material 3 Expressive) as a native sibling to the Swift/SwiftUI
  iOS app.
- **Jetpack Navigation 3 (`Nav3`), never Navigation 2.** App-owned back stack
  keeps map camera/selection synchronized cleanly across rotations, folds, and
  window resizes.
- **MapLibre Compose behind `:core:map`, never Google Maps SDK as primary
  renderer.** Renders OpenHikes’s PMTiles vector pipeline and offline countries
  natively without proprietary tile restrictions.
- **No Liquid Glass / frosted glass port.** Material 3 Expressive tonal
  surfaces, dynamic color, and spring motion only.
- **Fakes over mocks in tests.** Keep clean `:core:testing` (pure JVM) and
  `:core:testing-android` (Android/Compose) modules with reusable fakes.
- **Clean schemas during pre-release development.** Keep Room schemas clean and
  resettable in debug until the first public release ships; then freeze exported
  schemas (`exportSchema = true`) and require tested migrations.

---

## Documentation ownership

Each fact lives in exactly one place:

| File | What it owns |
|---|---|
| `AGENTS.md` (this file) | Enforceable rules, toolchain floor, module boundaries, contracts, verification commands, and settled decisions. |
| `docs/architecture.md` | Annotated module layout, storage/backup rules detail, shared iOS archive formats, MapLibre/network/Live Updates mechanics, M3E component guide. |
| `docs/testing-and-roadmap.md` | 3-tier test pyramid details, iOS test-porting workflow (`OpenHikesUITests`, fixtures, tags, `LaunchConfig`), static analysis wiring, Phase 0–4 roadmap. |
| `README.md` | What OpenHikes for Android is, setup prerequisites, and high-level layout (no engineering rationale). |
| `CONTRIBUTING.md` | Short path to a first contribution; points here for rules. |
| KDoc headers on owning types | Why a subsystem is designed a given way, measurements, and edge-case arguments. |
| GitHub Issues | Open work, bugs, and undecided product questions. |
