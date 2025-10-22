# Performance Optimization Specification

## Feature Overview
**Feature Name**: Performance Optimization & Monitoring
**Priority**: P0 (Critical)
**Status**: Maintenance & Enhancement
**Target Version**: v1.0.0

### Summary
Comprehensive performance optimization covering rendering, prediction latency, memory management, and performance monitoring. Ensures CleverKeys maintains 60fps rendering and <100ms prediction latency.

### Motivation
Keyboard performance is critical to user experience. Any lag or stutter breaks the typing flow and frustrates users. CleverKeys must be one of the fastest, most responsive keyboards available.

---

## ⚠️ KNOWN ISSUES (From Historical Review)

### CRITICAL Issues

#### ✅ Issue #7: Hardware Acceleration Status - VERIFIED FIXED
**File:** `AndroidManifest.xml`
**Status**: ✅ VERIFIED (2025-10-21)
**Historical Problem**: `android:hardwareAccelerated="false"` was globally disabled
**Impact**: Would cause severe rendering performance degradation (10-20fps instead of 60fps)
**Verification Result**: **ALREADY ENABLED** ✅
- AndroidManifest.xml:3 - `<manifest android:hardwareAccelerated="true">`
- AndroidManifest.xml:17 - `<application android:hardwareAccelerated="true">`
- Comment on line 2 confirms: "Hardware acceleration enabled for better rendering performance"

**Action Required** (Testing):
- [x] Check AndroidManifest.xml for `android:hardwareAccelerated` attribute ✅
- [x] Verify it's set to `"true"` on both `<manifest>` and `<application>` tags ✅
- [ ] Test rendering performance with GPU profiling (validate it works)
- [ ] Verify ONNX model runs correctly with hardware acceleration
- [ ] Profile memory usage (hardware acceleration uses more VRAM)
- [ ] Document any compatibility issues discovered

**Expected Behavior**:
```xml
<manifest ...>
  <application android:hardwareAccelerated="true" ...>
    <!-- All activities should inherit true by default -->
  </application>
</manifest>
```

### HIGH PRIORITY Issues

#### ✅ Issue #12: Performance Monitoring Not Cleaned Up
**File:** `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:673`
**Status**: ⚠️ NEEDS VERIFICATION
**Original Problem**: `// TODO: Stop performance monitoring` in onDestroy
**Impact**: Possible memory leak if monitoring not stopped
**Action Required**:
- [ ] Review CleverKeysService.kt:673 (onDestroy method)
- [ ] Verify performance monitoring cleanup is implemented
- [ ] Check for coroutine cancellation
- [ ] Check for timer/handler cleanup
- [ ] Profile memory after repeated enable/disable cycles
- [ ] Test for leaks using LeakCanary or Android Profiler

**Cleanup Checklist**:
```kotlin
override fun onDestroy() {
    // Cancel all coroutines
    serviceScope.cancel()

    // Stop performance monitoring
    performanceMonitor?.stop()

    // Release resources
    neuralEngine?.release()

    super.onDestroy()
}
```

---

## Performance Requirements

### Target Metrics
1. **Rendering**: 60fps (16.67ms per frame) sustained
2. **Key Press Latency**: <50ms from touch to visual feedback
3. **Prediction Latency**: <100ms from swipe end to suggestions
4. **Memory Usage**: <150MB RAM for keyboard process
5. **APK Size**: <60MB (currently 49MB)
6. **Cold Start**: <500ms to first render
7. **Layout Switch**: <200ms transition time

### Performance Budget
| Operation | Target | Maximum | Current |
|-----------|--------|---------|---------|
| Frame render | 16ms | 33ms (30fps) | TBD |
| Key press | 30ms | 50ms | TBD |
| ONNX inference | 50ms | 100ms | TBD |
| Layout load | 50ms | 100ms | TBD |
| Theme switch | 100ms | 200ms | TBD |

---

## Technical Design

### Performance Monitoring System
```kotlin
class PerformanceMonitor {
    // Frame timing
    fun startFrameTiming()
    fun recordFrame(durationMs: Long)

    // Operation timing
    fun startOperation(name: String): TimingToken
    fun endOperation(token: TimingToken)

    // Memory tracking
    fun recordMemorySnapshot()

    // Reporting
    fun getPerformanceReport(): PerformanceReport
    fun exportMetrics(format: ExportFormat)
}
```

### Hardware Acceleration Strategy
1. **Enable globally** in AndroidManifest.xml
2. **Use hardware layers** for animated views
3. **Cache Paint objects** to reduce allocations
4. **Use Canvas.drawBitmap** for complex renderings
5. **Profile with GPU Overdraw** tool

### Memory Optimization
1. **Object pooling** for frequent allocations (Paint, Path, Rect)
2. **Bitmap caching** for key backgrounds
3. **WeakReference** for large objects
4. **LRU cache** for prediction results
5. **Regular GC profiling** to detect leaks

