#!/usr/bin/env python3
"""
Quantize ONNX models to reduce size for web deployment.
"""

import os
from pathlib import Path
from onnxruntime.quantization import quantize_dynamic, QuantType


def quantize_model(input_path: Path, output_path: Path):
    """Apply dynamic quantization to reduce model to int8."""
    print(f"Quantizing {input_path.name}")
    
    original_size = os.path.getsize(input_path) / (1024 * 1024)
    print(f"  Original size: {original_size:.2f} MB")
    
    # Apply dynamic quantization
    quantize_dynamic(
        str(input_path),
        str(output_path),
        weight_type=QuantType.QUInt8,  # Use unsigned int8 for web compatibility
        per_channel=False,  # Disable per-channel for web compatibility
        reduce_range=False,  # Don't reduce range for web
    )
    
    quantized_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"  Quantized size: {quantized_size:.2f} MB")
    print(f"  Size reduction: {(1 - quantized_size/original_size)*100:.1f}%")
    
    return original_size, quantized_size


def main():
    deployment_dir = Path("deployment_package")
    web_demo_dir = Path("web-demo/public/models")
    
    # Models to quantize
    models = [
        ("swipe_model_character.onnx", "swipe_model_character_quant.onnx"),
        ("swipe_decoder_character.onnx", "swipe_decoder_character_quant.onnx"),
    ]
    
    print("=" * 60)
    print("ONNX Model Quantization (INT8)")
    print("=" * 60)
    
    total_original = 0
    total_quantized = 0
    
    for input_name, output_name in models:
        input_path = deployment_dir / input_name
        output_path = deployment_dir / output_name
        
        if not input_path.exists():
            print(f"⚠ {input_name} not found, skipping")
            continue
            
        orig, quant = quantize_model(input_path, output_path)
        total_original += orig
        total_quantized += quant
    
    print("\n" + "=" * 60)
    print(f"Total original size: {total_original:.2f} MB")
    print(f"Total quantized size: {total_quantized:.2f} MB")
    print(f"Total reduction: {(1 - total_quantized/total_original)*100:.1f}%")
    
    # Note about deployment
    print("\n⚠ Note: Quantized models require ONNX Runtime with quantization support.")
    print("For web deployment, test thoroughly as browser support may vary.")
    print("\nQuantized models saved to deployment_package/")
    print("Test with your web app before replacing original models.")


if __name__ == "__main__":
    main()