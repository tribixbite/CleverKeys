#!/usr/bin/env python3
"""
Export trained character-level swipe typing model to ONNX and ExecuTorch formats.
Creates deployment packages with all necessary configuration and examples.
"""

import os
import json
import math
import torch
import torch.nn as nn
import numpy as np
from pathlib import Path
from typing import Dict, Tuple, Optional
import onnx
import onnxruntime as ort

# Import the character model components
from train_character_model import CharacterLevelSwipeModel, CharTokenizer


def load_best_checkpoint() -> Tuple[CharacterLevelSwipeModel, str]:
    """Load the best performing checkpoint."""
    checkpoint_dir = Path('checkpoints/full_character_model')
    
    # Find the best checkpoint (70.1% accuracy)
    checkpoint_path = checkpoint_dir / 'full-model-14-0.701.ckpt'
    
    if not checkpoint_path.exists():
        # Try to find any checkpoint with >70% accuracy
        checkpoints = list(checkpoint_dir.glob('*.ckpt'))
        checkpoints.sort(key=lambda x: float(x.stem.split('-')[-1]), reverse=True)
        if checkpoints:
            checkpoint_path = checkpoints[0]
            print(f"Using checkpoint: {checkpoint_path}")
        else:
            raise FileNotFoundError("No checkpoint found! Please train the model first.")
    
    print(f"Loading checkpoint: {checkpoint_path}")
    checkpoint = torch.load(checkpoint_path, map_location='cpu', weights_only=False)
    
    # Initialize model with same architecture as training
    tokenizer = CharTokenizer()
    model = CharacterLevelSwipeModel(
        traj_dim=6,
        d_model=256,
        nhead=8,
        num_encoder_layers=6,
        num_decoder_layers=4,
        dim_feedforward=1024,
        dropout=0.0,  # No dropout for inference
        char_vocab_size=tokenizer.vocab_size,
        kb_vocab_size=tokenizer.vocab_size
    )
    
    # Load weights
    model.load_state_dict(checkpoint['model_state_dict'])
    model.eval()
    
    accuracy = checkpoint.get('val_word_acc', 0.0)
    print(f"Model loaded: {accuracy:.1%} word accuracy")
    
    return model, f"{accuracy:.3f}"


