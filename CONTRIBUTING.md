# Contributing to CleverKeys 🤝

Welcome to CleverKeys! We're excited that you want to contribute to the world's first privacy-first neural keyboard. This guide will help you get started with contributing to our open-source project.

## 🌟 Ways to Contribute

### 🐛 **Bug Reports**
- Found a bug? [Create an issue](https://github.com/tribixbite/CleverKeys/issues/new?template=bug_report.yml)
- Include detailed steps to reproduce
- Provide device information and CleverKeys version
- Add logs from `adb logcat | grep -E "CleverKeys|Neural|ONNX"` if possible

### ✨ **Feature Requests**
- Have an idea? [Request a feature](https://github.com/tribixbite/CleverKeys/issues/new?template=feature_request.yml)
- Explain the problem your feature would solve
- Describe your proposed solution
- Consider privacy and performance implications

### 💻 **Code Contributions**
- **Neural Models**: Improve prediction accuracy and speed
- **Performance**: Optimize inference and memory usage
- **UI/UX**: Enhance keyboard layouts and user experience
- **Languages**: Add new keyboard layouts and language support
- **Accessibility**: Improve screen reader and motor accessibility
- **Testing**: Expand our comprehensive test coverage

### 📚 **Documentation**
- Improve README, API docs, or code comments
- Create tutorials or usage guides
- Translate documentation to other languages
- Write technical blog posts about CleverKeys

## 🚀 Getting Started

### Development Environment Setup

#### Prerequisites
- **Android Studio** or IntelliJ IDEA with Android plugin
- **Android SDK** (API 21+ required, API 34 recommended)
- **Java 17** or higher
- **Git** for version control

#### 1. Fork and Clone
```bash
# Fork the repository on GitHub, then:
git clone https://github.com/YOUR_USERNAME/CleverKeys.git
cd CleverKeys
```

#### 2. Build and Run
```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Run tests
./gradlew test
```

#### 3. Set up CleverKeys
1. Go to **Settings → Languages & Input → Virtual Keyboard**
2. Add **CleverKeys**
3. Set as default keyboard
4. Enable neural predictions in CleverKeys settings

### Development Workflow

#### 1. Create a Branch
```bash
git checkout -b feature/your-feature-name
# or
git checkout -b fix/bug-description
```

#### 2. Make Changes
- Follow our [coding standards](#coding-standards)
- Write tests for new functionality
- Update documentation as needed
- Ensure all tests pass

#### 3. Test Your Changes
```bash
# Run automated tests
./testing/run-ui-tests.sh

# Build and test APK
./gradlew assembleDebug
adb install build/outputs/apk/debug/tribixbite.keyboard2.debug.apk
```

#### 4. Submit Pull Request
- Push your branch to your fork
- Create a pull request with a clear description
- Link any related issues
- Wait for review and address feedback

## 🧠 Neural Model Contributions

### Model Training
- **Training Data**: Use the neural calibration system to collect training data
- **Model Architecture**: Follow the transformer encoder-decoder pattern
- **Export Format**: ONNX models for cross-platform compatibility
- **Size Constraints**: Keep models under 10MB each for mobile performance

### Performance Requirements
- **Latency**: Predictions must complete in <200ms on mid-range devices
- **Memory**: Additional memory usage should stay under 25MB
- **Accuracy**: Maintain >90% top-3 accuracy on common words
- **Battery**: Minimal impact on battery life during normal typing

### Neural Code Guidelines
```kotlin
// Good: Proper resource management
suspend fun predict(input: SwipeInput): PredictionResult {
    return onnxPredictor.use { predictor ->
        predictor.predict(input)
    }
}

// Good: Error handling
try {
    val result = neuralEngine.processGesture(swipe)
    handlePredictionSuccess(result)
} catch (e: NeuralEngineException) {
    handlePredictionFailure(e)
}
```

## 🎨 UI/UX Contributions

### Design Principles
- **Privacy First**: No visual indicators of data collection
- **Accessibility**: Support screen readers and motor accessibility
- **Performance**: Smooth 60fps animations and interactions
- **Customization**: Respect user preferences and themes

### Layout Contributions
- **XML Definitions**: Follow existing layout format in `src/main/layouts/`
- **Key Mappings**: Use standard key value definitions
- **Testing**: Include visual regression tests for new layouts
- **Documentation**: Document special features or gestures

### Visual Consistency
- **Material Design**: Follow Android Material Design guidelines
- **Dark Mode**: Ensure compatibility with system dark theme
- **Accessibility**: High contrast support and large text compatibility
- **RTL Support**: Right-to-left language layout support

## 📱 Testing Contributions

### Test Types
- **Unit Tests**: Individual component testing
- **Integration Tests**: Component interaction testing
- **UI Tests**: User interface and interaction testing
- **Performance Tests**: Latency and memory usage validation
- **Visual Tests**: Screenshot comparison and regression detection

### Writing Tests
```kotlin
@Test
fun testNeuralPredictionLatency() = runBlocking {
    val swipe = createTestSwipe("hello")
    val startTime = System.currentTimeMillis()

    val result = neuralEngine.processGesture(swipe)

    val latency = System.currentTimeMillis() - startTime
    assertTrue("Prediction too slow: ${latency}ms", latency < 200)
    assertNotNull("Prediction result should not be null", result)
}
```

### Test Coverage Goals
- **Neural Components**: 95%+ coverage of prediction pipeline
- **UI Components**: 90%+ coverage of user interactions
- **Error Handling**: 100% coverage of error scenarios
- **Performance**: All critical paths benchmarked

## 🔧 Coding Standards

### Kotlin Style
```kotlin
// Good: Clear naming and documentation
/**
 * Processes a swipe gesture and returns neural predictions.
 * @param swipeInput Raw swipe trajectory data
 * @return Prediction results with confidence scores
 */
suspend fun processSwipeGesture(swipeInput: SwipeInput): PredictionResult {
    return withContext(Dispatchers.Default) {
        neuralPredictor.predict(swipeInput)
    }
}

// Good: Proper coroutine usage
class NeuralPredictionService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startPrediction(swipe: SwipeInput) {
        scope.launch {
            try {
                val result = processSwipeGesture(swipe)
                updateUI(result)
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }
}
```

### Code Quality
- **Null Safety**: Use Kotlin's null safety features
- **Immutability**: Prefer `val` over `var`, immutable data classes
- **Error Handling**: Explicit error handling, avoid silent failures
- **Documentation**: Clear KDoc comments for public APIs
- **Performance**: Avoid unnecessary allocations in hot paths

### Neural Network Code
- **Resource Management**: Always clean up ONNX resources
- **Memory Efficiency**: Reuse tensors when possible
- **Error Recovery**: Graceful degradation when neural prediction fails
- **Thread Safety**: Proper synchronization for shared neural resources

## 🔒 Privacy Guidelines

### Core Principles
- **Local Processing**: All AI inference must happen on-device
- **No Telemetry**: No usage analytics or crash reporting
- **User Control**: Users must control all data collection
- **Transparency**: Clear documentation of data usage

### Data Handling
```kotlin
// Good: Explicit user consent
fun startDataCollection(userConsent: Boolean) {
    if (userConsent) {
        trainingDataCollector.enable()
    }
}

// Good: Local storage only
fun saveTrainingData(data: SwipeData) {
    localDatabase.insert(data) // Never send to network
}

// Bad: Automatic data collection
fun collectUsageData() {
    // Don't do this - violates privacy principles
}
```

### Security Requirements
- **Encryption**: Sensitive data encrypted with Android KeyStore
- **Permissions**: Minimal permissions, clear justification
- **Network**: No network access for core functionality
- **Audit Trail**: Clear logging of privacy-related operations

## 🌍 Language and Localization

### Adding New Languages
1. **Layout Definition**: Create XML layout in `src/main/layouts/`
2. **Language Model**: Add n-gram data for contextual predictions
3. **Character Mapping**: Update tokenizer for language-specific characters
4. **Testing**: Include automated tests for new language support

### Naming Conventions
- **Layout Files**: `script_layout_locale.xml` (e.g., `latn_qwerty_en.xml`)
- **Language Codes**: Use ISO 639-1 language codes
- **Region Codes**: Use ISO 3166-1 alpha-2 country codes

## 🏗️ Architecture Guidelines

### Project Structure
```
src/main/kotlin/tribixbite/keyboard2/
├── neural/              # Neural prediction components
├── ui/                  # User interface components
├── config/              # Configuration management
├── gestures/            # Gesture recognition
└── layouts/             # Keyboard layout handling
```

### Key Components
- **CleverKeysService**: Main input method service
- **NeuralPredictionPipeline**: Core neural prediction orchestrator
- **SwipeGestureRecognizer**: Gesture detection and processing
- **ConfigurationManager**: Settings and preferences
- **CleverKeysView**: Main keyboard rendering

### Design Patterns
- **Repository Pattern**: Data access abstraction
- **Observer Pattern**: UI updates and state management
- **Factory Pattern**: Component creation and dependency injection
- **Builder Pattern**: Complex configuration objects

## 📊 Performance Guidelines

### Neural Performance
- **Latency Targets**: <200ms prediction time on mid-range devices
- **Memory Targets**: <25MB additional memory usage
- **Battery Targets**: <2% additional battery drain
- **Thermal Targets**: No thermal throttling during normal use

### UI Performance
- **Frame Rate**: Maintain 60fps during all interactions
- **Touch Latency**: <16ms touch-to-visual feedback
- **Startup Time**: <500ms keyboard appearance
- **Layout Switching**: <100ms between layout changes

### Optimization Techniques
```kotlin
// Good: Efficient tensor operations
class OptimizedPredictor {
    private val tensorPool = TensorPool()

    suspend fun predict(input: SwipeInput): PredictionResult {
        val tensor = tensorPool.acquire()
        try {
            return runInference(tensor, input)
        } finally {
            tensorPool.release(tensor)
        }
    }
}

// Good: Lazy initialization
class NeuralEngine {
    private val onnxSession by lazy {
        OrtSession.sessionFromAssets(context, "neural_model.onnx")
    }
}
```

## 🧪 Quality Assurance

### Automated Testing
Our CI/CD pipeline automatically runs:
- **Unit Tests**: Component functionality validation
- **Integration Tests**: End-to-end workflow testing
- **Performance Tests**: Latency and memory benchmarks
- **Visual Tests**: UI regression detection
- **Accessibility Tests**: Screen reader compatibility

### Manual Testing Checklist
Before submitting a PR, manually test:
- [ ] Install APK on test device
- [ ] Enable CleverKeys as default keyboard
- [ ] Test basic typing functionality
- [ ] Test swipe gesture recognition
- [ ] Test neural prediction accuracy
- [ ] Test settings and configuration
- [ ] Test accessibility features
- [ ] Verify no crashes or errors

### Performance Validation
```bash
# Run comprehensive test suite
./testing/run-ui-tests.sh

# Check specific performance metrics
adb logcat | grep "PERFORMANCE_METRIC"

# Validate memory usage
adb shell dumpsys meminfo tribixbite.keyboard2
```

## 🚢 Release Process

### Version Numbering
- **Major**: Breaking changes or major feature additions
- **Minor**: New features with backward compatibility
- **Patch**: Bug fixes and minor improvements

### Release Checklist
1. [ ] All tests passing in CI/CD
2. [ ] Performance benchmarks within targets
3. [ ] Documentation updated
4. [ ] Changelog entries added
5. [ ] Version numbers updated
6. [ ] APK tested on multiple devices
7. [ ] Security review completed

## 📞 Getting Help

### Community Support
- **GitHub Discussions**: [Ask questions and share ideas](https://github.com/tribixbite/CleverKeys/discussions)
- **Issues**: [Report bugs or request features](https://github.com/tribixbite/CleverKeys/issues)
- **Documentation**: Check our comprehensive README and code comments

### Development Support
- **Architecture Questions**: Create a discussion with "architecture" label
- **Neural Model Help**: Create a discussion with "neural" label
- **UI/UX Feedback**: Create a discussion with "design" label
- **Performance Issues**: Create a discussion with "performance" label

## 🙏 Recognition

### Contributors
All contributors are recognized in:
- **README.md**: Contributors section
- **Release Notes**: Major contributor acknowledgments
- **Git History**: Permanent record of contributions

### Contribution Types
We recognize various contribution types:
- 💻 **Code**: Direct code contributions
- 📖 **Documentation**: Documentation improvements
- 🧪 **Testing**: Test coverage improvements
- 🎨 **Design**: UI/UX enhancements
- 🌍 **Translation**: Localization support
- 🐛 **Bug Reports**: Quality issue identification
- 💡 **Ideas**: Feature suggestions and feedback

## 📜 License

By contributing to CleverKeys, you agree that your contributions will be licensed under the same [GPL-3.0 License](LICENSE) that covers the project. This ensures that CleverKeys remains free and open source for everyone.

---

**Thank you for contributing to CleverKeys!** 🎉

Together, we're building the future of privacy-first neural keyboards. Your contributions help make typing faster, smarter, and more private for users around the world.

🧠 **Think Faster** • ⌨️ **Type Smarter** • 🔒 **Stay Private**