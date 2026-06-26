# Design Decisions: IMU-Telemetry Auto Alignment

This document outlines the technical rationale, physical constraints, and design decisions made for aligning video timeline timestamps with GPS telemetry (`.fit` files) via IMU sensor correlation in the `fit-trimmer` application.

## 1. Background
Initial versions attempted to correlate continuous vibration intensities or raw G-forces directly with GPS telemetry speed. This resulted in incorrect alignment offsets (e.g., matching a +47s offset instead of the actual -10s offset). 

To resolve this, we analyzed two distinct correlation approaches: **Binary (state-based) Correlation** and **Acceleration (derivative-based) Correlation**.

---

## 2. Approach 1: Binary State-Based Correlation
Instead of matching raw vibration magnitudes to speeds, both signals are converted into a binarized representation of "Moving" (1.0) and "Stopped" (0.0).

* **GPS Telemetry**: `speedKmh > 2.0` (Moving) vs. `speedKmh <= 2.0` (Stopped).
* **Video Vibration**: Binarized via a dynamic threshold calculated from the 10th percentile of the smoothed vibration profile:
  $$\text{threshold} = \max(20.0, P_{10} \times 1.5)$$
  If the smoothed vibration intensity exceeds this threshold, it is marked as `1.0` (Moving), otherwise `0.0` (Stopped).

### Why It Works (The Anchor Effect)
During a ride, stopping events (e.g., waiting at a traffic light) create a perfect alignment signature: **GPS speed drops to absolute zero, and video vibration falls to near-zero (engine/idle noise only)**. Matching these zero-flatlines locks the temporal alignment in place, yielding precise sync outputs (offset ~ -10.0s) with extremely high correlation scores.

---

## 3. Approach 2: Acceleration (Derivative) Correlation
This approach attempts to correlate the rate of change of speed (acceleration) with the changes in IMU forces.

* **GPS Telemetry**: Absolute difference of the speed grid: $\text{acc}_{\text{fit}} = \left| \frac{d(\text{speed})}{dt} \right|$.
* **Video IMU**: Absolute difference of the smoothed vibration intensity profile.

### Physical Limitations and Why It Fails with Vibration
1. **Mismatched Physical Properties**: 
   GPS acceleration represents macro forward/backward G-force. Video vibration, on the other hand, represents high-frequency micro-jolts caused by road surface roughness. A bike accelerating on a smooth asphalt road may experience high forward G-force but *low* vibration, while a bike rolling at a constant speed on rough gravel experiences zero forward G-force but *extreme* vibration.
2. **High-Frequency Noise**: 
   Derivatives (differentiation) magnify high-frequency noise. Any bump or suspension bounce creates a massive spike in the vibration derivative, leading the correlation algorithm to find false alignment peaks (e.g., slipping to a +128s offset instead of -10s).
3. **The "Neck Rotation" Problem (Body/Helmet Mounts)**: 
   If the camera is mounted on the rider's helmet, any neck rotation (e.g., checking traffic at junctions) rotates the camera's frame of reference. The forward acceleration vector shifts into the lateral camera axis. Mid-ride head movement breaks pure acceleration tracking.

---

## 4. Camera Inversion and Horizon Lock (Physical Robustness)
When mounting cameras upside-down (e.g., hanging under a handlebar or outfront mount), the raw IMU values are recorded relative to the physical camera body (causing Z-axis or Y-axis inversion). While video players apply Horizon Lock to rotate the visual frame to keep the ground at the bottom, the raw IMU file data remains inverted.

### The Scalar Magnitude Solution
To ensure the system works reliably regardless of camera mounting angle, rotation, or inversion, we compute the **vector norm (scalar magnitude)** of the raw 3-axis accelerometer values ($A_x, A_y, A_z$):
$$\text{norm} = \sqrt{A_x^2 + A_y^2 + A_z^2}$$
Since negative values (inverted G-forces) become positive when squared, the resulting scalar magnitude remains **identical** whether the camera is mounted right-side up, upside-down, or tilted. This enables the binary state-based algorithm to perform flawlessly under any mounting condition without user calibration.

---

## 5. Architectural Implementation
To support portability, speed, and prevent external dependency failures in standalone builds (where local Python or `numpy`/`scipy` installations cannot be assumed), the alignment system has been ported **100% natively to Kotlin (JVM)** in `TelemetryAligner.kt`.

### Data Preservation Strategy
While we currently use the scalar vibration norm for robust binary state matching, the native parser **fully extracts and preserves the raw 3-axis accelerometer and gyroscope data** in memory:
```kotlin
data class ImuData(
    val times: DoubleArray,
    val accX: DoubleArray,
    val accY: DoubleArray,
    val accZ: DoubleArray,
    val gyroX: DoubleArray,
    val gyroY: DoubleArray,
    val gyroZ: DoubleArray
)
```
This ensures that if we decide to implement gravity-vector subtraction (using gyro orientation to extract pure forward/backward acceleration) in the future, the data foundation is already fully in place.