def export_to_onnx(model: CharacterLevelSwipeModel, output_dir: Path) -> Dict:
    """Export model to ONNX format for web deployment."""
    print("\n=== ONNX Export ===")
    
    onnx_path = output_dir / 'swipe_model_character.onnx'
    
    # Create wrapper for ONNX export (handles autoregressive generation)
    class ONNXWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
            
        def forward(self, traj_features, nearest_keys, src_mask):
            # For ONNX, we export the encoder only
            # The decoder will be run autoregressively in JavaScript
            
            # Use the model's encode_trajectory method
            memory = self.model.encode_trajectory(traj_features, nearest_keys, src_mask)
            
            return memory
    
    wrapper = ONNXWrapper(model)
    wrapper.eval()
    
    # Create sample inputs
    batch_size = 1
    seq_len = 150  # Max sequence length from model_config.json
    traj_features = torch.randn(batch_size, seq_len, 6)
    nearest_keys = torch.randint(0, 30, (batch_size, seq_len))  # 2D tensor
    src_mask = torch.zeros(batch_size, seq_len, dtype=torch.bool)
    
    # Dynamic axes for variable length sequences
    dynamic_axes = {
        'trajectory_features': {0: 'batch', 1: 'sequence'},
        'nearest_keys': {0: 'batch', 1: 'sequence'},
        'src_mask': {0: 'batch', 1: 'sequence'},
        'encoder_output': {0: 'batch', 1: 'sequence'}
    }
    
    # Export encoder
    torch.onnx.export(
        wrapper,
        (traj_features, nearest_keys, src_mask),
        onnx_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['trajectory_features', 'nearest_keys', 'src_mask'],
        output_names=['encoder_output'],
        dynamic_axes=dynamic_axes,
        verbose=False
    )
    
    # Validate
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    
    # Test inference
    ort_session = ort.InferenceSession(str(onnx_path))
    ort_inputs = {
        'trajectory_features': traj_features.numpy(),
        'nearest_keys': nearest_keys.numpy(),
        'src_mask': src_mask.numpy()
    }
    ort_outputs = ort_session.run(None, ort_inputs)
    
    print(f"✓ Encoder exported: {onnx_path}")
    print(f"  Output shape: {ort_outputs[0].shape}")
    
    # Export decoder separately
    decoder_path = output_dir / 'swipe_decoder_character.onnx'
    
    class DecoderWrapper(nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model
            self.d_model = model.d_model
            
        def forward(self, memory, tgt_tokens, src_mask, tgt_mask):
            # Embed target tokens
            batch_size, tgt_len = tgt_tokens.shape
            tgt_emb = self.model.char_embedding(tgt_tokens) * math.sqrt(self.d_model)
            tgt_emb = tgt_emb + self.model.pe[:, :tgt_len, :]
            
            # Create causal mask for autoregressive generation
            causal_mask = nn.Transformer.generate_square_subsequent_mask(tgt_len).to(tgt_emb.device)
            
            # Decode
            output = self.model.decoder(
                tgt_emb, memory,
                tgt_mask=causal_mask,
                memory_key_padding_mask=src_mask,
                tgt_key_padding_mask=tgt_mask
            )
            
            # Project to vocabulary
            logits = self.model.output_proj(output)
            
            return logits
    
    decoder_wrapper = DecoderWrapper(model)
    decoder_wrapper.eval()
    
    # Sample inputs for decoder
    memory = torch.randn(batch_size, seq_len, 256)
    tgt_tokens = torch.randint(0, 30, (batch_size, 20))
    src_mask_decoder = torch.zeros(batch_size, seq_len, dtype=torch.bool)
    tgt_mask = torch.zeros(batch_size, 20, dtype=torch.bool)
    
    dynamic_axes_decoder = {
        'memory': {0: 'batch', 1: 'enc_sequence'},
        'target_tokens': {0: 'batch', 1: 'dec_sequence'},
        'src_mask': {0: 'batch', 1: 'enc_sequence'},
        'target_mask': {0: 'batch', 1: 'dec_sequence'},
        'logits': {0: 'batch', 1: 'dec_sequence'}
    }
    
    torch.onnx.export(
        decoder_wrapper,
        (memory, tgt_tokens, src_mask_decoder, tgt_mask),
        decoder_path,
        export_params=True,
        opset_version=14,
        do_constant_folding=True,
        input_names=['memory', 'target_tokens', 'src_mask', 'target_mask'],
        output_names=['logits'],
        dynamic_axes=dynamic_axes_decoder,
        verbose=False
    )
    
    print(f"✓ Decoder exported: {decoder_path}")
    
    return {
        'encoder_path': str(onnx_path),
        'decoder_path': str(decoder_path),
        'encoder_size_kb': os.path.getsize(onnx_path) / 1024,
        'decoder_size_kb': os.path.getsize(decoder_path) / 1024
    }


def export_to_executorch(model: CharacterLevelSwipeModel, output_dir: Path) -> Dict:
    """Export model to ExecuTorch format for mobile deployment."""
    print("\n=== ExecuTorch Export ===")
    
    try:
        from executorch.exir import to_edge
        from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
        
        # Create a simpler model wrapper for mobile
        class MobileModel(nn.Module):
            def __init__(self, model):
                super().__init__()
                self.model = model
                self.encoder = model.encoder
                self.decoder = model.decoder
                self.traj_proj = model.traj_proj
                self.kb_embedding = model.kb_embedding
                self.encoder_norm = model.encoder_norm
                self.char_embedding = model.char_embedding
                self.output_proj = model.output_proj
                self.pe = model.pe
                self.d_model = model.d_model
                
            def forward(self, traj_features, nearest_keys):
                # Fixed sequence generation for mobile
                batch_size = traj_features.shape[0]
                seq_len = traj_features.shape[1]
                
                # Encode trajectory using model's method
                memory = self.model.encode_trajectory(traj_features, nearest_keys)
                
                # Generate fixed length output
                max_len = 20
                device = traj_features.device
                
                # Start with SOS token
                output_tokens = torch.full((batch_size, max_len), 0, device=device, dtype=torch.long)
                output_tokens[:, 0] = 3  # SOS token
                
                for i in range(1, max_len):
                    # Embed tokens generated so far
                    tgt = self.char_embedding(output_tokens[:, :i]) * math.sqrt(self.d_model)
                    tgt = tgt + self.pe[:, :i, :]
                    
                    # Create causal mask
                    causal_mask = nn.Transformer.generate_square_subsequent_mask(i).to(device)
                    
                    # Decode
                    decoder_out = self.decoder(tgt, memory, tgt_mask=causal_mask)
                    
                    # Get next token
                    logits = self.output_proj(decoder_out[:, -1, :])
                    next_token = logits.argmax(dim=-1)
                    output_tokens[:, i] = next_token
                
                return output_tokens
        
        mobile_model = MobileModel(model)
        mobile_model.eval()
        
        # Trace the model
        example_inputs = (
            torch.randn(1, 150, 6),  # Max sequence length from model_config.json
            torch.randint(0, 30, (1, 150))  # 2D tensor for nearest_keys
        )
        
        traced_model = torch.jit.trace(mobile_model, example_inputs)
        
        # Convert to ExecuTorch
        edge_model = to_edge(traced_model, example_inputs)
        
        # Apply XNNPACK acceleration
        edge_model = edge_model.to_backend(XnnpackPartitioner())
        
        # Save the model
        et_path = output_dir / 'swipe_model_character.pte'
        with open(et_path, 'wb') as f:
            edge_model.write(f)
        
        print(f"✓ ExecuTorch model exported: {et_path}")
        print(f"  Size: {os.path.getsize(et_path) / 1024:.1f} KB")
        
        return {
            'path': str(et_path),
            'size_kb': os.path.getsize(et_path) / 1024
        }
        
    except ImportError as e:
        print(f"⚠ ExecuTorch not available: {e}")
        print("  Install with: pip install executorch")
        return {}


def create_tokenizer_config(output_dir: Path):
    """Create tokenizer configuration file."""
    tokenizer = CharTokenizer()
    
    config = {
        'vocab_size': tokenizer.vocab_size,
        'char_to_idx': tokenizer.char_to_idx,
        'idx_to_char': tokenizer.idx_to_char,
        'special_tokens': {
            'pad_token': '<pad>',
            'pad_idx': tokenizer.pad_idx,
            'eos_token': '<eos>',
            'eos_idx': tokenizer.eos_idx,
            'unk_token': '<unk>',
            'unk_idx': tokenizer.unk_idx,
            'sos_token': '<sos>',
            'sos_idx': tokenizer.sos_idx
        }
    }
    
    config_path = output_dir / 'tokenizer_config.json'
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)
    
    print(f"✓ Tokenizer config saved: {config_path}")
    return config


