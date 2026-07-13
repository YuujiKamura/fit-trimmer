# Context: Fit Trimmer Domain Glossary

This document outlines the canonical vocabulary and concepts governing the state relation, telemetry parsing, and video encoding process of Fit Trimmer.

## Vocabulary

### High-Frequency Event
Any asynchronous background message or computation payload (such as processed video frames, elapsed progress ratios, or console streaming logs) emitted dozens of times per second.

### State Reflection
The mechanism of mapping background business logic outcomes or execution metrics into user-visible GUI State elements.

### State Throttling (Throttling)
A temporal constraint applied to State Reflection to filter high-frequency events, capping update dispatches to a rate matching GUI redrawing thresholds (e.g., max 10 to 30 dispatches per second) to prevent resource saturation.

### Deferred State Read
An optimization technique in the UI rendering tree where Compose State references are deferred by wrapping them in lambda calls (`() -> T`) instead of raw values, confining recomposition to the target leaf widgets and shielding parent layouts.

### Plate Scan (Plate Detection)
The process of using object detection models (e.g., YOLO) to find vehicles or pedestrians in video frames for the purpose of identifying regions containing license plates or individuals to blur.

### Track
A stateful representation of an active, identified object (such as a vehicle or pedestrian) across sequential video frames, storing its trajectory, estimated velocity, visual appearance features (like HSV color histograms), and lifespan statistics.

### Dynamic Frame Skipping (Inference Decimation)
A performance optimization technique that runs full neural network inference (ONNX) only once every $N$ frames (e.g., $N=10$) and delegates the intermediate frames to lightweight tracking algorithms.

### Backtracking (Bidirectional Interpolation)
A post-processing recovery mechanism that, when a new Track is detected at frame $T$, retroactively propagates its bounding box trajectory backward through the preceding $N-1$ un-inferred frames to the screen border, preventing raw license plate exposure during frame-in intervals.

