#!/usr/bin/env python3
"""Minimal reproducer for masked_fill ONNX export issue."""

import torch

class MinimalMaskedFill(torch.nn.Module):
    def forward(self, x, mask):
        # Use a common fill value for transformers
        return x.masked_fill(mask, -1e9)

model = MinimalMaskedFill()
x = torch.randn(2, 4, dtype=torch.float32)
mask = torch.tensor([[True, True, False, False], [False, True, True, False]], dtype=torch.bool)

print("Testing masked_fill ONNX export...")

# Test with opset 11
try:
    torch.onnx.export(
        model,
        (x, mask),
        "minimal_test_opset11.onnx",
        opset_version=11,
        input_names=['x', 'mask'],
        output_names=['output']
    )
    print("✅ Export successful with opset 11")
except Exception as e:
    print(f"❌ Export failed with opset 11: {e}")

# Test with opset 14
try:
    torch.onnx.export(
        model,
        (x, mask),
        "minimal_test_opset14.onnx",
        opset_version=14,
        input_names=['x', 'mask'],
        output_names=['output']
    )
    print("✅ Export successful with opset 14")
except Exception as e:
    print(f"❌ Export failed with opset 14: {e}")

# Test with opset 17
try:
    torch.onnx.export(
        model,
        (x, mask),
        "minimal_test_opset17.onnx",
        opset_version=17,
        input_names=['x', 'mask'],
        output_names=['output']
    )
    print("✅ Export successful with opset 17")
except Exception as e:
    print(f"❌ Export failed with opset 17: {e}")

# Test with torch.where alternative
class MinimalWhere(torch.nn.Module):
    def forward(self, x, mask):
        fill_value = torch.tensor(-1e9, dtype=x.dtype)
        return torch.where(mask, fill_value, x)

model_where = MinimalWhere()

try:
    torch.onnx.export(
        model_where,
        (x, mask),
        "minimal_test_where_opset11.onnx",
        opset_version=11,
        input_names=['x', 'mask'],
        output_names=['output']
    )
    print("✅ Export successful with torch.where (opset 11)")
except Exception as e:
    print(f"❌ Export failed with torch.where (opset 11): {e}")

# Test with register_buffer workaround
class MinimalWithBuffer(torch.nn.Module):
    def __init__(self):
        super().__init__()
        # Register the fill value as a buffer to avoid scalar constant serialization
        self.register_buffer('neg_inf', torch.tensor(-1e9))

    def forward(self, x, mask):
        return torch.where(mask, self.neg_inf, x)

model_buffer = MinimalWithBuffer()

try:
    torch.onnx.export(
        model_buffer,
        (x, mask),
        "minimal_test_buffer_opset11.onnx",
        opset_version=11,
        input_names=['x', 'mask'],
        output_names=['output']
    )
    print("✅ Export successful with register_buffer workaround (opset 11)")
except Exception as e:
    print(f"❌ Export failed with register_buffer (opset 11): {e}")