def create_model_config(output_dir: Path, accuracy: str):
    """Create model configuration file."""
    config = {
        'model_type': 'character_level_transformer',
        'architecture': {
            'trajectory_dim': 6,
            'd_model': 256,
            'nhead': 8,
            'num_encoder_layers': 6,
            'num_decoder_layers': 4,
            'dim_feedforward': 1024,
            'vocab_size': 30,
            'max_seq_length': 150,
            'max_word_length': 20
        },
        'feature_extraction': {
            'input_features': ['x', 'y', 'vx', 'vy', 'ax', 'ay'],
            'normalization': {
                'keyboard_width': 360,
                'keyboard_height': 215,
                'velocity_scale': 1000,
                'acceleration_scale': 500
            },
            'nearest_keys': {
                'enabled': True,
                'top_k': 3
            }
        },
        'inference': {
            'beam_size': 5,
            'max_length': 20,
            'length_penalty': 1.0,
            'temperature': 1.0
        },
        'performance': {
            'word_accuracy': float(accuracy),
            'model_parameters': 8968510,  # 8.97M
            'model_size_mb': 34.2
        },
        'training': {
            'dataset_size': 68848,
            'epochs_trained': 14,
            'batch_size': 64,
            'learning_rate': 5e-4
        }
    }
    
    config_path = output_dir / 'model_config.json'
    with open(config_path, 'w') as f:
        json.dump(config, f, indent=2)
    
    print(f"✓ Model config saved: {config_path}")
    return config


