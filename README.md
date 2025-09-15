# CleverKeys - Privacy-First Neural Keyboard

**Privacy-focused Android keyboard with neural swipe prediction**

> **Note**: CleverKeys is a modern Kotlin rewrite and enhancement of [Unexpected Keyboard](https://github.com/Julow/Unexpected), adding neural prediction while preserving privacy and customization principles.

CleverKeys combines the **privacy and customization** of Unexpected Keyboard with **advanced neural prediction**:
- **🔒 Complete Privacy**: All processing happens locally - no cloud, no data collection
- **🧠 Neural Swipe Typing**: On-device transformer models for intelligent predictions
- **⚙️ Full Customization**: Configure every algorithm parameter and behavior
- **🎯 Advanced Gestures**: 8-directional swipes, circles, and programmable patterns
- **💻 Developer Focused**: Enhanced programming support and Termux integration

## 🎯 **FEATURES**

### **🧠 Neural Swipe Prediction**
- **Local Transformer Models**: On-device encoder-decoder architecture for swipe-to-text
- **Configurable Intelligence**: Adjust beam width (1-16), max length (10-50), confidence thresholds
- **Batched Processing**: Performance-optimized inference for real-time prediction
- **Privacy-Preserved**: All neural processing happens locally with no data transmission

### **🎯 Advanced Gestures (from Unexpected Keyboard)**
- **8-Directional Swipes**: Access symbols via corner swipes (n, ne, e, se, s, sw, w, nw)
- **Circle Gestures**: Anti-clockwise circles for special characters
- **Compose Keys**: Advanced accent and character composition sequences
- **Custom Patterns**: Define your own gesture shortcuts and behaviors

### **⚙️ Power User Customization**
- **Layout Editor**: Visual keyboard layout creation and modification
- **Neural Engine Tuning**: Real-time adjustment of prediction algorithms
- **Performance Monitoring**: Detailed metrics and optimization controls
- **App-Specific Modes**: Automatic behavior adaptation for development tools

## 📱 **INSTALLATION**

### **Download & Install**
1. Get the latest APK from [GitHub Releases](https://github.com/tribixbite/CleverKeys/releases)
2. Enable "Unknown sources" in Android settings
3. Install CleverKeys APK
4. Go to Settings → Language & Input → Virtual Keyboard → Enable CleverKeys

### **Try the Web Demo**
Experience neural swipe prediction without installation: [CleverKeys Web Demo](https://tribixbite.github.io/CleverKeys)

## ⚙️ **NEURAL ARCHITECTURE**

CleverKeys uses a **transformer encoder-decoder architecture** optimized for mobile devices:

### **Model Architecture**
- **Encoder**: Processes gesture trajectories (coordinates, velocity, acceleration)
- **Decoder**: Generates character sequences with beam search
- **Attention Mechanism**: Learned associations between gestures and text
- **Local Inference**: All processing happens on-device with hardware acceleration

### **Technical Implementation**
- **Model Format**: PyTorch Exported (PTE) files for optimal mobile performance
- **Quantization**: INT8 quantized models for memory efficiency
- **Batched Processing**: Multiple predictions processed simultaneously
- **Memory Management**: Sophisticated tensor pooling and cleanup

*Model details and training code available in [CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML)*

## 🗺️ **ROADMAP**

### **✅ Completed (v1.0)**
- Pure ONNX neural prediction pipeline
- Kotlin architecture with modern Android patterns
- Privacy-first local processing
- Advanced gesture recognition
- Performance optimization with batched inference

### **🔄 In Development (v1.1)**
- APK build system completion
- Device testing and validation
- Performance benchmarking vs baseline
- Production deployment optimization

### **📋 Planned Features**
- **Multi-language Support**: Expand beyond English with language-specific models
- **On-device Training**: User adaptation and personalization
- **Voice Integration**: Speech-to-text coordination
- **Advanced Accessibility**: Enhanced screen reader and motor accessibility features

### **🚫 NOT Planned**
- Cloud-based processing or AI features
- Data collection or telemetry
- Proprietary algorithms or closed-source components

## 🔧 **CONFIGURATION**

### **Neural Prediction**
```kotlin
neural_beam_width = 8                 // Search breadth (1-16)
neural_max_length = 35                // Max word length (10-50)
neural_confidence_threshold = 0.1f    // Prediction filtering (0.0-1.0)
```

### **Quick Symbol Access (Inherited from Unexpected Keyboard)**
- **Corner Swipes**: Northeast (numbers), Southeast (punctuation), Southwest (math), Northwest (programming)
- **Circle Gestures**: Anti-clockwise circles for special functions
- **Compose Keys**: Advanced character composition for accents and symbols
- **Custom Layouts**: QWERTY, AZERTY, Dvorak, Colemak + visual layout editor

## 📊 **COMPARISON**

| Feature | CleverKeys | Gboard | Unexpected KB |
|---------|------------|--------|---------------|
| **Privacy** | 🔒 100% Local | ❌ Cloud | 🔒 100% Local |
| **Neural Prediction** | 🧠 On-device | 🧠 Cloud AI | ❌ None |
| **Open Source** | ✅ Complete | ❌ Proprietary | ✅ Complete |
| **8-Direction Swipes** | ✅ Yes | ❌ No | ✅ Yes |
| **Programming Support** | 💻 Enhanced | ❌ No | 💻 Yes |

## 🤝 **CREDITS & CONTRIBUTING**

### **Acknowledgments**
CleverKeys is built upon the excellent foundation of **[Unexpected Keyboard](https://github.com/Julow/Unexpected)** by Julow. We preserve the privacy-first philosophy and advanced customization while adding modern neural prediction capabilities.

### **Contributing**
- **Issues**: Report bugs and request features
- **Pull Requests**: Code contributions welcome
- **Documentation**: Help improve guides and examples
- **Models**: Contribute to neural model training ([CleverKeys-ML](https://github.com/tribixbite/CleverKeys-ML))

---

**CleverKeys**: Privacy-first neural keyboard for power users • Built with [Unexpected Keyboard](https://github.com/Julow/Unexpected) • [Web Demo](https://tribixbite.github.io/CleverKeys)