# CleverKeys Development Guide

## 🛠️ **DEVELOPMENT WORKFLOW**

### **Project Structure**
```
src/main/kotlin/juloo/keyboard2/
├── core/                    # Core keyboard components
│   ├── CleverKeysService.kt          # Main InputMethodService
│   ├── Keyboard2View.kt              # Keyboard rendering
│   ├── KeyEventHandler.kt            # Input processing
│   └── InputConnectionManager.kt     # Text input integration
├── neural/                  # Neural prediction system
│   ├── NeuralSwipeEngine.kt          # High-level neural API
│   ├── OnnxSwipePredictorImpl.kt     # ONNX implementation
│   ├── NeuralPredictionPipeline.kt   # Complete pipeline
│   └── TensorMemoryManager.kt        # Memory optimization
├── gesture/                 # Gesture recognition
│   ├── SwipeGestureRecognizer.kt     # Pattern recognition
│   ├── SwipeDetector.kt              # Gesture classification
│   ├── EnhancedSwipeGestureRecognizer.kt # Real-time recognition
│   └── AdvancedTemplateMatching.kt   # Template algorithms
├── data/                    # Data models
│   ├── SwipeInput.kt                 # Gesture data
│   ├── PredictionResult.kt           # Prediction results
│   ├── KeyValue.kt                   # Key representation
│   └── KeyboardData.kt               # Layout data
├── config/                  # Configuration system
│   ├── Config.kt                     # Global configuration
│   ├── NeuralConfig.kt               # Neural settings
│   └── ConfigurationManager.kt       # Reactive config
├── ui/                      # User interface
│   ├── SwipeCalibrationActivity.kt   # Neural calibration
│   ├── SettingsActivity.kt           # Settings interface
│   ├── LauncherActivity.kt           # Setup and navigation
│   └── CustomLayoutEditor.kt         # Layout customization
├── utils/                   # Utilities and helpers
│   ├── Extensions.kt                 # Kotlin extensions
│   ├── Utils.kt                      # Common utilities
│   ├── ErrorHandling.kt              # Error management
│   └── Logs.kt                       # Logging system
└── ml/                      # Machine learning
    ├── SwipeMLData.kt                # Training data
    └── SwipeMLDataStore.kt           # Data persistence
```

## 🏗️ **BUILDING**

### **Requirements**
- Android Studio Arctic Fox or newer
- Kotlin 1.9.20+
- Android SDK 35
- Gradle 8.7+
- Python 3.8+ (for resource generation)

### **Build Commands**
```bash
# Debug build
./gradlew assembleDebug

# Release build  
./gradlew assembleRelease

# Run tests
./gradlew test

# Run benchmarks
./gradlew benchmarkDebug

# Generate documentation
./gradlew dokkaHtml
```

### **Build Variants**
- **debug**: Development with full logging
- **release**: Optimized production build
- **benchmark**: Performance testing build

## 🧪 **TESTING**

### **Test Categories**

#### **Unit Tests**
```bash
./gradlew testDebugUnitTest
```
- Individual component testing
- Data model validation
- Algorithm verification
- Configuration management

#### **Integration Tests**
```bash
./gradlew connectedDebugAndroidTest
```
- Component interaction testing
- Pipeline integration validation
- Neural prediction accuracy
- Gesture recognition performance

#### **System Tests**
```kotlin
val tester = SystemIntegrationTester(context)
val results = tester.runCompleteSystemTest()
```
- End-to-end functionality
- Performance benchmarking
- Memory management validation
- Error handling resilience

### **Performance Testing**
```kotlin
val benchmark = BenchmarkSuite(context)
val results = benchmark.runBenchmarkSuite()
val report = benchmark.generateBenchmarkReport(results)
```

### **Quality Metrics**
- **Code Coverage**: Target >80%
- **Performance**: <200ms prediction latency
- **Memory**: <100MB peak usage
- **Accuracy**: >80% neural prediction accuracy
- **Reliability**: >99% uptime without crashes

## 🔍 **DEBUGGING**

### **Enable Debug Logging**
```kotlin
// In Application.onCreate()
if (BuildConfig.DEBUG) {
    Logs.setDebugEnabled(true)
    Logs.setVerboseEnabled(true)
}
```

### **Performance Profiling**
```kotlin
val profiler = PerformanceProfiler(context)
profiler.startMonitoring { metric ->
    Log.d("Performance", "${metric.operation}: ${metric.durationMs}ms")
}
```

### **Memory Analysis**
```kotlin
val memoryManager = TensorMemoryManager(ortEnvironment)
val stats = memoryManager.getMemoryStats()
Log.d("Memory", "Active tensors: ${stats.activeTensors}")
```

### **Neural Prediction Debugging**
```kotlin
val neuralEngine = NeuralSwipeEngine(context, config)
neuralEngine.setDebugLogger { message ->
    Log.d("Neural", message)
}
```

## 🔧 **COMMON TASKS**

### **Adding New Gesture Patterns**
```kotlin
// 1. Extend GestureType enum
enum class GestureType {
    // ... existing types
    NEW_PATTERN
}

// 2. Add recognition logic
private fun classifyGestureType(points: List<PointF>): GestureType {
    // ... existing logic
    if (isNewPattern(points)) return GestureType.NEW_PATTERN
}

// 3. Add template matching
private fun isNewPattern(points: List<PointF>): Boolean {
    // Pattern detection logic
}
```

