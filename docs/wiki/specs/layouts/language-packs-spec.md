---
title: Language Packs - Technical Specification
description: Offline dictionary pack import, bounded validation, and model eligibility
user_guide: /wiki/layouts/language-packs/
status: implemented
version: v2.0.0 development
---

# Language Packs Technical Specification

`langpack/LanguagePackManager.kt` imports a ZIP from a user-selected content URI.
CleverKeys performs this work locally; the importer has no download service, remote
pack registry, layout installer, or network permission.

## Container and metadata

Required members are `manifest.json` and `dictionary.bin`. The manifest records
`code`, `name`, `version`, `author`, and `wordCount`. Optional members are
`unigrams.txt`, `contractions.json`, `NOTICE.txt`, and a declared `model.onnx`.
Legacy `prefix_boost.bin` can be retained but is not used by the current swipe engines.
Layouts are selected or imported separately.

A declared model uses a `model` object with `file` and `sha256`; only `model.onnx` is
accepted and its bytes must match that digest. An undeclared model is ignored.
Optional `license`, `attribution`, and `source` metadata are bounded to 2,000
characters each; the installed notice is read with a 64 KiB character limit.

## Bounds and validation

| Input | Limit |
|-------|-------|
| Dictionary header word count | 100,000 |
| Dictionary and ordinary member | 16 MiB each |
| Model member | 8 MiB |
| Manifest and notice member | 64 KiB each |
| Aggregate extracted bytes | 64 MiB |
| ZIP entries | 64 |

Limits apply to actual decompressed bytes and the CKDT header, not the advertised
manifest word count. Dictionary preflight checks CKDT magic, version 2, and the fixed
header's bounded count; it does not parse every dictionary record during import.
Readers enforce their own bounds when using the installed dictionary.

The importer rejects traversal, absolute paths, backslashes, NUL/dot path components,
and duplicate flattened basenames. A benign wrapper directory is allowed. Language
codes match `^[a-z]{2,3}(?:[_-][a-z0-9]{1,16}){0,4}$` and the canonical installation
path must be a direct child of `files/langpacks`.

## Installation and model eligibility

Validated content is copied into a staging directory with the manifest copied last.
Preflight or staging-copy rejection leaves the existing pack unchanged. The current
final replacement removes the old directory before renaming staging into place.

<!-- TODO: Make final directory replacement recoverable if rename fails; existing
preflight preservation tests do not establish rollback of this final swap. -->

Model import integrity and runtime execution eligibility are separate checks.
`CtcPackModel` and its registry require a separately approved model digest and suitable
language/layout geometry. A pack's self-declared hash does not authorize arbitrary
ONNX execution. Tap predictions and geometric swipe availability also depend on the
actual dictionary and supported script, rather than merely the presence of a manifest.

## Verification

`swipe/CtcImportedPackInstrumentedTest` covers real imports and runtime routing. The
oversized-update regression installs a working pack, attempts a replacement containing
100,001 words while advertising only 1,200, and verifies the old dictionary bytes and
eligibility survive rejection. Archive, dictionary-reader, and model-registry tests
cover their respective validation boundaries. See the internal
[testing strategy](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md) for full-run evidence.

Bangla National and Provat tap layouts exist. This does not provide Avro-style
transliteration, a spelling-preserving Bangla dictionary pipeline, or a validated
Bangla swipe model; those remain separate work.

[User guide](../../layouts/language-packs.md)