def create_deployment_guide(output_dir: Path, export_info: Dict):
    """Create comprehensive deployment instructions."""
    guide = f"""# Character-Level Swipe Typing Model Deployment Guide

## Model Overview
- **Architecture**: Transformer-based character-level generation
- **Accuracy**: 70.1% word accuracy
- **Parameters**: 8.97M
- **Input**: 6D trajectory features (x, y, vx, vy, ax, ay) + nearest keys
- **Output**: Character sequence with beam search

## Exported Formats

### 1. ONNX (Web/Browser)
- **Encoder**: `swipe_model_character.onnx` ({export_info.get('encoder_size_kb', 0):.1f} KB)
- **Decoder**: `swipe_decoder_character.onnx` ({export_info.get('decoder_size_kb', 0):.1f} KB)
- **Runtime**: ONNX Runtime Web (onnxruntime-web)
- **Supported Platforms**: Chrome, Firefox, Safari, Edge

### 2. ExecuTorch (Mobile)
- **Model**: `swipe_model_character.pte` ({export_info.get('et_size_kb', 0):.1f} KB)
- **Runtime**: ExecuTorch with XNNPACK backend
- **Supported Platforms**: Android, iOS

## Web Deployment (ONNX)

### Installation
```bash
npm install onnxruntime-web
```

### TypeScript Integration
```typescript
import * as ort from 'onnxruntime-web';

class SwipePredictor {{
    private encoderSession: ort.InferenceSession;
    private decoderSession: ort.InferenceSession;
    private tokenizer: Tokenizer;
    
    async loadModels(encoderUrl: string, decoderUrl: string) {{
        this.encoderSession = await ort.InferenceSession.create(encoderUrl);
        this.decoderSession = await ort.InferenceSession.create(decoderUrl);
        this.tokenizer = new Tokenizer(); // Load from tokenizer_config.json
    }}
    
    async predictWord(swipePoints: SwipePoint[]): Promise<string> {{
        // 1. Extract features
        const features = this.extractFeatures(swipePoints);
        const nearestKeys = this.findNearestKeys(swipePoints);
        
        // 2. Run encoder
        const encoderInputs = {{
            trajectory_features: new ort.Tensor('float32', features.data, features.shape),
            nearest_keys: new ort.Tensor('int64', nearestKeys.data, nearestKeys.shape),
            src_mask: new ort.Tensor('bool', maskData, maskShape)
        }};
        
        const encoderOutputs = await this.encoderSession.run(encoderInputs);
        const memory = encoderOutputs.encoder_output;
        
        // 3. Run beam search with decoder
        return await this.beamSearch(memory, 5);
    }}
    
    private async beamSearch(memory: ort.Tensor, beamSize: number): Promise<string> {{
        // Implement beam search using decoder
        // See web_integration_full.ts for complete implementation
    }}
}}
```

## Android Deployment (ExecuTorch)

### Setup
1. Add ExecuTorch to your Android project:
```gradle
dependencies {{
    implementation 'org.pytorch:executorch-android:0.1.0'
}}
```

2. Load and run the model:
```kotlin
class SwipePredictor(context: Context) {{
    private lateinit var module: Module
    
    init {{
        val modelPath = getAssetFilePath(context, "swipe_model_character.pte")
        module = Module.load(modelPath)
    }}
    
    fun predictWord(swipePoints: List<SwipePoint>): String {{
        // Extract features
        val features = extractFeatures(swipePoints)
        val nearestKeys = findNearestKeys(swipePoints)
        
        // Create input tensors
        val inputTensor = Tensor.fromBlob(
            features,
            longArrayOf(1, swipePoints.size.toLong(), 6)
        )
        val keysTensor = Tensor.fromBlob(
            nearestKeys,
            longArrayOf(1, swipePoints.size.toLong(), 3)
        )
        
        // Run inference
        val outputTensor = module.forward(
            IValue.from(inputTensor),
            IValue.from(keysTensor)
        ).toTensor()
        
        // Decode output
        return decodeOutput(outputTensor)
    }}
}}
```

## iOS Deployment (ExecuTorch)

### Setup
1. Add ExecuTorch to your iOS project via CocoaPods:
```ruby
pod 'ExecuTorch', '~> 0.1.0'
```

2. Swift implementation:
```swift
import ExecuTorch

class SwipePredictor {{
    private var module: ETModule!
    
    init() {{
        let modelPath = Bundle.main.path(forResource: "swipe_model_character", ofType: "pte")!
        module = try! ETModule(contentsOfFile: modelPath)
    }}
    
    func predictWord(swipePoints: [SwipePoint]) -> String {{
        // Extract features
        let features = extractFeatures(from: swipePoints)
        let nearestKeys = findNearestKeys(for: swipePoints)
        
        // Create tensors
        let inputTensor = try! ETTensor(
            data: features,
            shape: [1, swipePoints.count, 6]
        )
        let keysTensor = try! ETTensor(
            data: nearestKeys,
            shape: [1, swipePoints.count, 3]
        )
        
        // Run inference
        let output = try! module.forward([inputTensor, keysTensor])
        
        // Decode
        return decodeOutput(output[0])
    }}
}}
```

## Feature Extraction

All platforms must implement consistent feature extraction:

```python
def extract_features(points):
    '''Extract 6D features from swipe points.'''
    features = []
    
    for i, point in enumerate(points):
        # Normalize coordinates
        x = point.x / 360  # keyboard width
        y = point.y / 215  # keyboard height
        
        # Calculate velocity
        if i > 0:
            dt = max(point.t - points[i-1].t, 1)
            vx = (point.x - points[i-1].x) / dt
            vy = (point.y - points[i-1].y) / dt
        else:
            vx = vy = 0
        
        # Calculate acceleration
        if i > 1:
            # ... (see complete implementation)
        else:
            ax = ay = 0
        
        # Normalize and clip
        vx = clip(vx / 1000, -1, 1)
        vy = clip(vy / 1000, -1, 1)
        ax = clip(ax / 500, -1, 1)
        ay = clip(ay / 500, -1, 1)
        
        features.append([x, y, vx, vy, ax, ay])
    
    return features
```

## Beam Search Implementation

The model uses beam search for better accuracy:

```javascript
async function beamSearch(memory, beamSize = 5) {{
    let beams = [{{
        tokens: [SOS_TOKEN],
        score: 0,
        finished: false
    }}];
    
    for (let step = 0; step < MAX_LENGTH; step++) {{
        let allCandidates = [];
        
        for (const beam of beams) {{
            if (beam.finished) {{
                allCandidates.push(beam);
                continue;
            }}
            
            // Run decoder
            const logits = await runDecoder(memory, beam.tokens);
            const probs = softmax(logits);
            
            // Get top k tokens
            const topK = getTopK(probs, beamSize);
            
            for (const [token, prob] of topK) {{
                const newBeam = {{
                    tokens: [...beam.tokens, token],
                    score: beam.score + Math.log(prob),
                    finished: token === EOS_TOKEN
                }};
                allCandidates.push(newBeam);
            }}
        }}
        
        // Keep top beams
        beams = allCandidates
            .sort((a, b) => b.score - a.score)
            .slice(0, beamSize);
        
        // Check if all beams are finished
        if (beams.every(b => b.finished)) break;
    }}
    
    // Return best beam
    return decodeTokens(beams[0].tokens);
}}
```

## Performance Optimization

### Web (ONNX)
- Use WebAssembly SIMD for 2-3x speedup
- Enable WebGL backend for GPU acceleration
- Cache model sessions between predictions
- Use Web Workers for non-blocking inference

### Mobile (ExecuTorch)
- Use XNNPACK backend for CPU optimization
- Enable GPU delegation where available
- Implement model quantization for smaller size
- Use batch processing for multiple predictions

## Testing

### Unit Tests
```javascript
describe('SwipePredictor', () => {{
    it('should predict "hello" correctly', async () => {{
        const points = [
            {{x: 96, y: 167, t: 0}},   // h
            {{x: 124, y: 167, t: 50}}, // e
            {{x: 152, y: 167, t: 100}}, // l
            {{x: 152, y: 167, t: 150}}, // l
            {{x: 208, y: 167, t: 200}}  // o
        ];
        
        const prediction = await predictor.predictWord(points);
        expect(prediction).toBe('hello');
    }});
}});
```

### Integration Tests
- Test with real swipe data from `data/combined_dataset/`
- Verify 70% accuracy on test set
- Test edge cases (short words, long words, unusual patterns)

## Troubleshooting

### Common Issues

1. **Low accuracy**: Ensure feature extraction matches training
2. **Slow inference**: Enable hardware acceleration
3. **Memory issues**: Use model quantization
4. **Crashes**: Check tensor shapes and data types

### Debug Mode
Enable verbose logging to debug issues:
```javascript
predictor.setDebugMode(true);
```

## Resources

- Model weights: `checkpoints/full_character_model/`
- Training code: `train_character_model.py`
- Evaluation: `evaluate_character_model.py`
- Dataset: `data/combined_dataset/`

## Support

For issues or questions:
- GitHub: [repository-url]
- Documentation: See `docs/` folder
- Examples: See `examples/` folder

---
Generated with 70.1% word accuracy on 68,848 training samples.
"""
    
    guide_path = output_dir / 'DEPLOYMENT_GUIDE.md'
    with open(guide_path, 'w') as f:
        f.write(guide)
    
    print(f"✓ Deployment guide saved: {guide_path}")


