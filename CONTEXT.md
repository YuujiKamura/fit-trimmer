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