### **Adding New Configuration Options**
```kotlin
// 1. Add to NeuralConfig
class NeuralConfig(prefs: SharedPreferences) {
    var newSetting: Int by IntPreference("new_setting", defaultValue)
}

// 2. Add validation
fun validateNeuralConfig(config: NeuralConfig): ValidationResult {
    if (config.newSetting !in validRange) {
        errors.add("Invalid new setting")
    }
}

// 3. Handle configuration changes
private fun handleConfigurationChange(change: ConfigChange) {
    when (change.key) {
        "new_setting" -> updateComponentWithNewSetting()
    }
}
```

### **Adding New UI Components**
```kotlin
// 1. Create Kotlin class with modern patterns
class NewUIComponent(context: Context) : View(context) {
    private val scope = context.uiScope
    
    // Use scope functions for setup
    init {
        setupUI()
    }
    
    private fun setupUI() = apply {
        // Clean UI setup with Kotlin DSL
    }
}

// 2. Integrate with reactive updates
scope.launch {
    configFlow.collect { config ->
        updateFromConfig(config)
    }
}
```

## 📊 **MONITORING**

### **Performance Metrics**
```kotlin
// Monitor critical operations
val metrics = mapOf(
    "neural_prediction_latency" to targetUnder(200), // ms
    "gesture_recognition_latency" to targetUnder(50), // ms
    "memory_allocation_rate" to targetUnder(10), // MB/sec
    "battery_drain_rate" to targetUnder(5) // % per hour
)
```

### **Error Tracking**
```kotlin
// Track error categories
val errorCategories = listOf(
    ErrorHandling.CleverKeysException.NeuralEngineException::class,
    ErrorHandling.CleverKeysException.GestureRecognitionException::class,
    ErrorHandling.CleverKeysException.ConfigurationException::class
)
```

### **Quality Assurance**
```kotlin
// Automated quality checks
val qualityMetrics = QualityMetrics(
    codeComplexity = measureCyclomaticComplexity(),
    testCoverage = measureTestCoverage(),
    nullSafety = validateNullSafety(),
    performanceRegression = detectPerformanceRegression()
)
```

## 🚀 **DEPLOYMENT**

### **Pre-Deployment Checklist**
- [ ] All tests passing (unit, integration, system)
- [ ] Performance benchmarks within targets
- [ ] Memory leak testing completed  
- [ ] Error handling validation passed
- [ ] Accessibility testing verified
- [ ] Configuration migration tested
- [ ] ProGuard optimization validated
- [ ] APK size optimization completed

### **Release Process**
1. **Code freeze** and final testing
2. **Performance validation** with benchmark suite
3. **Security review** of ProGuard configuration
4. **Documentation update** with new features
5. **Release build generation** with optimization
6. **APK validation** with RuntimeValidator
7. **Deployment preparation** with rollback plan

### **Post-Release Monitoring**
- Performance metrics collection
- Error rate analysis
- User feedback integration
- Neural prediction accuracy tracking
- Memory usage optimization

## 🔄 **CONTINUOUS INTEGRATION**

### **Automated Testing Pipeline**
```yaml
# GitHub Actions / CI Pipeline
steps:
  - name: Unit Tests
    run: ./gradlew testDebugUnitTest
    
  - name: Integration Tests  
    run: ./gradlew connectedDebugAndroidTest
    
  - name: Performance Benchmarks
    run: ./gradlew benchmarkDebug
    
  - name: Code Quality Analysis
    run: ./gradlew ktlintCheck detekt
    
  - name: Security Scan
    run: ./gradlew dependencyCheckAnalyze
```

### **Quality Gates**
- **Test Coverage**: >80% required
- **Performance**: No >20% regression
- **Memory**: No memory leaks detected
- **Security**: No high-severity vulnerabilities
- **Code Quality**: Kotlin style compliance

## 📖 **BEST PRACTICES**

### **Kotlin Patterns**
```kotlin
// Use data classes for immutable data
data class GestureData(val points: List<PointF>, val timing: List<Long>)

// Use sealed classes for type safety
sealed class PredictionSource {
    object Neural : PredictionSource()
    object Traditional : PredictionSource()
    data class Hybrid(val weights: Map<String, Float>) : PredictionSource()
}

// Use coroutines for async operations
suspend fun processGesture(input: SwipeInput): PredictionResult = withContext(Dispatchers.Default) {
    // Async processing
}

// Use extension functions for domain operations
fun List<PointF>.pathLength(): Float = zipWithNext { p1, p2 -> p1.distanceTo(p2) }.sum()
```

### **Error Handling**
```kotlin
// Use Result type for error-prone operations
suspend fun safeOperation(): Result<PredictionResult> = try {
    Result.success(performOperation())
} catch (e: Exception) {
    Result.failure(e)
}

// Use structured concurrency
class Component(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    fun cleanup() = scope.cancel() // Automatic cleanup
}
```

### **Performance Optimization**
```kotlin
// Use lazy initialization for expensive objects
val expensiveResource by lazy { createExpensiveResource() }

// Use Flow for reactive data streams
val configUpdates: Flow<Config> = configManager.getUpdatesFlow()
    .distinctUntilChanged()
    .debounce(100)
```

## 🎯 **FUTURE DEVELOPMENT**

### **Roadmap Priorities**
1. **On-device model training** with user adaptation
2. **Multi-language neural models** with language detection
3. **Advanced accessibility features** with haptic patterns
4. **Custom gesture definitions** with user training
5. **Voice integration** with speech-to-text coordination

### **Architecture Evolution**
- **Modular architecture** with feature plugins
- **Microservice patterns** for component isolation
- **Event-driven architecture** with complete reactive programming
- **ML pipeline automation** with continuous learning
- **Cross-platform compatibility** with Kotlin Multiplatform

The CleverKeys Kotlin implementation provides a modern, maintainable, and high-performance foundation for advanced Android keyboard development.