def create_web_integration_example(output_dir: Path):
    """Create complete web integration example."""
    example = """// Complete Web Integration Example for Character-Level Swipe Model
// File: swipe-predictor.ts

import * as ort from 'onnxruntime-web';

interface SwipePoint {
    x: number;
    y: number;
    t: number;
}

interface Beam {
    tokens: number[];
    score: number;
    finished: boolean;
}

export class CharacterSwipePredictor {
    private encoderSession?: ort.InferenceSession;
    private decoderSession?: ort.InferenceSession;
    private tokenizer: any;
    private keyboardLayout: any;
    
    // Special tokens
    private readonly PAD_IDX = 0;
    private readonly EOS_IDX = 1;
    private readonly UNK_IDX = 2;
    private readonly SOS_IDX = 3;
    
    constructor() {
        this.loadTokenizer();
        this.loadKeyboardLayout();
    }
    
    private loadTokenizer() {
        // Load from tokenizer_config.json
        this.tokenizer = {
            charToIdx: {
                '<pad>': 0, '<eos>': 1, '<unk>': 2, '<sos>': 3,
                'a': 4, 'b': 5, 'c': 6, 'd': 7, 'e': 8, 'f': 9,
                'g': 10, 'h': 11, 'i': 12, 'j': 13, 'k': 14, 'l': 15,
                'm': 16, 'n': 17, 'o': 18, 'p': 19, 'q': 20, 'r': 21,
                's': 22, 't': 23, 'u': 24, 'v': 25, 'w': 26, 'x': 27,
                'y': 28, 'z': 29
            },
            idxToChar: {} as any
        };
        
        // Create reverse mapping
        for (const [char, idx] of Object.entries(this.tokenizer.charToIdx)) {
            this.tokenizer.idxToChar[idx] = char;
        }
    }
    
    private loadKeyboardLayout() {
        // QWERTY keyboard layout with positions
        this.keyboardLayout = {
            'q': {x: 18, y: 111}, 'w': {x: 54, y: 111}, 'e': {x: 90, y: 111},
            'r': {x: 126, y: 111}, 't': {x: 162, y: 111}, 'y': {x: 198, y: 111},
            'u': {x: 234, y: 111}, 'i': {x: 270, y: 111}, 'o': {x: 306, y: 111},
            'p': {x: 342, y: 111},
            'a': {x: 36, y: 167}, 's': {x: 72, y: 167}, 'd': {x: 108, y: 167},
            'f': {x: 144, y: 167}, 'g': {x: 180, y: 167}, 'h': {x: 216, y: 167},
            'j': {x: 252, y: 167}, 'k': {x: 288, y: 167}, 'l': {x: 324, y: 167},
            'z': {x: 72, y: 223}, 'x': {x: 108, y: 223}, 'c': {x: 144, y: 223},
            'v': {x: 180, y: 223}, 'b': {x: 216, y: 223}, 'n': {x: 252, y: 223},
            'm': {x: 288, y: 223}
        };
    }
    
    async loadModels(encoderUrl: string, decoderUrl: string) {
        console.log('Loading ONNX models...');
        
        // Configure session options
        const options: ort.InferenceSession.SessionOptions = {
            executionProviders: ['wasm'],  // Use 'webgl' for GPU
            graphOptimizationLevel: 'all'
        };
        
        this.encoderSession = await ort.InferenceSession.create(encoderUrl, options);
        this.decoderSession = await ort.InferenceSession.create(decoderUrl, options);
        
        console.log('Models loaded successfully');
    }
    
    async predictWord(swipePoints: SwipePoint[], beamSize: number = 5): Promise<string> {
        if (!this.encoderSession || !this.decoderSession) {
            throw new Error('Models not loaded');
        }
        
        // 1. Extract features
        const features = this.extractFeatures(swipePoints);
        const nearestKeys = this.findNearestKeys(swipePoints);
        const srcMask = new Float32Array(swipePoints.length).fill(0);
        
        // 2. Prepare encoder inputs
        const encoderInputs = {
            trajectory_features: new ort.Tensor(
                'float32',
                features,
                [1, swipePoints.length, 6]
            ),
            nearest_keys: new ort.Tensor(
                'int64',
                nearestKeys,
                [1, swipePoints.length, 3]
            ),
            src_mask: new ort.Tensor(
                'bool',
                srcMask,
                [1, swipePoints.length]
            )
        };
        
        // 3. Run encoder
        const encoderOutputs = await this.encoderSession.run(encoderInputs);
        const memory = encoderOutputs.encoder_output;
        
        // 4. Run beam search with decoder
        const result = await this.beamSearchDecode(memory, beamSize);
        
        return result;
    }
    
    private extractFeatures(points: SwipePoint[]): Float32Array {
        const features: number[] = [];
        
        for (let i = 0; i < points.length; i++) {
            const p = points[i];
            
            // Normalize coordinates
            const x = p.x / 360;
            const y = p.y / 215;
            
            // Calculate velocities
            let vx = 0, vy = 0;
            if (i > 0) {
                const prev = points[i - 1];
                const dt = Math.max(p.t - prev.t, 1);
                vx = (p.x - prev.x) / dt;
                vy = (p.y - prev.y) / dt;
            }
            
            // Calculate accelerations
            let ax = 0, ay = 0;
            if (i > 1) {
                const prev = points[i - 1];
                const prev2 = points[i - 2];
                const dt1 = Math.max(p.t - prev.t, 1);
                const dt2 = Math.max(prev.t - prev2.t, 1);
                
                const vxPrev = (prev.x - prev2.x) / dt2;
                const vyPrev = (prev.y - prev2.y) / dt2;
                
                ax = (vx - vxPrev) / dt1;
                ay = (vy - vyPrev) / dt1;
            }
            
            // Normalize and clip
            vx = Math.max(-1, Math.min(1, vx / 1000));
            vy = Math.max(-1, Math.min(1, vy / 1000));
            ax = Math.max(-1, Math.min(1, ax / 500));
            ay = Math.max(-1, Math.min(1, ay / 500));
            
            features.push(x, y, vx, vy, ax, ay);
        }
        
        return new Float32Array(features);
    }
    
    private findNearestKeys(points: SwipePoint[]): BigInt64Array {
        const nearestKeys: bigint[] = [];
        
        for (const point of points) {
            // Find 3 nearest keys for each point
            const distances: Array<{key: string, dist: number}> = [];
            
            for (const [key, pos] of Object.entries(this.keyboardLayout)) {
                const dist = Math.sqrt(
                    Math.pow(point.x - pos.x, 2) + 
                    Math.pow(point.y - pos.y, 2)
                );
                distances.push({key, dist});
            }
            
            // Sort by distance and take top 3
            distances.sort((a, b) => a.dist - b.dist);
            const top3 = distances.slice(0, 3);
            
            // Convert to indices
            for (const {key} of top3) {
                const idx = this.tokenizer.charToIdx[key] || this.UNK_IDX;
                nearestKeys.push(BigInt(idx));
            }
        }
        
        return new BigInt64Array(nearestKeys);
    }
    
    private async beamSearchDecode(
        memory: ort.Tensor,
        beamSize: number
    ): Promise<string> {
        const maxLength = 20;
        let beams: Beam[] = [{
            tokens: [this.SOS_IDX],
            score: 0,
            finished: false
        }];
        
        for (let step = 0; step < maxLength; step++) {
            const allCandidates: Beam[] = [];
            
            for (const beam of beams) {
                if (beam.finished) {
                    allCandidates.push(beam);
                    continue;
                }
                
                // Prepare decoder inputs
                const tgtTokens = new BigInt64Array(
                    beam.tokens.map(t => BigInt(t))
                );
                const tgtMask = new Float32Array(beam.tokens.length).fill(0);
                
                const decoderInputs = {
                    memory: memory,
                    target_tokens: new ort.Tensor(
                        'int64',
                        tgtTokens,
                        [1, beam.tokens.length]
                    ),
                    target_mask: new ort.Tensor(
                        'bool',
                        tgtMask,
                        [1, beam.tokens.length]
                    )
                };
                
                // Run decoder
                const decoderOutputs = await this.decoderSession!.run(decoderInputs);
                const logits = decoderOutputs.logits;
                
                // Get last token predictions
                const logitsData = logits.data as Float32Array;
                const vocabSize = 30;
                const lastLogits = logitsData.slice(-vocabSize);
                
                // Apply softmax and get top k
                const probs = this.softmax(lastLogits);
                const topK = this.getTopK(probs, beamSize);
                
                // Create new beams
                for (const {idx, prob} of topK) {
                    allCandidates.push({
                        tokens: [...beam.tokens, idx],
                        score: beam.score + Math.log(prob),
                        finished: idx === this.EOS_IDX
                    });
                }
            }
            
            // Keep top beams
            beams = allCandidates
                .sort((a, b) => b.score - a.score)
                .slice(0, beamSize);
            
            // Check if all finished
            if (beams.every(b => b.finished)) break;
        }
        
        // Decode best beam
        return this.decodeTokens(beams[0].tokens);
    }
    
    private softmax(logits: Float32Array): Float32Array {
        const maxLogit = Math.max(...logits);
        const expScores = logits.map(l => Math.exp(l - maxLogit));
        const sumExp = expScores.reduce((a, b) => a + b, 0);
        return new Float32Array(expScores.map(e => e / sumExp));
    }
    
    private getTopK(
        probs: Float32Array,
        k: number
    ): Array<{idx: number, prob: number}> {
        const indexed = Array.from(probs).map((prob, idx) => ({idx, prob}));
        indexed.sort((a, b) => b.prob - a.prob);
        return indexed.slice(0, k);
    }
    
    private decodeTokens(tokens: number[]): string {
        let word = '';
        
        for (const token of tokens) {
            if (token === this.EOS_IDX) break;
            if (token === this.SOS_IDX || token === this.PAD_IDX) continue;
            
            const char = this.tokenizer.idxToChar[token];
            if (char && !char.startsWith('<')) {
                word += char;
            }
        }
        
        return word;
    }
}

// Usage example
async function demo() {
    const predictor = new CharacterSwipePredictor();
    
    // Load models
    await predictor.loadModels(
        '/models/swipe_model_character.onnx',
        '/models/swipe_decoder_character.onnx'
    );
    
    // Example swipe for "hello"
    const swipePoints: SwipePoint[] = [
        {x: 216, y: 167, t: 0},   // h
        {x: 90, y: 111, t: 100},  // e
        {x: 324, y: 167, t: 200}, // l
        {x: 324, y: 167, t: 300}, // l
        {x: 306, y: 111, t: 400}  // o
    ];
    
    const prediction = await predictor.predictWord(swipePoints);
    console.log('Predicted word:', prediction); // Should output: "hello"
}

export default CharacterSwipePredictor;
"""
    
    example_path = output_dir / 'examples' / 'web_integration_full.ts'
    example_path.parent.mkdir(exist_ok=True)
    
    with open(example_path, 'w') as f:
        f.write(example)
    
    print(f"✓ Web integration example saved: {example_path}")


