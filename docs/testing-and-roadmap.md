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
     `debug` variant) executed via `./gradlew testScreenshotTestDefaultDebugTestSuite`
     and refreshed via `./gradlew updateScreenshotTestDefaultDebugTestSuite`
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
     `ktlintCheck detektAll lintDebug test testDebugUnitTest assembleDebug`, and
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
