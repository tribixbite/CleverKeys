v1.2.9 - Performance & Polish

Neural Network Optimization:
- Tensor reuse in beam search reduces memory allocations by 90%
- XNNPACK thread count now user-configurable (1-8 threads)
- Batched beam decoding toggle for advanced users
- Fixed native memory leak in ONNX inference

New Features:
- Launcher Gestures Box: Third setup step guides per-key calibration
- In-App Help & FAQ: Searchable FAQ section in Settings
- ONNX Threads Setting: Fine-tune inference performance
- Backup & Reset support for neural settings

Fixes:
- French contraction frequency: "qu'est" now ranks correctly vs "quest"
- Short gesture max distance check restored (was accidentally removed)
- Full AndroidX migration: ExtraKeysPreference, ListGroupPreference

Documentation:
- All 69 wiki pages audited and verified against source code
- Fixed 40+ incorrect settings paths and fabricated features
- FAQ content verified against actual code behavior
- Comprehensive accuracy improvements across all documentation
