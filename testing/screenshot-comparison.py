#!/usr/bin/env python3
"""
Automated screenshot comparison system for CleverKeys visual regression testing.
This script compares current screenshots with baseline images and generates reports.
"""

import os
import sys
import json
import argparse
from pathlib import Path
from typing import Dict, List, Tuple, Optional
import cv2
import numpy as np
from skimage.metrics import structural_similarity as ssim
from PIL import Image, ImageDraw, ImageFont
import matplotlib.pyplot as plt
import matplotlib.patches as patches

class ScreenshotComparator:
    def __init__(self, baseline_dir: str, current_dir: str, output_dir: str):
        self.baseline_dir = Path(baseline_dir)
        self.current_dir = Path(current_dir)
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)

        # Comparison thresholds
        self.ssim_threshold = 0.95  # Structural similarity threshold
        self.pixel_diff_threshold = 0.02  # Percentage of different pixels allowed
        self.color_tolerance = 30  # RGB color difference tolerance

        # Results storage
        self.comparison_results = []

    def compare_screenshots(self) -> Dict:
        """Compare all screenshots and return comprehensive results."""
        print("🔍 Starting screenshot comparison...")

        baseline_files = list(self.baseline_dir.glob("*.png"))
        current_files = list(self.current_dir.glob("*.png"))

        # Find matching pairs
        baseline_names = {f.stem for f in baseline_files}
        current_names = {f.stem for f in current_files}

        # Report missing files
        missing_baseline = current_names - baseline_names
        missing_current = baseline_names - current_names

        if missing_baseline:
            print(f"⚠️  Missing baseline images: {missing_baseline}")
        if missing_current:
            print(f"⚠️  Missing current images: {missing_current}")

        # Compare matching pairs
        common_names = baseline_names & current_names

        for name in common_names:
            baseline_path = self.baseline_dir / f"{name}.png"
            current_path = self.current_dir / f"{name}.png"

            result = self.compare_single_image(baseline_path, current_path, name)
            self.comparison_results.append(result)

        # Generate comprehensive report
        report = self.generate_report()

        # Save detailed results
        self.save_results()

        return report

    def compare_single_image(self, baseline_path: Path, current_path: Path, name: str) -> Dict:
        """Compare two images and return detailed analysis."""
        print(f"📸 Comparing: {name}")

        try:
            # Load images
            baseline = cv2.imread(str(baseline_path))
            current = cv2.imread(str(current_path))

            if baseline is None or current is None:
                return {
                    "name": name,
                    "status": "error",
                    "error": "Failed to load images",
                    "baseline_exists": baseline is not None,
                    "current_exists": current is not None
                }

            # Resize images to match if needed
            if baseline.shape != current.shape:
                print(f"  📏 Resizing images: {baseline.shape} vs {current.shape}")
                min_height = min(baseline.shape[0], current.shape[0])
                min_width = min(baseline.shape[1], current.shape[1])
                baseline = cv2.resize(baseline, (min_width, min_height))
                current = cv2.resize(current, (min_width, min_height))

            # Convert to grayscale for SSIM
            baseline_gray = cv2.cvtColor(baseline, cv2.COLOR_BGR2GRAY)
            current_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)

            # Calculate SSIM
            ssim_score, ssim_diff = ssim(baseline_gray, current_gray, full=True)

            # Calculate pixel differences
            diff = cv2.absdiff(baseline, current)
            diff_percentage = (np.count_nonzero(diff) / diff.size) * 100

            # Calculate color histogram differences
            hist_diff = self.calculate_histogram_difference(baseline, current)

            # Determine if images match
            is_match = (
                ssim_score >= self.ssim_threshold and
                diff_percentage <= self.pixel_diff_threshold
            )

            # Generate difference visualization
            diff_image_path = self.create_difference_visualization(
                baseline, current, diff, ssim_diff, name
            )

            result = {
                "name": name,
                "status": "pass" if is_match else "fail",
                "metrics": {
                    "ssim_score": float(ssim_score),
                    "pixel_diff_percentage": float(diff_percentage),
                    "histogram_difference": float(hist_diff),
                    "baseline_shape": baseline.shape,
                    "current_shape": current.shape
                },
                "thresholds": {
                    "ssim_threshold": self.ssim_threshold,
                    "pixel_diff_threshold": self.pixel_diff_threshold
                },
                "diff_image": str(diff_image_path.relative_to(self.output_dir)),
                "baseline_path": str(baseline_path),
                "current_path": str(current_path)
            }

            # Log result
            status_emoji = "✅" if is_match else "❌"
            print(f"  {status_emoji} SSIM: {ssim_score:.3f}, Diff: {diff_percentage:.2f}%")

            return result

        except Exception as e:
            return {
                "name": name,
                "status": "error",
                "error": str(e),
                "baseline_path": str(baseline_path),
                "current_path": str(current_path)
            }

    def calculate_histogram_difference(self, img1: np.ndarray, img2: np.ndarray) -> float:
        """Calculate histogram difference between two images."""
        hist1 = cv2.calcHist([img1], [0, 1, 2], None, [256, 256, 256], [0, 256, 0, 256, 0, 256])
        hist2 = cv2.calcHist([img2], [0, 1, 2], None, [256, 256, 256], [0, 256, 0, 256, 0, 256])

        return cv2.compareHist(hist1, hist2, cv2.HISTCMP_CORREL)

    def create_difference_visualization(self, baseline: np.ndarray, current: np.ndarray,
                                     diff: np.ndarray, ssim_diff: np.ndarray, name: str) -> Path:
        """Create a comprehensive difference visualization."""

        # Convert SSIM diff to heatmap
        ssim_heatmap = np.uint8(255 * (1 - ssim_diff))
        ssim_colored = cv2.applyColorMap(ssim_heatmap, cv2.COLORMAP_JET)

        # Create side-by-side comparison
        height, width = baseline.shape[:2]

        # Create combined visualization
        combined_width = width * 4  # baseline, current, diff, ssim
        combined_height = height + 100  # Extra space for labels

        combined = np.zeros((combined_height, combined_width, 3), dtype=np.uint8)

        # Place images
        combined[50:50+height, 0:width] = baseline
        combined[50:50+height, width:width*2] = current
        combined[50:50+height, width*2:width*3] = diff
        combined[50:50+height, width*3:width*4] = ssim_colored

        # Convert to PIL for text rendering
        combined_pil = Image.fromarray(cv2.cvtColor(combined, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(combined_pil)

        # Add labels
        try:
            font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 20)
        except:
            font = ImageFont.load_default()

        labels = ["Baseline", "Current", "Pixel Diff", "SSIM Diff"]
        for i, label in enumerate(labels):
            x = i * width + width // 2 - len(label) * 5
            draw.text((x, 10), label, fill=(255, 255, 255), font=font)

        # Save combined image
        diff_path = self.output_dir / f"{name}_diff.png"
        combined_pil.save(diff_path)

        return diff_path

    def generate_report(self) -> Dict:
        """Generate comprehensive test report."""
        total_tests = len(self.comparison_results)
        passed_tests = sum(1 for r in self.comparison_results if r["status"] == "pass")
        failed_tests = sum(1 for r in self.comparison_results if r["status"] == "fail")
        error_tests = sum(1 for r in self.comparison_results if r["status"] == "error")

        report = {
            "summary": {
                "total_tests": total_tests,
                "passed": passed_tests,
                "failed": failed_tests,
                "errors": error_tests,
                "pass_rate": (passed_tests / total_tests * 100) if total_tests > 0 else 0
            },
            "failed_tests": [r for r in self.comparison_results if r["status"] == "fail"],
            "error_tests": [r for r in self.comparison_results if r["status"] == "error"],
            "thresholds": {
                "ssim_threshold": self.ssim_threshold,
                "pixel_diff_threshold": self.pixel_diff_threshold,
                "color_tolerance": self.color_tolerance
            },
            "detailed_results": self.comparison_results
        }

        return report

    def save_results(self):
        """Save detailed results to files."""
        # Save JSON report
        report = self.generate_report()

        json_path = self.output_dir / "comparison_report.json"
        with open(json_path, 'w') as f:
            json.dump(report, f, indent=2)

        # Generate HTML report
        self.generate_html_report(report)

        # Generate GitHub Actions summary
        self.generate_github_summary(report)

        print(f"📊 Results saved to: {self.output_dir}")

    def generate_html_report(self, report: Dict):
        """Generate HTML report for visual review."""
        html_path = self.output_dir / "visual_regression_report.html"

        html_content = f"""
        <!DOCTYPE html>
        <html>
        <head>
            <title>CleverKeys Visual Regression Report</title>
            <style>
                body {{ font-family: Arial, sans-serif; margin: 20px; }}
                .summary {{ background: #f5f5f5; padding: 20px; border-radius: 8px; margin-bottom: 20px; }}
                .test-item {{ border: 1px solid #ddd; margin: 10px 0; padding: 15px; border-radius: 5px; }}
                .pass {{ border-left: 5px solid #4CAF50; }}
                .fail {{ border-left: 5px solid #f44336; }}
                .error {{ border-left: 5px solid #ff9800; }}
                .diff-images {{ display: flex; gap: 10px; margin: 10px 0; }}
                .diff-images img {{ max-width: 200px; border: 1px solid #ccc; }}
                .metrics {{ background: #f9f9f9; padding: 10px; border-radius: 3px; font-family: monospace; }}
            </style>
        </head>
        <body>
            <h1>🧪 CleverKeys Visual Regression Report</h1>

            <div class="summary">
                <h2>📊 Summary</h2>
                <p><strong>Total Tests:</strong> {report['summary']['total_tests']}</p>
                <p><strong>Passed:</strong> ✅ {report['summary']['passed']}</p>
                <p><strong>Failed:</strong> ❌ {report['summary']['failed']}</p>
                <p><strong>Errors:</strong> ⚠️ {report['summary']['errors']}</p>
                <p><strong>Pass Rate:</strong> {report['summary']['pass_rate']:.1f}%</p>
            </div>
        """

        # Add failed tests section
        if report['failed_tests']:
            html_content += "<h2>❌ Failed Tests</h2>"
            for test in report['failed_tests']:
                html_content += f"""
                <div class="test-item fail">
                    <h3>{test['name']}</h3>
                    <div class="metrics">
                        SSIM Score: {test['metrics']['ssim_score']:.3f} (threshold: {test['thresholds']['ssim_threshold']})
                        Pixel Diff: {test['metrics']['pixel_diff_percentage']:.2f}% (threshold: {test['thresholds']['pixel_diff_threshold']}%)
                    </div>
                    <div class="diff-images">
                        <img src="{test['diff_image']}" alt="Difference visualization">
                    </div>
                </div>
                """

        # Add passed tests section
        passed_tests = [r for r in report['detailed_results'] if r['status'] == 'pass']
        if passed_tests:
            html_content += "<h2>✅ Passed Tests</h2>"
            for test in passed_tests:
                html_content += f"""
                <div class="test-item pass">
                    <h3>{test['name']}</h3>
                    <div class="metrics">
                        SSIM Score: {test['metrics']['ssim_score']:.3f}
                        Pixel Diff: {test['metrics']['pixel_diff_percentage']:.2f}%
                    </div>
                </div>
                """

        html_content += "</body></html>"

        with open(html_path, 'w') as f:
            f.write(html_content)

    def generate_github_summary(self, report: Dict):
        """Generate GitHub Actions step summary."""
        summary_path = self.output_dir / "github_summary.md"

        summary_content = f"""
# 📸 Visual Regression Test Results

## 📊 Summary
- **Total Tests**: {report['summary']['total_tests']}
- **✅ Passed**: {report['summary']['passed']}
- **❌ Failed**: {report['summary']['failed']}
- **⚠️ Errors**: {report['summary']['errors']}
- **Pass Rate**: {report['summary']['pass_rate']:.1f}%

## 🎯 Test Thresholds
- **SSIM Threshold**: {report['thresholds']['ssim_threshold']}
- **Pixel Difference**: {report['thresholds']['pixel_diff_threshold']}%
- **Color Tolerance**: {report['thresholds']['color_tolerance']}

"""

        if report['failed_tests']:
            summary_content += "## ❌ Failed Tests\n\n"
            for test in report['failed_tests']:
                summary_content += f"### {test['name']}\n"
                summary_content += f"- **SSIM Score**: {test['metrics']['ssim_score']:.3f} (required: ≥{test['thresholds']['ssim_threshold']})\n"
                summary_content += f"- **Pixel Diff**: {test['metrics']['pixel_diff_percentage']:.2f}% (required: ≤{test['thresholds']['pixel_diff_threshold']}%)\n\n"

        if report['error_tests']:
            summary_content += "## ⚠️ Error Tests\n\n"
            for test in report['error_tests']:
                summary_content += f"### {test['name']}\n"
                summary_content += f"- **Error**: {test.get('error', 'Unknown error')}\n\n"

        summary_content += f"""
## 📁 Artifacts
- [Full HTML Report](visual_regression_report.html)
- [JSON Results](comparison_report.json)
- Difference images: `*_diff.png`

## 🔍 Review Instructions
1. Download the visual regression artifacts
2. Open `visual_regression_report.html` in a browser
3. Review failed tests and difference visualizations
4. Update baseline images if changes are intentional
"""

        with open(summary_path, 'w') as f:
            f.write(summary_content)


def main():
    parser = argparse.ArgumentParser(description="Compare screenshots for visual regression testing")
    parser.add_argument("--baseline", required=True, help="Directory containing baseline images")
    parser.add_argument("--current", required=True, help="Directory containing current images")
    parser.add_argument("--output", required=True, help="Output directory for results")
    parser.add_argument("--ssim-threshold", type=float, default=0.95, help="SSIM threshold for passing")
    parser.add_argument("--pixel-threshold", type=float, default=2.0, help="Pixel difference threshold (%)")
    parser.add_argument("--fail-on-diff", action="store_true", help="Exit with error code if differences found")

    args = parser.parse_args()

    # Create comparator
    comparator = ScreenshotComparator(args.baseline, args.current, args.output)
    comparator.ssim_threshold = args.ssim_threshold
    comparator.pixel_diff_threshold = args.pixel_threshold

    # Run comparison
    report = comparator.compare_screenshots()

    # Print summary
    print("\n🏁 Comparison Complete!")
    print(f"📊 Pass Rate: {report['summary']['pass_rate']:.1f}%")
    print(f"✅ Passed: {report['summary']['passed']}")
    print(f"❌ Failed: {report['summary']['failed']}")
    print(f"⚠️ Errors: {report['summary']['errors']}")

    # Exit with appropriate code
    if args.fail_on_diff and (report['summary']['failed'] > 0 or report['summary']['errors'] > 0):
        sys.exit(1)

    sys.exit(0)


if __name__ == "__main__":
    main()