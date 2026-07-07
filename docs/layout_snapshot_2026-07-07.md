# Layout Snapshot: 2026-07-07

This document records the current source layout as observed on 2026-07-07, plus a short reading of why it likely ended up this way and what a cleaner end state would look like.

## Current Shape

### Module split

- `shared-core`: shared domain logic for FIT parsing, MP4 parsing, HUD rendering, cache/state primitives, and encoder support.
- `composeApp`: desktop Compose UI, view-model state, Windows video playback integration, batch orchestration, and desktop-only utilities.
- `src/` + `index.html` + `hud_designer.html`: separate web-side assets and scripts, not part of the desktop Kotlin UI path.

### Package shape

- `shared-core` is comparatively regular: most Kotlin code lives under `fit/`, with smaller `mp4/` and `crc/` packages.
- `composeApp` is mixed:
  - top-level files such as `Main.kt`, `FitTrimmerMainContent.kt`, `HudEncodePipeline.kt`, `TimeOffset.kt`, `EncodePlan.kt`, `ControlPlane.kt`
  - subpackages `components/`, `utils/`, `viewmodel/`
  - a vendor-style package for `io.github.kdroidfilter.composemediaplayer.windows`

### Functional concentration

- `Main.kt` contains more than startup: GUI boot, CLI branch, update flow, batch orchestration, UI helpers, and several composable panels.
- `FitTrimmerMainContent.kt` is the main UI composition root and also contains multiple dialogs and control flows.
- `AppViewModel.kt` holds a large amount of screen state, persistence hooks, batch state, plate detection state, sync state, and derived properties.
- `TimeOffset.kt` is the dedicated time-alignment primitive, but it is consumed from several files rather than owning a full feature boundary.

## Why It Probably Grew This Way

This is an inference from the current code, not a historical claim.

- The app appears to have started with a few central files, then accumulated features without a major layout pass.
- Desktop UI work and domain work were probably optimized for speed of implementation, so shared state and feature-specific helpers were kept close to the entry points that needed them.
- The Windows-only playback stack and native encoder bindings likely created pressure to keep platform-specific code near the desktop module root.
- Some layout choices look driven by safety constraints: direct file I/O is restricted, so cache-related code was centralized into a small set of allowed helpers instead of being spread across packages.

## What Good Looks Like

The goal is not "more folders". The goal is stable ownership.

- `shared-core` stays the boundary for platform-neutral domain logic.
- `composeApp` is organized by feature, not by historical accident.
- One feature should have one obvious home for:
  - state
  - UI composition
  - persistence
  - background work
- `Main.kt` should shrink to orchestration only.
- `FitTrimmerMainContent.kt` should become a thin route/root composition file, not a catch-all for feature flows.
- `AppViewModel.kt` should split when it becomes the place people browse for unrelated behavior.

## Target Direction

If this were cleaned up gradually, the likely destination would be:

- `composeApp/src/desktopMain/kotlin/app/` for bootstrapping and app-level orchestration
- `composeApp/src/desktopMain/kotlin/feature/<feature-name>/` for feature-owned UI/state/helpers
- `composeApp/src/desktopMain/kotlin/platform/` for Windows-specific integration code
- `shared-core/src/commonMain/kotlin/fit/` remaining the canonical home for portable domain logic

## Practical Rule For Future Changes

- If logic is reusable outside the desktop UI, it belongs in `shared-core`.
- If logic is desktop-only but feature-owned, it should live beside that feature.
- If logic only wires the app together, it belongs in `Main.kt` or a small app bootstrap file.
- If a file becomes the place where unrelated changes land, it is a candidate for extraction.

## What This Snapshot Is For

This is a baseline for later refactoring work. It captures the current asymmetry so future changes can be judged against the actual starting point instead of memory.