---

## TODO: Performance Tasks

### Phase 1: CRITICAL - Hardware Acceleration (P0)
**Duration**: 1 hour
**Tasks**:
- [ ] Verify AndroidManifest.xml hardware acceleration status
- [ ] Enable if disabled
- [ ] Test keyboard rendering with GPU profiling
- [ ] Verify ONNX compatibility
- [ ] Measure frame times before/after
- [ ] Document any issues discovered

**Success Criteria**: Sustained 60fps rendering in normal typing scenarios

### Phase 2: Performance Monitoring Cleanup (P1)
**Duration**: 1-2 hours
**Tasks**:
- [ ] Review CleverKeysService.onDestroy implementation
- [ ] Add performance monitor cleanup
- [ ] Add coroutine scope cancellation
- [ ] Test for memory leaks with Android Profiler
- [ ] Profile repeated enable/disable cycles
- [ ] Verify no lingering background tasks

**Success Criteria**: Zero memory leaks, all resources properly released

### Phase 3: Performance Profiling (P1)
**Duration**: 2-3 hours
**Tasks**:
- [ ] Profile rendering performance (Systrace/GPU profiling)
- [ ] Profile ONNX inference latency
- [ ] Profile memory usage patterns
- [ ] Identify performance bottlenecks
- [ ] Create performance baseline metrics
- [ ] Document performance characteristics

**Success Criteria**: All operations meet performance budget

### Phase 4: Optimization Implementation (P2)
**Duration**: 4-8 hours
**Tasks**:
- [ ] Implement object pooling for Paint/Path/Rect
- [ ] Implement bitmap caching for key backgrounds
- [ ] Optimize swipe trail rendering
- [ ] Optimize suggestion bar rendering
- [ ] Reduce allocations in hot paths
- [ ] Profile and verify improvements

**Success Criteria**: 20% improvement in memory allocations, sustained 60fps

---

## Performance Testing Strategy

### Rendering Tests
```kotlin
@Test
fun `rendering maintains 60fps during typing`() {
    // Simulate rapid typing
    // Measure frame times
    // Assert: 95th percentile < 16.67ms
}

@Test
fun `rendering maintains 60fps during swipe`() {
    // Simulate swipe gesture
    // Measure frame times
    // Assert: all frames < 16.67ms
}
```

### Memory Tests
```kotlin
@Test
fun `no memory leaks after 100 enable-disable cycles`() {
    repeat(100) {
        enableKeyboard()
        disableKeyboard()
    }
    // Force GC
    // Assert: heap size returns to baseline
}
```

### Latency Tests
```kotlin
@Test
fun `key press latency under 50ms`() {
    // Simulate key press
    // Measure touch → visual feedback time
    // Assert: latency < 50ms
}

@Test
fun `prediction latency under 100ms`() {
    // Simulate swipe
    // Measure swipe end → suggestions time
    // Assert: latency < 100ms
}
```

---

## Monitoring & Instrumentation

### Metrics to Track
1. **Frame Times**: Min, max, avg, p95, p99
2. **Key Latency**: Touch to feedback time
3. **Prediction Latency**: Swipe to suggestions time
4. **Memory Usage**: Heap size, native memory
5. **GC Events**: Frequency, duration
6. **ONNX Inference**: Model load time, inference time

### Debug Dashboard
Create in-app performance overlay showing:
- Current FPS
- Frame time histogram
- Memory usage graph
- ONNX inference time
- Key press latency

---

## Tools & Techniques

### Profiling Tools
- **Android Profiler**: CPU, memory, network profiling
- **Systrace**: System-level performance analysis
- **GPU Overdraw**: Rendering optimization
- **Layout Inspector**: View hierarchy optimization
- **LeakCanary**: Memory leak detection
- **Perfetto**: Advanced tracing

### Optimization Techniques
1. **View Rendering**: Hardware layers, bitmap caching
2. **Memory**: Object pooling, weak references, LRU caches
3. **Threading**: Coroutines, background inference
4. **I/O**: Async file operations, memory-mapped files
5. **Garbage Collection**: Reduce allocations in hot paths

---

## Success Metrics
- ✅ Hardware acceleration enabled and stable
- ✅ 60fps rendering maintained in 95% of scenarios
- ✅ Zero memory leaks detected
- ✅ All operations within performance budget
- ✅ Performance monitoring properly cleaned up
- ✅ Performance baseline documented

---

## Open Questions
1. Does ONNX model work correctly with hardware acceleration?
2. Are there specific devices with hardware acceleration issues?
3. What is acceptable memory overhead for hardware acceleration?
4. Should we add user-facing performance settings?

---

**Created**: 2025-10-21
**Last Updated**: 2025-10-21
**Owner**: CleverKeys Development Team
**Status**: Critical verification needed for hardware acceleration
**Priority**: P0 - Check AndroidManifest.xml IMMEDIATELY
