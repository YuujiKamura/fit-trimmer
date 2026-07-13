# 4. Lightweight Plate Tracking with Dynamic Frame Skipping

## Context
The current plate scan system uses `yolov8n.onnx` to detect vehicles and pedestrians, defining their bottom halves (or full bodies) as blur zones. While effective, the active tracking phase triggers neural network inference on every single decoded video frame, inducing high CPU/GPU resource consumption. To optimize this, we aim to reduce ONNX inference frequency by decimation (e.g., executing ONNX inference once every $N=10$ frames). 

However, running inference intermittently introduces two primary issues:
1. **Intra-frame Trajectory Drift**: Vehicles can move significantly over 10 frames (approx. 1.5 to 3 seconds at typical scan frame rates), causing static bounding boxes to lag behind.
2. **Frame-In Exposure (Leakage)**: A new vehicle entering the frame during the skipped interval remains undetected until the next ONNX run, leading to raw license plates being exposed for up to 3 seconds.

## Decision
We will resolve these issues by implementing a hybrid tracker that couples decimation with lightweight temporal tracking and backtracking:

1. **Track Lifecycle & Dynamic Decimation**
   * Keep a list of active `Track` objects. Each track maintains an ID, bounding box history, movement velocity (for Kalman/motion filtering), and an HSV color histogram of the detected object's visual patch.
   * Restrict full ONNX inference to a configurable interval (e.g., every 10th frame). 

2. **Forward Tracking via HSV Histograms and Motion Prediction**
   * On intermediate (skipped) frames, predict the bounding box's new location using a linear motion model (Kalman filter style prediction step).
   * Define a local search window around the predicted coordinates. Extract the HSV color histogram of the candidate patch and locate the best match by minimizing the Bhattacharyya distance relative to the track's reference histogram.
   * Associate new detections with existing tracks using a distance-gated greedy match based on spatial proximity and color similarity.

3. **Retroactive Backtracking (Bidirectional Interpolation)**
   * During an ONNX inference frame, if a vehicle is detected that does not match any existing active track (a new frame-in), construct its past trajectory.
   * Linearly interpolate the bounding box backward from its current detection coordinates to the nearest screen border over the preceding $N-1$ un-inferred frames.
   * Insert these interpolated bounding boxes into the past frame records to cover the frame-in exposure window, ensuring no raw license plates are shown.

## Consequences
* **Pros**:
  * **Massive Performance Boost**: Reduces inference workload by up to 90%, freeing up significant computing capacity for concurrent UI tasks or reducing overall encoding times.
  * **Low Computational Overhead**: HSV histogram extraction and comparison are performed purely via fast pixel-level CPU routines, which require negligible resources compared to ONNX execution.
  * **Leakage-Free Execution**: Exploits the batch-processing nature of video files, retroactively masking newly appeared vehicles to secure complete privacy compliance.
* **Cons**:
  * **Drift under Extreme Shocks**: Rapid vehicle camera rotation, sudden light fluctuations, or occlusions might momentarily disrupt the HSV matching, causing the blur bounding box to shift slightly (mitigated by vehicle-level padding and bounding box margins).
