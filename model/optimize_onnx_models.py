#!/usr/bin/env python3
"""
Optimize and compress ONNX models for web deployment.
Applies quantization and optimization techniques to reduce model size.
"""

import os
from pathlib import Path
import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
from onnxruntime.quantization.shape_inference import quant_pre_process
import numpy as np


def optimize_onnx_model(input_path: Path, output_path: Path):
    """Apply ONNX Runtime preprocessing for quantization."""
    print(f"Pre-processing model from {input_path}")
    
    # Get original size
    original_size = os.path.getsize(input_path) / (1024 * 1024)
    print(f"Original size: {original_size:.2f} MB")
    
    # Apply preprocessing optimizations
    print("Applying preprocessing optimizations...")
    quant_pre_process(
        str(input_path),
        str(output_path),
        skip_optimization=False,  # Apply optimizations
        skip_onnx_shape=False,    # Run shape inference
        skip_symbolic_shape=False, # Run symbolic shape inference
        auto_merge=True,          # Merge computation nodes
        int_max=2**31-1,
        guess_output_rank=False,
        verbose=0,
        save_as_external_data=False,
        all_tensors_to_one_file=True,
        external_data_location="",
        external_data_size_threshold=1024,
    )
    
    optimized_size = os.path.getsize(output_path) / (1024 * 1024) 
    print(f"Optimized size: {optimized_size:.2f} MB")
    print(f"Size reduction: {(1 - optimized_size/original_size)*100:.1f}%")
    
    return True


def quantize_onnx_model(input_path: Path, output_path: Path):
    """Apply dynamic quantization to reduce model to int8."""
    print(f"\nQuantizing model from {input_path}")
    
    original_size = os.path.getsize(input_path) / (1024 * 1024)
    print(f"Original size: {original_size:.2f} MB")
    
    # Apply dynamic quantization
    quantize_dynamic(
        str(input_path),
        str(output_path),
        weight_type=QuantType.QInt8,
        optimize_model=True,
        per_channel=True,
        reduce_range=True,
    )
    
    quantized_size = os.path.getsize(output_path) / (1024 * 1024)
    print(f"Quantized size: {quantized_size:.2f} MB")
    print(f"Size reduction: {(1 - quantized_size/original_size)*100:.1f}%")


def main():
    # Paths
    deployment_dir = Path("deployment_package")
    web_demo_dir = Path("web-demo/public/models")
    
    # Original models
    encoder_path = deployment_dir / "swipe_model_character.onnx"
    decoder_path = deployment_dir / "swipe_decoder_character.onnx"
    
    # Optimized paths
    encoder_opt = deployment_dir / "swipe_model_character_opt.onnx"
    decoder_opt = deployment_dir / "swipe_decoder_character_opt.onnx"
    
    # Quantized paths  
    encoder_quant = deployment_dir / "swipe_model_character_quant.onnx"
    decoder_quant = deployment_dir / "swipe_decoder_character_quant.onnx"
    
    print("=" * 60)
    print("ONNX Model Optimization and Compression")
    print("=" * 60)
    
    # Optimize models
    print("\n=== Graph Optimization ===")
    optimize_onnx_model(encoder_path, encoder_opt)
    optimize_onnx_model(decoder_path, decoder_opt)
    
    # Quantize models
    print("\n=== Dynamic Quantization (INT8) ===")
    quantize_onnx_model(encoder_opt, encoder_quant)
    quantize_onnx_model(decoder_opt, decoder_quant)
    
    # Copy best versions to web demo
    print("\n=== Deploying Optimized Models ===")
    
    # Check which version is smallest
    sizes = {
        'original': (os.path.getsize(encoder_path) + os.path.getsize(decoder_path)) / (1024 * 1024),
        'optimized': (os.path.getsize(encoder_opt) + os.path.getsize(decoder_opt)) / (1024 * 1024),
        'quantized': (os.path.getsize(encoder_quant) + os.path.getsize(decoder_quant)) / (1024 * 1024),
    }
    
    print(f"Total sizes:")
    for name, size in sizes.items():
        print(f"  {name}: {size:.2f} MB")
    
    # Use optimized version (quantized might have accuracy issues)
    import shutil
    print(f"\nCopying optimized models to web demo...")
    shutil.copy(encoder_opt, web_demo_dir / "swipe_model_character.onnx")
    shutil.copy(decoder_opt, web_demo_dir / "swipe_decoder_character.onnx")
    print(f"✓ Models deployed to {web_demo_dir}")


if __name__ == "__main__":
    main()