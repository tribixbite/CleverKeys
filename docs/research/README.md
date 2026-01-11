# Dictionary Research Files

This folder contains research outputs from dictionary optimization analysis.

## Files

### AOSP Comparison
- `ck_not_in_aosp.csv` - Words in CleverKeys dictionary not found in AOSP dictionary
- `ck_not_in_aosp_top50k.csv` - Top 50K frequency words not in AOSP

### Quality Analysis
- `suspicious_words.csv` - Words flagged for quality review
- `potential_typos_full.csv` - Potential typos detected in dictionary
- `potential_typos_high_freq.csv` - High-frequency potential typos (priority review)
- `incorrect_compounds.csv` - Incorrectly concatenated compound words
- `mismatches_4_10.csv` - Words with 4-10 character length mismatches

### Dictionary Backups
- `en_enhanced_v1_backup.bin` - English dictionary V1 backup (binary)
- `en_enhanced_v1_backup.json` - English dictionary V1 backup (JSON)

## Purpose

These files are used for ongoing dictionary quality research and optimization.
They should not be bundled with the app but are kept for reference.