def main():
    """Main export function."""
    print("="*60)
    print("Character-Level Swipe Model Export")
    print("="*60)
    
    # Create output directory
    output_dir = Path('deployment_package')
    output_dir.mkdir(exist_ok=True)
    
    # Load the trained model
    model, accuracy = load_best_checkpoint()
    
    # Export to ONNX
    onnx_info = export_to_onnx(model, output_dir)
    
    # Export to ExecuTorch (optional - may fail)
    try:
        et_info = export_to_executorch(model, output_dir)
    except Exception as e:
        print(f"⚠ ExecuTorch export failed: {e}")
        print("  Continuing with ONNX export only...")
        et_info = {}
    
    # Create configuration files
    create_tokenizer_config(output_dir)
    create_model_config(output_dir, accuracy)
    
    # Combine export info
    export_info = {
        'encoder_size_kb': onnx_info.get('encoder_size_kb', 0),
        'decoder_size_kb': onnx_info.get('decoder_size_kb', 0),
        'et_size_kb': et_info.get('size_kb', 0)
    }
    
    # Create deployment guide
    create_deployment_guide(output_dir, export_info)
    
    # Create integration examples
    create_web_integration_example(output_dir)
    
    print("\n" + "="*60)
    print("✅ Export Complete!")
    print("="*60)
    print(f"\n📦 Deployment package created in: {output_dir}/")
    print("\nContents:")
    print("  - swipe_model_character.onnx (encoder)")
    print("  - swipe_decoder_character.onnx (decoder)")
    if et_info:
        print("  - swipe_model_character.pte (ExecuTorch)")
    print("  - tokenizer_config.json")
    print("  - model_config.json")
    print("  - DEPLOYMENT_GUIDE.md")
    print("  - examples/web_integration_full.ts")
    print(f"\n🎯 Model accuracy: {float(accuracy)*100:.1f}%")
    print("\nNext steps:")
    print("1. Test ONNX models in browser with example code")
    print("2. Deploy to web application")
    print("3. Test on mobile with ExecuTorch")


if __name__ == "__main__":
    main()