# Performance Optimization

> **Status (2026-10-23):** targets and strategies only. There is no `PerformanceMonitor` class;
> `PerformanceProfiler` (an `object` over `android.os.Trace`, statistics off by default) has no
> production call sites at HEAD. Cleanup is `CleanupHandler`, not a hand-written `onDestroy`.
> Measured evidence lives in `docs/eval/` and `docs/audit/2026-09-10-memory-oom-root-cause.md`.

## Overview

Performance optimization system covering rendering, prediction latency, memory management, and monitoring. Target metrics: 60fps rendering, <100ms prediction latency, <150MB RAM.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `onCreate()`, `onDestroy()` | Lifecycle and cleanup |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | `onDraw()` | Rendering |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt` | Swipe decoding | ONNX performance |
| `AndroidManifest.xml` | `hardwareAccelerated` | GPU rendering |

## Performance Budget

| Operation | Target | Maximum |
|-----------|--------|---------|
| Frame render | 16ms | 33ms (30fps) |
| Key press latency | 30ms | 50ms |
| ONNX inference | 50ms | 100ms |
| Layout load | 50ms | 100ms |
| Theme switch | 100ms | 200ms |

## Implementation Details

### Hardware Acceleration

Enabled in AndroidManifest.xml:

```xml
<manifest android:hardwareAccelerated="true">
    <application android:hardwareAccelerated="true">
        <!-- All activities inherit true -->
    </application>
</manifest>
```

### Performance monitoring (as built)

- `PerformanceProfiler` (`PerformanceProfiler.kt`, `object`): wraps `android.os.Trace` sections
  so Perfetto / Android Studio Profiler can see them; the legacy statistics path is gated by an
  `ENABLE_STATISTICS` flag that is off by default. **No production code calls it at HEAD**
  (`rg PerformanceProfiler src/main` finds only the file), so it is a developer utility, not a
  monitoring system. There is no `PerformanceMonitor`, `TimingToken` or `PerformanceReport`.
- Swipe decode timing is recorded by `SwipePerformanceStats` (singleton) for the debug
  activity; heap-pressure work is documented in `docs/audit/2026-09-10-memory-oom-root-cause.md`.

### Resource cleanup (as built: `CleanupHandler`)

`CleverKeysService.onDestroy` unregisters its receivers and then delegates the manager
teardown (`CleverKeysService.kt:535-544`, extracted in v1.32.404):

```kotlin
CleanupHandler.create(
    this, _configManager, _clipboardManager, _predictionCoordinator, _debugLoggingManager,
    if (::_inputCoordinator.isInitialized) _inputCoordinator else null,
    if (::_suggestionHandler.isInitialized) _suggestionHandler else null
).cleanup()
DirectBootManager.getInstance(this).cleanup()
```

`CleanupHandler.kt` owns the order (config listener, clipboard, prediction coordinator and its
engines, debug logging, coordinator/handler scopes). `performanceProfiler?.cleanup()`,
`serviceScope.cancel()` and `ctcAdapter?.release()` as written in the old sketch do not exist
in the service.

### Memory Optimization Strategies

| Strategy | Implementation |
|----------|----------------|
| Object pooling | Reuse Paint, Path, Rect in hot paths |
| Bitmap caching | Cache key background bitmaps |
| WeakReference | Large objects not critical to hold |
| LRU cache | Prediction results; geometric template indices (`swipe/geometric/TemplateCache.kt`) |

### Rendering Optimization

1. **Hardware layers** for animated views
2. **Cache Paint objects** to reduce allocations
3. **Use Canvas.drawBitmap** for complex renderings
4. **Avoid overdraw** with opaque backgrounds

### ONNX Inference Optimization

`OptimizedTensorPool` (pre-allocated tensor buffers for the transformer) was deleted with
the neural engine on 2026-08-18 (ADR-011). The CTC path does not pool tensors: the single
encoder call per swipe wraps freshly-built feature arrays
(`OnnxTensor.createTensor(env, FloatBuffer.wrap(features), ...)` in
`swipe/OnnxCtcEmissionModel.kt`), and the whole decode runs off-main on
`PredictionTaskRunner`'s single decode thread, so per-swipe allocation is not on a hot
render path. Session-level tuning (XNNPACK-first providers, `onnx_xnnpack_threads`) lives
in `onnx/ModelLoader.kt`.

### Metrics to Track

| Category | Metrics |
|----------|---------|
| Frame Times | Min, max, avg, p95, p99 |
| Key Latency | Touch to feedback time |
| Prediction Latency | Swipe to suggestions time |
| Memory Usage | Heap size, native memory |
| GC Events | Frequency, duration |
| ONNX Inference | Model load time, inference time |

### Profiling Tools

| Tool | Purpose |
|------|---------|
| Android Profiler | CPU, memory, network |
| Systrace | System-level analysis |
| GPU Overdraw | Rendering optimization |
| Layout Inspector | View hierarchy |
| LeakCanary | Memory leak detection |
| Perfetto | Advanced tracing |
