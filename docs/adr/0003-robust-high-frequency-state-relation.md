# 3. Robust High-Frequency State Relation

## Context
During operations like license plate scanning, telemetry parsing, and custom HUD video encoding, background pipelines produce updates at extremely high rates (e.g., on every video frame). Writing these updates raw into Jetpack Compose `mutableStateOf` states on every invocation triggers excessive recompositions ("recomposition storms"). This saturates the Main/UI thread message loop, causes GUI freezes, runs up massive garbage collections (GC), and eventually triggers application crashes or OutOfMemory errors.

## Decision
We will enforce a robust state relation system using two complementary techniques:

1. **State Throttling via Kotlin Flow**
   * Background callbacks will write progress metrics and frame updates into a `MutableSharedFlow`.
   * These flows will be throttled using operators like `.conflate()` or `.sample(100.milliseconds)` on the ViewModel level before switching to `Dispatchers.Main.immediate` to update Compose States.

2. **Deferred State Reads in GUI Layer**
   * UI components receiving high-frequency updates will accept lambda providers (e.g., `progressProvider: () -> Float`) instead of naked variables.
   * This isolates composition scopes, limiting recomposition to the smallest possible leaf widgets and skipping parent tree recalculations.

## Consequences
* **Pros**: Prevents UI thread saturation, eliminates recomposition-induced lag and OutOfMemory crashes, and bounds GUI performance regardless of background process throughput.
* **Cons**: Slight rendering latency of progress states up to the throttling interval (typically 50-100ms), which is visually imperceptible to users.
