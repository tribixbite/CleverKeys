#!/usr/bin/env python3
"""
CLI Prediction Test — decode swipe traces with the CURRENT production ONNX models.

Uses the Android model architecture (encoder input ``actual_length`` int32 scalar,
NOT a bool ``src_mask``; decoder inputs int32 ``target_tokens`` + int32
``actual_src_length``, output pre-log-softmaxed ``log_probs``; tokens a=4..z=29;
DECODER_SEQ_LENGTH=20). Verified against
``src/main/assets/models/swipe_{encoder,decoder}_android.onnx`` (2026-07-21):

    encoder  trajectory_features [b,250,6] f32 | nearest_keys [b,250] i32 | actual_length [b] i32
             -> encoder_output [b,250,256] f32
    decoder  memory [1,enc_seq,256] f32 | target_tokens [num_beams,20] i32 | actual_src_length [1] i32
             -> log_probs [num_beams,20,30] f32   (already log-softmaxed)

The decoder supports BROADCAST batched decode (``target_tokens`` shape
``[num_active_beams, 20]`` with ``memory`` staying ``[1, seq, 256]``), the same
6-8x speedup path the web_demo (``web_demo/demo/index.html`` ``stepBatched``) uses;
this runner uses it by default.

── Two run modes ──────────────────────────────────────────────────────────────
1. LEGACY smoke test (default, no ``--corpus``): the original 100-sample smoke over
   ``swype-model-training/swipes.jsonl`` in the ``{word, curve:{x,y,t}}`` shape.
2. LOCAL-CORPUS head-to-head (``--corpus PATH``): decode the gzipped
   ``{word, w, h, pts:[[nx,ny,t],...]}`` cache that the geometric replay
   (``GeoLocalCorpusReplayTest`` / ``scripts/build_local_corpus_replay.mjs``)
   consumes, apply the SAME in-dict filter (word must be in the 98,140-word en
   dictionary, ``--dict src/main/assets/dictionaries/en_enhanced.json``), and report
   neural top-1/3/5 both RAW (any beam string) and DICTIONARY-FILTERED (drop beams
   not in the dict) plus by length stratum (2-3 / 4-6 / 7+). This is the NEURAL half
   of the geometric-vs-neural head-to-head (spec ``docs/specs/geometric-swipe-engine.md``).

── CORRUPT-TIMESTAMP HANDICAP (position-only default) ─────────────────────────
The combined-English corpus has broken timestamps (t arrays reset / deltas broken),
so velocity/acceleration features computed from t actively HURT accuracy on THIS
corpus. Historical measurement: position-only 53% top-1 vs with-velocity 29%. This
runner therefore defaults to POSITION-ONLY features (velocity/accel zeroed). Pass
``--velocity`` to reconfirm the degradation on a probe (``--limit``).

── Coordinate frame ───────────────────────────────────────────────────────────
The corpus ``pts`` are normalized [0,1] over the 360x215 px ``qwerty_english`` canvas
(nx = px/360, ny = px/215). This runner reconstructs raw px (nx*360, ny*215) and then
applies the SAME model-input normalization the historical 53% reference used:
``x_norm = px/360``, ``y_norm = px/280`` (the /280 is the model-input squash band the
encoder was trained on, NOT the geometric canvas — see the geo test KDoc). Nearest
keys use the ``QWERTY_KEYS`` pixel centroids on raw px (the repo-authoritative grid,
identical to the geo test's ``QWERTY_ENGLISH_KEYS``).

Usage:
    # legacy smoke
    python3 tools/test_cli_predict.py
    # full head-to-head over the local cache (position-only, beam=8)
    python3 tools/test_cli_predict.py \
        --corpus ~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz \
        --dict src/main/assets/dictionaries/en_enhanced.json \
        --beam 8 [--limit N] [--velocity] [--out results.jsonl]

Runs under proot-distro Ubuntu (glibc) where onnxruntime loads; plain Termux
(bionic) cannot load ORT. See the env notes in the head-to-head report.
"""

import argparse
import gzip
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

# Constants matching Android/Kotlin implementation
MAX_SEQUENCE_LENGTH = 250
DECODER_SEQ_LENGTH = 20
VOCAB_SIZE = 30
PAD_IDX = 0
SOS_IDX = 2
EOS_IDX = 3
BEAM_WIDTH = 8

# Model-input coordinate-normalization band (the historical 53% reference).
# x over the 360 px canvas; y over a 280 px band the encoder was trained on.
NORM_X = 360.0
NORM_Y = 280.0

# Keyboard layout (qwerty_english) — repo-authoritative PIXEL centroids, identical to
# GeoLocalCorpusReplayTest.QWERTY_ENGLISH_KEYS and model/train_character_model.py grid.
QWERTY_KEYS = {
    'q': (18, 34), 'w': (54, 34), 'e': (90, 34), 'r': (126, 34), 't': (162, 34),
    'y': (198, 34), 'u': (234, 34), 'i': (270, 34), 'o': (306, 34), 'p': (342, 34),
    'a': (36, 93), 's': (72, 93), 'd': (108, 93), 'f': (144, 93), 'g': (180, 93),
    'h': (216, 93), 'j': (252, 93), 'k': (288, 93), 'l': (324, 93),
    'z': (72, 152), 'x': (108, 152), 'c': (144, 152), 'v': (180, 152), 'b': (216, 152),
    'n': (252, 152), 'm': (288, 152),
}

# Reverse lookup: key index to character (a=4..z=29)
KEY_IDX_TO_CHAR = ['<pad>', '<unk>', '<sos>', '<eos>'] + list('abcdefghijklmnopqrstuvwxyz')
CHAR_TO_KEY_IDX = {c: i for i, c in enumerate(KEY_IDX_TO_CHAR)}

# Authoritative normalized keyboard grid (KeyboardGrid.kt / web_demo KEYBOARD_GRID,
# post-a22b76ad). Rows q/a/z with X offsets 0.0/0.05/0.15, KEY_WIDTH=0.1,
# ROW_HEIGHT=1/3 → key centers cx=off+i*0.1+0.05, cy=1/6,1/2,5/6. Vert:horiz pitch
# ratio 3.333 (vs the legacy QWERTY_KEYS pixel grid's wrong 1.639). Matches the
# FUTO official layout (futo_qwerty.json) to <=0.0005 in normalized space.
_GRID_ROWS = [('qwertyuiop', 0.0), ('asdfghjkl', 0.05), ('zxcvbnm', 0.15)]
_KEY_W, _ROW_H = 0.1, 1.0 / 3.0
_GRID_NORM = {}
for _r, (_keys, _off) in enumerate(_GRID_ROWS):
    for _i, _c in enumerate(_keys):
        _GRID_NORM[_c] = (_off + _i * _KEY_W + _KEY_W / 2, _r * _ROW_H + _ROW_H / 2)


def get_nearest_key_norm(nx, ny):
    """KeyboardGrid.getNearestKeyToken — normalized [0,1] coords (Android parity)."""
    x = min(1.0, max(0.0, nx))
    y = min(1.0, max(0.0, ny))
    best, bd = 'a', float('inf')
    for c, (cx, cy) in _GRID_NORM.items():
        d = (x - cx) ** 2 + (y - cy) ** 2
        if d < bd:
            bd, best = d, c
    return CHAR_TO_KEY_IDX.get(best, 1)


def get_nearest_key(x, y):
    """Nearest keyboard key token index to a raw-pixel point (2D)."""
    min_dist = float('inf')
    nearest = '<unk>'
    for key, (kx, ky) in QWERTY_KEYS.items():
        dist = (x - kx) ** 2 + (y - ky) ** 2
        if dist < min_dist:
            min_dist = dist
            nearest = key
    return CHAR_TO_KEY_IDX.get(nearest, 1)  # 1 = <unk>


def extract_features(xs, ys, ts, use_velocity):
    """Extract trajectory features from raw-pixel swipe coordinates.

    Position-only by default (velocity/acceleration zeroed) because THIS corpus has
    corrupt timestamps that hurt accuracy (position-only ~53% vs velocity ~29%).
    Pass ``use_velocity=True`` to compute time-normalized deltas (the training
    formula) and reconfirm the degradation.

    Returns (trajectory_features [N,6], nearest_keys [N]).
    """
    n = len(xs)
    trajectory_features = []
    nearest_keys = []

    # Precompute velocity/accel only when requested (time-normalized, clipped to
    # [-10, 10] — matching TrajectoryFeatureCalculator / the web_demo).
    vxs = [0.0] * n
    vys = [0.0] * n
    axs = [0.0] * n
    ays = [0.0] * n
    if use_velocity:
        for i in range(1, n):
            dt = (ts[i] - ts[i - 1]) if ts is not None else 1.0
            if dt <= 0:
                dt = 1.0
            vxs[i] = _clip((xs[i] - xs[i - 1]) / NORM_X / dt)
            vys[i] = _clip((ys[i] - ys[i - 1]) / NORM_Y / dt)
        for i in range(2, n):
            dt = (ts[i] - ts[i - 1]) if ts is not None else 1.0
            if dt <= 0:
                dt = 1.0
            axs[i] = _clip((vxs[i] - vxs[i - 1]) / dt)
            ays[i] = _clip((vys[i] - vys[i - 1]) / dt)

    for i in range(n):
        x_norm = xs[i] / NORM_X
        y_norm = ys[i] / NORM_Y
        trajectory_features.append([x_norm, y_norm, vxs[i], vys[i], axs[i], ays[i]])
        nearest_keys.append(get_nearest_key(xs[i], ys[i]))

    return trajectory_features, nearest_keys


def _clip(v, lo=-10.0, hi=10.0):
    return max(lo, min(hi, v))


def resample_discard(xs, ys, ts, target=250):
    """Resample >target-point traces to `target`, preserving start+end (audit D).

    Production (SwipeResampler DISCARD, SwipeTrajectoryProcessor.kt:172-209)
    resamples long swipes to MAX_SEQUENCE_LENGTH before feature calc; the legacy
    harness HEAD-truncated, dropping the ending key. Uniform index map keeps
    endpoints (idx[0]=0, idx[-1]=n-1)."""
    n = len(xs)
    if n <= target:
        return xs, ys, ts
    idx = [round(i * (n - 1) / (target - 1)) for i in range(target)]
    rxs = [xs[j] for j in idx]
    rys = [ys[j] for j in idx]
    rts = [ts[j] for j in idx] if ts is not None else ts
    return rxs, rys, rts


def extract_features_training(nxs, nys, ts):
    """Training-EXACT feature extraction (model/train_character_model.py:160-196).

    Positions are the raw 0-1 normalized coords (xs/width, ys/height) fed DIRECTLY
    — NOT squashed through a pixel band. Velocity from index 1 using 0-1 coords;
    acceleration from index 1 using RAW (unclipped) velocity; clip v and a to
    [-10,10] AFTER both are computed. dt clamped to >=1e-6 (real ms timestamps).

    This is the production/web_demo path (TrajectoryFeatureCalculator.kt parity).
    ``nxs``/``nys`` are already 0-1 normalized (FUTO frame == training frame).

    Returns (trajectory_features [N,6], nearest_keys [N]).
    """
    n = len(nxs)
    vxs = [0.0] * n
    vys = [0.0] * n
    axs = [0.0] * n
    ays = [0.0] * n
    # dt[i] = max(t[i]-t[i-1], 1e-6); dt[0] = max(t[0]-t[0], 1e-6)=1e-6 (np.diff prepend=t[0]).
    for i in range(1, n):
        dt = max((ts[i] - ts[i - 1]) if ts is not None else 1.0, 1e-6)
        vxs[i] = (nxs[i] - nxs[i - 1]) / dt          # raw (unclipped) velocity
        vys[i] = (nys[i] - nys[i - 1]) / dt
    for i in range(1, n):                             # accel from index 1, RAW velocity
        dt = max((ts[i] - ts[i - 1]) if ts is not None else 1.0, 1e-6)
        axs[i] = (vxs[i] - vxs[i - 1]) / dt
        ays[i] = (vys[i] - vys[i - 1]) / dt
    # Clip AFTER both computed (matches np.clip order in training).
    vxs = [_clip(v) for v in vxs]
    vys = [_clip(v) for v in vys]
    axs = [_clip(a) for a in axs]
    ays = [_clip(a) for a in ays]

    trajectory_features = []
    nearest_keys = []
    for i in range(n):
        trajectory_features.append([nxs[i], nys[i], vxs[i], vys[i], axs[i], ays[i]])
        # Authoritative normalized KeyboardGrid nearest-key (pitch ratio 3.333),
        # NOT the legacy pixel grid (ratio 1.639) — fixes audit defect C.
        nearest_keys.append(get_nearest_key_norm(nxs[i], nys[i]))
    return trajectory_features, nearest_keys


def create_tensors(trajectory_features, nearest_keys):
    """Create ONNX encoder input tensors (Android architecture)."""
    actual_length = len(trajectory_features)

    traj_tensor = np.zeros((1, MAX_SEQUENCE_LENGTH, 6), dtype=np.float32)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        traj_tensor[0, i] = trajectory_features[i]

    keys_tensor = np.full((1, MAX_SEQUENCE_LENGTH), PAD_IDX, dtype=np.int32)
    for i in range(min(actual_length, MAX_SEQUENCE_LENGTH)):
        keys_tensor[0, i] = nearest_keys[i]

    actual_length_tensor = np.array([min(actual_length, MAX_SEQUENCE_LENGTH)], dtype=np.int32)
    return traj_tensor, keys_tensor, actual_length_tensor


def decode_prediction(token_indices):
    """Decode a token sequence to a lowercase word (skip specials)."""
    chars = []
    for idx in token_indices:
        if idx == SOS_IDX:
            continue
        if idx == EOS_IDX or idx == PAD_IDX:
            break
        if 4 <= idx < len(KEY_IDX_TO_CHAR):
            chars.append(KEY_IDX_TO_CHAR[idx])
    return ''.join(chars)


def run_beam_search(decoder_session, memory, actual_src_length, beam_size, max_len):
    """Broadcast-batched beam search — Android decoder.

    Mirrors the web_demo ``stepBatched`` semantics: score = SUM of step log-probs
    (higher = better); token position = min(len-1, 19); finished on EOS; finished
    beams carry forward; early-stop when all finished OR (step>=10 and >=3 finished).

    Returns list of (sequence, score) for the final beams, best-first.
    """
    actual_src_len_tensor = np.array([actual_src_length], dtype=np.int32)

    beams = [{'tokens': [SOS_IDX], 'score': 0.0, 'finished': False}]
    # Loop ceiling: the decoder input is fixed at DECODER_SEQ_LENGTH=20, so steps past
    # token-index 19 re-read the same clamped position for zero new information.
    step_cap = min(max_len, DECODER_SEQ_LENGTH - 1)

    for step in range(step_cap):
        active = [b for b in beams if not b['finished']]
        if not active:
            break
        finished_carry = [b for b in beams if b['finished']]

        # Batched target_tokens: [num_active, 20]
        batch = len(active)
        tgt = np.full((batch, DECODER_SEQ_LENGTH), PAD_IDX, dtype=np.int32)
        for b_i, beam in enumerate(active):
            for i, tok in enumerate(beam['tokens'][:DECODER_SEQ_LENGTH]):
                tgt[b_i, i] = tok

        log_probs = decoder_session.run(
            None,
            {
                'memory': memory,                       # [1, enc_seq, 256] — broadcast
                'target_tokens': tgt,                   # [num_active, 20]
                'actual_src_length': actual_src_len_tensor,  # [1]
            },
        )[0]  # -> [num_active, 20, 30]

        candidates = list(finished_carry)
        for b_i, beam in enumerate(active):
            tok_pos = min(len(beam['tokens']) - 1, DECODER_SEQ_LENGTH - 1)
            step_lp = log_probs[b_i, tok_pos]  # [30]
            top_idx = np.argpartition(step_lp, -beam_size)[-beam_size:]
            for idx in top_idx:
                idx = int(idx)
                candidates.append({
                    'tokens': beam['tokens'] + [idx],
                    'score': beam['score'] + float(step_lp[idx]),  # sum log-probs
                    'finished': idx == EOS_IDX,
                })

        candidates.sort(key=lambda b: b['score'], reverse=True)
        beams = candidates[:beam_size]

        n_finished = sum(1 for b in beams if b['finished'])
        if all(b['finished'] for b in beams) or (step >= 10 and n_finished >= 3):
            break

    return [(b['tokens'], b['score']) for b in beams]


# ══════════════════════════════════════════════════════════════════════════════
# PRODUCTION-EQUIVALENT DECODE (--production)
#
# Faithful Python port of the on-device English decode path so the neural side of
# the head-to-head runs its FULL production pipeline, not a bare encoder+beam.
# Every constant/formula below is transcribed from the Kotlin with a file:line cite.
#
# What production does for ENGLISH (the corpus language), verified in the source:
#   • BeamSearchEngine (onnx/BeamSearchEngine.kt): trie-CONSTRAINED beam search with
#     length-normalized scoring. beamWidth=6 (Config.kt:134 NEURAL_BEAM_WIDTH),
#     lengthPenaltyAlpha=1.4 (Config.kt:145 NEURAL_BEAM_ALPHA, always passed by
#     SwipePredictorOrchestrator.kt:125 — NOT the 1.0 constructor fallback),
#     temperature=1.0, confidenceThreshold=0.01 (Config.kt:136).
#   • Prefix boost is a NO-OP for English: PrefixBoostTrie.loadFromAssets("en")
#     calls unload() and returns hasBoosts()=false (PrefixBoostTrie.kt:72-77);
#     SwipePredictorOrchestrator.kt:568/595-598 only loads a boost trie when
#     primaryLang != "en" (and there is no en.bin asset — only de/es/fr/…).
#     Orchestrator passes prefixBoostTrie=null (line 467 guard hasBoosts()). So
#     (a) prefix-boost-in-loop contributes ZERO on this English corpus.
#   • strictStartChar is OFF (Config.kt:158 NEURAL_STRICT_START_CHAR=false), so (b)
#     strict start-char handling contributes zero.
#   • OptimizedVocabulary.filterPredictions (OptimizedVocabulary.kt:331) reranks the
#     beam outputs: score = (confW·NNconf + freqW·freq)·boost, then sort desc, top-10.
#     For English the swipe-stats path (firstChar/lastChar/expectedLength) is INERT:
#     production constructs SwipeInput with an EMPTY touchedKeys list
#     (InputCoordinator.kt:1304-1308, SwipeCalibrationActivity likewise), so
#     keySequence="" ⇒ firstChar/lastChar=NUL, expectedLength=0. Plus the
#     first-char prefix filter default is autocorrect_prefix_length=0 (Config.kt:189).
#     Net: no first-char gate, no expected-length gate — final branch is top-10.
#
# The remaining production levers over bare decode are therefore: (1) beam width 6
# vs 8, (2) α=1.4 length-normalized beam scoring vs plain sum, (3) the VOCAB-TRIE
# CONSTRAINT (bare mode is unconstrained), (4) the conf+freq rerank of survivors.
# ══════════════════════════════════════════════════════════════════════════════

# ── production config defaults (English), transcribed from Config.kt ─────────────
PROD_BEAM_WIDTH = 6           # Config.kt:134 NEURAL_BEAM_WIDTH
PROD_BEAM_ALPHA = 1.4         # Config.kt:145 NEURAL_BEAM_ALPHA (lengthPenaltyAlpha)
PROD_TEMPERATURE = 1.0        # Config.kt:150 NEURAL_TEMPERATURE
PROD_CONF_THRESHOLD = 0.01    # Config.kt:136 NEURAL_CONFIDENCE_THRESHOLD
PROD_ADAPTIVE_WIDTH_STEP = 12 # Config.kt:148 NEURAL_ADAPTIVE_WIDTH_STEP
PROD_ADAPTIVE_WIDTH_CONF = 0.8  # Config.kt:146 NEURAL_BEAM_PRUNE_CONFIDENCE
PROD_SCORE_GAP_STEP = 12      # Config.kt:149 NEURAL_SCORE_GAP_STEP
PROD_SCORE_GAP = 80.0         # Config.kt:147 NEURAL_BEAM_SCORE_GAP
PRUNE_STEP_THRESHOLD = 2      # BeamSearchEngine.kt:62

# filterPredictions weights (OptimizedVocabulary + Config).
# swipe_prediction_source=80 (Config.kt:198) ⇒ confidenceWeight=0.80, frequencyWeight=0.20
# (Config.kt:848-849). neural_frequency_weight=0.57 (Config.kt:151) multiplies freq weight.
PROD_CONFIDENCE_WEIGHT = 0.80
PROD_FREQUENCY_WEIGHT = 0.20
PROD_NEURAL_FREQ_WEIGHT = 0.57
# SWIPE_{COMMON,TOP5000,RARE}_* boosts all default 1.0 (Config.kt:199-201) ⇒ tier
# boost is 1.0 across the board; the rare tier additionally gates on a freq floor.
PROD_COMMON_BOOST = 1.0
PROD_TOP5000_BOOST = 1.0
PROD_RARE_PENALTY = 1.0
# autocorrect_confidence_min_frequency=100 (Config.kt:184) ⇒ /10000 (OptimizedVocabulary.kt:489)
PROD_AC_MIN_FREQ = 100
# dict-fuzzy rescue params (OptimizedVocabulary.kt, Config.kt defaults)
PROD_MAX_LENGTH_DIFF = 2      # AUTOCORRECT_MAX_LENGTH_DIFF (Config.kt:185)
PROD_PREFIX_LENGTH = 0        # AUTOCORRECT_PREFIX_LENGTH (Config.kt:189) — first-char gate OFF
PROD_MIN_WORD_LENGTH = 2      # AUTOCORRECT_MIN_WORD_LENGTH (Config.kt:179)
PROD_CHAR_MATCH_THRESHOLD = 0.65  # AUTOCORRECT_CHAR_MATCH_THRESHOLD (Config.kt:183)
PROD_MAX_BEAM_CANDIDATES = 3  # AUTOCORRECT_MAX_BEAM_CANDIDATES (Config.kt:190)
PROD_SWIPE_AUTOCORRECT = True # SWIPE_BEAM_AUTOCORRECT_ENABLED (Config.kt:191)

# Character token layout: a=4..z=29 (same as KEY_IDX_TO_CHAR above).
IDX_TO_CHAR = {i: c for i, c in enumerate('abcdefghijklmnopqrstuvwxyz', start=4)}


class VocabTrie:
    """Prefix trie mirroring VocabularyTrie.kt (getAllowedNextChars / containsWord).

    Nodes are ``{char: child_dict}`` with a sentinel ``'$'`` marking end-of-word,
    exactly the lookups BeamSearchEngine.applyTrieMasking performs. Stores lowercase.
    """
    __slots__ = ('root',)
    _END = '$'

    def __init__(self):
        self.root = {}

    def insert(self, word):
        if not word:
            return
        node = self.root
        for ch in word.lower():
            node = node.setdefault(ch, {})
        node[self._END] = True

    def insert_all(self, words):
        for w in words:
            self.insert(w)

    def _walk(self, prefix):
        node = self.root
        for ch in prefix.lower():
            node = node.get(ch)
            if node is None:
                return None
        return node

    def allowed_next_chars(self, prefix):
        """VocabularyTrie.getAllowedNextChars — child keys of the prefix node (empty if
        prefix absent). Excludes the end-of-word sentinel."""
        node = self._walk(prefix)
        if node is None:
            return frozenset()
        return frozenset(k for k in node.keys() if k != self._END)

    def contains_word(self, prefix):
        """VocabularyTrie.containsWord — prefix node exists AND is end-of-word."""
        node = self._walk(prefix)
        return node is not None and node.get(self._END, False) is True


class ProdVocab:
    """Port of OptimizedVocabulary for English: frequency normalization, tiers, the
    conf+freq rerank of filterPredictions, and the dict-fuzzy rescue. Built from the
    flat ``en_enhanced.json`` — the exact asset the APK loads (loadWordFrequencies,
    OptimizedVocabulary.kt:970)."""

    def __init__(self, raw_freqs):
        # raw_freqs: {word: int_freq} straight from en_enhanced.json (values 128..255).
        # Mirror loadWordFrequencies (OptimizedVocabulary.kt:984-1027): keep words
        # matching ^[\p{L}'-]+$, sort by raw freq desc, assign tiers by rank, and
        # normalize freq = ((raw-128)/127).coerceAtLeast(0.001).
        import re
        word_re = re.compile(r"^[^\W\d_]+['-]?[^\W\d_]*$|^[a-z'-]+$")
        entries = []
        for w, f in raw_freqs.items():
            wl = w.lower()
            # Kotlin regex is ^[\p{L}'-]+$ (letters, apostrophe, hyphen). Approximate
            # with an alpha/'/'- filter; the corpus targets are a-z so this is exact
            # for anything that can be a decode target.
            if all(c.isalpha() or c in "'-" for c in wl) and wl:
                entries.append((wl, int(f)))
        entries.sort(key=lambda kv: kv[1], reverse=True)

        self.vocab = {}                # word -> (frequency_float, tier_byte)
        self.by_length = {}            # len -> [words]  (for dict-fuzzy buckets)
        self.trie = VocabTrie()
        for rank, (word, raw) in enumerate(entries[:150000]):
            freq = max((raw - 128) / 127.0, 0.001)          # OptimizedVocabulary.kt:1006
            if rank < 100:
                tier = 2   # common (top 100)
            elif rank < 3000:
                tier = 1   # top3000
            else:
                tier = 0   # regular
            self.vocab[word] = (freq, tier)
            self.trie.insert(word)
            self.by_length.setdefault(len(word), []).append(word)

        # minFrequencyByLength — initializeFrequencyThresholds (OptimizedVocabulary.kt:1079)
        self.min_freq_by_len = {
            1: 1e-4, 2: 1e-5, 3: 1e-6, 4: 1e-6, 5: 1e-7, 6: 1e-7, 7: 1e-8, 8: 1e-8,
        }
        for i in range(9, 21):
            self.min_freq_by_len[i] = 1e-9

    def _min_freq(self, length):
        return self.min_freq_by_len.get(length, 1e-9)

    @staticmethod
    def _combined_score(confidence, frequency, boost, conf_w, freq_w):
        # VocabularyUtils.calculateCombinedScore (VocabularyUtils.kt:13-25)
        return (conf_w * confidence + freq_w * frequency) * boost

    @staticmethod
    def _levenshtein(s1, s2):
        # VocabularyUtils.calculateLevenshteinDistance (VocabularyUtils.kt:71)
        if s1 == s2:
            return 0
        l1, l2 = len(s1), len(s2)
        if l1 == 0:
            return l2
        if l2 == 0:
            return l1
        prev = list(range(l2 + 1))
        for i in range(1, l1 + 1):
            cur = [i] + [0] * l2
            for j in range(1, l2 + 1):
                cost = 0 if s1[i - 1] == s2[j - 1] else 1
                cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            prev = cur
        return prev[l2]

    @classmethod
    def _match_quality(cls, dict_word, beam_word):
        # calculateMatchQuality(useEditDistance=true) — SWIPE_FUZZY_MATCH_MODE default
        # is "edit_distance" (Config.kt:197) ⇒ Levenshtein branch (VocabularyUtils.kt:106).
        dist = cls._levenshtein(dict_word, beam_word)
        maxd = max(len(dict_word), len(beam_word))
        if maxd == 0:
            return 1.0
        return 1.0 - (dist / maxd)

    @staticmethod
    def _fuzzy_match(w1, w2, char_thresh, max_len_diff, prefix_len, min_len):
        # VocabularyUtils.fuzzyMatch (VocabularyUtils.kt:31)
        if len(w1) < min_len or len(w2) < min_len:
            return False
        if abs(len(w1) - len(w2)) > max_len_diff:
            return False
        actual_prefix = min(prefix_len, len(w1), len(w2))
        if actual_prefix > 0 and w1[:actual_prefix] != w2[:actual_prefix]:
            return False
        min_len_ab = min(len(w1), len(w2))
        matches = sum(1 for i in range(min_len_ab) if w1[i] == w2[i])
        return (matches / min_len_ab) >= char_thresh

    def filter_predictions(self, raw_predictions):
        """Port of OptimizedVocabulary.filterPredictions for English, swipe-stats inert.

        raw_predictions: list of (word, confidence) — the beam outputs (word lowercase
        a-z, confidence = length-normalized exp(-normalizedScore) from convertToCandidate).
        Returns the reranked, deduped list of (word, score) capped at 10 (top-K).
        """
        conf_w = PROD_CONFIDENCE_WEIGHT
        eff_freq_w = PROD_FREQUENCY_WEIGHT * PROD_NEURAL_FREQ_WEIGHT
        config_norm_min_freq = max(0.0, PROD_AC_MIN_FREQ / 10000.0)  # OptimizedVocabulary.kt:489

        valid = []  # (word, score, confidence, frequency, source)
        for word, confidence in raw_predictions:
            w = word.lower().strip()
            if not w or any(c < 'a' or c > 'z' for c in w):
                continue  # ^[a-z]+$ (OptimizedVocabulary.kt:382)
            # First-char prefix gate: prefixLength=0 ⇒ skipped (OptimizedVocabulary.kt:389)
            info = self.vocab.get(w)
            if info is None:
                continue  # not in vocabulary (OptimizedVocabulary.kt:452)
            freq, tier = info
            if tier == 2:
                boost = PROD_COMMON_BOOST
                source = "common"
            elif tier == 1:
                boost = PROD_TOP5000_BOOST
                source = "top5000"
            else:
                hardcoded_min = self._min_freq(len(w))
                effective_min = max(hardcoded_min, config_norm_min_freq)
                if freq < effective_min:
                    continue  # below frequency threshold (OptimizedVocabulary.kt:495)
                boost = PROD_RARE_PENALTY
                source = "vocabulary"
            score = self._combined_score(confidence, freq, boost, conf_w, eff_freq_w)
            valid.append([w, score, confidence, freq, source])

        # sort by combined score desc (OptimizedVocabulary.kt:537)
        valid.sort(key=lambda p: p[1], reverse=True)

        # MAIN DICTIONARY FUZZY MATCHING — rescue rejected beam outputs when we have
        # fewer than 3 valid predictions (OptimizedVocabulary.kt:600-730). Custom-word
        # autocorrect is skipped (no custom words in eval). Runs for English.
        if PROD_SWIPE_AUTOCORRECT and len(valid) < 3 and raw_predictions:
            valid_words = {p[0] for p in valid}
            for i in range(min(PROD_MAX_BEAM_CANDIDATES, len(raw_predictions))):
                beam_word = raw_predictions[i][0].lower().strip()
                beam_conf = raw_predictions[i][1]
                if beam_word in self.vocab:
                    continue  # already passed filtering
                target_len = len(beam_word)
                best_match = None
                best_score = 0.0
                lo = max(1, target_len - PROD_MAX_LENGTH_DIFF)
                hi = target_len + PROD_MAX_LENGTH_DIFF
                for length in range(lo, hi + 1):
                    for dict_word in self.by_length.get(length, ()):  # noqa: bucket scan
                        dinfo = self.vocab.get(dict_word)
                        if dinfo is None:
                            continue
                        if not self._fuzzy_match(dict_word, beam_word, PROD_CHAR_MATCH_THRESHOLD,
                                                 PROD_MAX_LENGTH_DIFF, PROD_PREFIX_LENGTH, PROD_MIN_WORD_LENGTH):
                            continue
                        dfreq, dtier = dinfo
                        boost = PROD_COMMON_BOOST if dtier == 2 else (PROD_TOP5000_BOOST if dtier == 1 else PROD_RARE_PENALTY)
                        mq = self._match_quality(dict_word, beam_word)
                        match_power = mq * mq * mq  # cubic (OptimizedVocabulary.kt:674)
                        base = (conf_w * beam_conf) + (eff_freq_w * dfreq)
                        base *= 0.8  # rescue penalty (OptimizedVocabulary.kt:678)
                        s = base * match_power * boost
                        if s > best_score:
                            best_score = s
                            best_match = dict_word
                            best_freq = dfreq
                # prefixLength=0 ⇒ re-check first-char gate skipped (OptimizedVocabulary.kt:696)
                if best_match is not None and best_match not in valid_words:
                    valid.append([best_match, best_score, beam_conf, best_freq, "dict-fuzzy"])
                    valid_words.add(best_match)
            valid.sort(key=lambda p: p[1], reverse=True)

        # Deduplicate lowercase-keep-best + top-K=10, mirroring PostProcessor + the
        # expectedLength=0 branch (OptimizedVocabulary.kt:945, subList(0, min(size,10))).
        seen = {}
        for w, s, _c, _f, _src in valid:
            if w not in seen or s > seen[w]:
                seen[w] = s
        ordered = sorted(seen.items(), key=lambda kv: kv[1], reverse=True)
        return ordered[:10]


def _prod_norm_factor(length, alpha):
    """Length normalization used by BOTH beam sort and convertToCandidate
    (BeamSearchEngine.kt:170, :567): (5+len)^alpha / 6^alpha."""
    return ((5.0 + length) ** alpha) / (6.0 ** alpha)


def _log_softmax(logits):
    """BeamSearchEngine.logSoftmax at temperature 1.0 — numerically stable.
    ``logits`` may contain -inf for masked tokens; those map to -inf log-probs."""
    m = np.max(logits)
    if not np.isfinite(m):
        # all masked — degenerate; return as-is (caller top-K skips -inf)
        return logits
    shifted = logits - m
    sum_exp = np.sum(np.exp(shifted))
    log_sum_exp = m + np.log(sum_exp)
    return logits - log_sum_exp


def run_production_beam(decoder_session, memory, actual_src_length, trie, beam_width, max_len, alpha):
    """Trie-CONSTRAINED, length-normalized beam search — faithful port of
    BeamSearchEngine.search for English (prefix boost & strictStartChar inert).

    Sequential decode: one decoder call per active beam per step (production default
    is batchBeams=false, Config.kt:137 NEURAL_BATCH_BEAMS). We use the broadcast-batched
    decoder call purely as a speed optimization — it returns numerically identical
    per-position logits, and the topK/scoring below is done per-beam exactly as Kotlin's
    processLogitsForBeam does.

    Score is accumulated NEGATIVE log-likelihood (lower = better), matching Kotlin.
    Returns list of (word, normalized_confidence) best-first (post convertToCandidate).
    """
    actual_src_len_tensor = np.array([actual_src_length], dtype=np.int32)

    # BeamState: tokens, score(NLL, lower better), finished
    beams = [{'tokens': [SOS_IDX], 'score': 0.0, 'finished': False}]
    step = 0
    step_cap = min(max_len, DECODER_SEQ_LENGTH - 1)

    while step < step_cap:
        active = [b for b in beams if not b['finished']]
        finished = [dict(b) for b in beams if b['finished']]
        if not active:
            break

        # Batched decoder call for all active beams (broadcast memory).
        batch = len(active)
        tgt = np.full((batch, DECODER_SEQ_LENGTH), PAD_IDX, dtype=np.int32)
        for b_i, beam in enumerate(active):
            for i, tok in enumerate(beam['tokens'][:DECODER_SEQ_LENGTH]):
                tgt[b_i, i] = tok
        log_probs_out = decoder_session.run(
            None,
            {'memory': memory, 'target_tokens': tgt, 'actual_src_length': actual_src_len_tensor},
        )[0]  # [batch, 20, 30] — ALREADY log-softmaxed by the model

        candidates = list(finished)  # carry finished beams forward (BeamSearchEngine.kt:112)
        for b_i, beam in enumerate(active):
            cur_pos = len(beam['tokens']) - 1
            if not (0 <= cur_pos < DECODER_SEQ_LENGTH):
                continue
            # Reconstruct the model's per-position LOGITS is not needed: the decoder
            # already emits log_probs. But applyTrieMasking masks pre-softmax logits and
            # re-softmaxes. Masking a token to -inf and re-normalizing over the surviving
            # set is exactly: take the model log_probs, set masked entries to -inf, then
            # renormalize (log_probs - logsumexp(surviving)). That equals BeamSearchEngine's
            # logSoftmax over masked logits because softmax is invariant to the shared
            # additive logsumexp constant the model already subtracted. So we operate on
            # log_probs directly. (Prefix boost would break this equivalence, but it is a
            # no-op for English — see header.)
            lp = log_probs_out[b_i, cur_pos].astype(np.float64).copy()  # [30]

            # ── applyTrieMasking (BeamSearchEngine.kt:367) ──
            prefix = ''.join(IDX_TO_CHAR[t] for t in beam['tokens'] if t in IDX_TO_CHAR)
            allowed = trie.allowed_next_chars(prefix)
            is_word = trie.contains_word(prefix)
            masked = np.full(VOCAB_SIZE, -np.inf, dtype=np.float64)
            # SOS/PAD always masked; EOS only if prefix is a word; char tokens if allowed
            if is_word:
                masked[EOS_IDX] = lp[EOS_IDX]
            for idx, ch in IDX_TO_CHAR.items():
                if ch in allowed:
                    masked[idx] = lp[idx]

            # Re-softmax over surviving logits (log-domain renormalization).
            re_lp = _log_softmax(masked)

            # Top-K over surviving (BeamSearchEngine.getTopKIndices skips -inf).
            finite = np.where(np.isfinite(re_lp))[0]
            if finite.size == 0:
                continue
            k = min(beam_width, finite.size)
            top = finite[np.argsort(re_lp[finite])[-k:]]  # ascending; take k largest
            for idx in top:
                idx = int(idx)
                if idx in (SOS_IDX, PAD_IDX):
                    continue
                new_beam = {
                    'tokens': beam['tokens'] + [idx],
                    'score': beam['score'] + float(-re_lp[idx]),  # accumulate NLL
                    'finished': idx == EOS_IDX,
                }
                candidates.append(new_beam)

        # ── ranking/pruning (BeamSearchEngine.kt:167) ──
        # length-normalized sort (lower score/normFactor = better)
        candidates.sort(key=lambda b: b['score'] / _prod_norm_factor(len(b['tokens']), alpha))

        # low-probability prune after PRUNE_STEP_THRESHOLD (BeamSearchEngine.kt:175)
        if step >= PRUNE_STEP_THRESHOLD:
            candidates = [b for b in candidates if np.exp(-b['score']) >= 1e-6]

        # dedup by token sequence, keep top beam_width (BeamSearchEngine.kt:180)
        beams = []
        seen_seqs = set()
        for cand in candidates:
            if len(beams) >= beam_width:
                break
            seq = tuple(cand['tokens'])
            if seq not in seen_seqs:
                seen_seqs.add(seq)
                beams.append(cand)

        # adaptive width reduction at adaptiveWidthStep (BeamSearchEngine.kt:197)
        if step == PROD_ADAPTIVE_WIDTH_STEP and len(beams) > 3:
            top_score = beams[0]['score']
            if np.exp(-top_score) > PROD_ADAPTIVE_WIDTH_CONF:
                beams = beams[:3]

        # score-gap early stop (BeamSearchEngine.kt:207)
        if len(beams) >= 2 and step >= PROD_SCORE_GAP_STEP:
            gap = beams[1]['score'] - beams[0]['score']
            if beams[0]['finished'] and gap > PROD_SCORE_GAP:
                break

        # all-finished check (BeamSearchEngine.kt:219)
        n_fin = sum(1 for b in beams if b['finished'])
        if all(b['finished'] for b in beams) or n_fin >= beam_width:
            break
        step += 1

    # ── convertToCandidate (BeamSearchEngine.kt:534): length-normalized confidence + threshold ──
    out = []
    for beam in beams:
        word = ''.join(IDX_TO_CHAR[t] for t in beam['tokens'] if t in IDX_TO_CHAR)
        if not word:
            continue
        norm_factor = _prod_norm_factor(len(word), alpha)
        confidence = float(np.exp(-beam['score'] / norm_factor))
        if confidence < PROD_CONF_THRESHOLD:
            continue
        out.append((word, confidence, beam['score']))
    # best-first: production returns beams in their sorted order; sort by normalized conf desc
    out.sort(key=lambda t: t[1], reverse=True)
    return [(w, c) for (w, c, _s) in out]


# ── corpus loaders ──────────────────────────────────────────────────────────────

def load_legacy_swipes(path):
    """Legacy ``{word, curve:{x,y,t}}`` JSONL (raw pixels). Yields (word, xs, ys, ts)."""
    out = []
    with open(path, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            c = obj['curve']
            out.append((obj['word'], list(c['x']), list(c['y']), list(c.get('t', []))))
    return out


def load_corpus_cache(path, frame_remap=None):
    """Gzipped ``{word, w, h, pts:[[nx,ny,t],...]}`` cache (normalized coords).

    Default: reconstructs raw px = nx*w, ny*h so the model-input normalization band
    applies the same way as on the legacy raw-pixel corpus (the LOCAL corpus canvases
    are already the model's ~360x189 frame). Yields (word, xs_px, ys_px, ts).

    ``frame_remap='futo'``: FUTO rows carry per-DEVICE canvas px (e.g. 1080-wide) but
    the model expects the fixed 360-wide QWERTY frame (QWERTY_KEYS: q=18..p=342 ->
    x = 360*nx exactly; rows at y=34/93/152 with pitch 59 -> letter area spans
    [4.5, 181.5] -> y = 4.5 + 177*ny). Ignores stored w/h for coordinates (they
    remain provenance) and maps normalized pts straight into the model frame.
    """
    opener = gzip.open if str(path).endswith('.gz') else open
    out = []
    with opener(path, 'rt') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            w = float(obj['w'])
            h = float(obj['h'])
            xs, ys, ts = [], [], []
            for p in obj['pts']:
                nx = float(p[0])
                ny = float(p[1])
                if frame_remap == 'identity':
                    # Pass the raw 0-1 normalized coords straight through (training
                    # frame == FUTO frame: xs/width, ys/height). Consumed by
                    # extract_features_training, which does NOT divide by NORM_*.
                    xs.append(nx)
                    ys.append(ny)
                elif frame_remap == 'futo':
                    xs.append(nx * 360.0)
                    ys.append(4.5 + ny * 177.0)
                else:
                    xs.append(nx * w)
                    ys.append(ny * h)
                ts.append(float(p[2]) if len(p) > 2 else 0.0)
            out.append((obj['word'], xs, ys, ts))
    return out


def load_dictionary(path):
    """Load the flat ``{word: score}`` en_enhanced.json into a lowercase word set."""
    with open(path, 'r') as f:
        data = json.load(f)
    return {k.lower() for k in data.keys()}


def load_dictionary_raw(path):
    """Load the flat ``{word: int_freq}`` en_enhanced.json unchanged (for ProdVocab).

    Values are the raw 128..255 frequency bytes the APK's loadWordFrequencies consumes.
    """
    with open(path, 'r') as f:
        return json.load(f)


def len_stratum(word):
    n = len(word.replace("'", ""))
    if n <= 3:
        return '2-3'
    if n <= 6:
        return '4-6'
    return '7+'


class Tally:
    """Top-K tally over ranks (0-based; -1 = absent)."""
    __slots__ = ('n', 't1', 't3', 't5')

    def __init__(self):
        self.n = self.t1 = self.t3 = self.t5 = 0

    def add(self, rank):
        self.n += 1
        if 0 <= rank < 1:
            self.t1 += 1
        if 0 <= rank < 3:
            self.t3 += 1
        if 0 <= rank < 5:
            self.t5 += 1

    def top1(self):
        return self.t1 / self.n if self.n else 0.0

    def top3(self):
        return self.t3 / self.n if self.n else 0.0

    def top5(self):
        return self.t5 / self.n if self.n else 0.0

    def row(self):
        return f"{self.top1() * 100:5.1f}%  {self.top3() * 100:5.1f}%  {self.top5() * 100:5.1f}%"


def make_session(path, threads):
    """ONNX session with explicit thread count (silences pthread affinity warnings
    under proot) and CPU provider."""
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    return ort.InferenceSession(str(path), sess_options=so, providers=['CPUExecutionProvider'])


def rank_of(target, words):
    """0-based rank of ``target`` in ``words`` (dedup-preserving), or -1."""
    for i, w in enumerate(words):
        if w == target:
            return i
    return -1


def dedup(words):
    """Order-preserving dedup (raw beam outputs can repeat)."""
    seen = set()
    out = []
    for w in words:
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out


def run_head_to_head(args):
    root = Path(__file__).resolve().parents[1]
    encoder_path = root / "src/main/assets/models/swipe_encoder_android.onnx"
    decoder_path = root / "src/main/assets/models/swipe_decoder_android.onnx"
    for p in (encoder_path, decoder_path):
        if not p.exists():
            print(f"ERROR: model not found: {p}")
            return 1

    corpus_path = Path(args.corpus).expanduser()
    if not corpus_path.exists():
        print(f"ERROR: corpus not found: {corpus_path}")
        return 1
    dict_path = Path(args.dict).expanduser()
    if not dict_path.exists():
        print(f"ERROR: dictionary not found: {dict_path}")
        return 1

    print("=" * 78)
    print("NEURAL HEAD-TO-HEAD — local combined-corpus (neural held-out test set)")
    print("=" * 78)
    print(f"encoder: {encoder_path}")
    print(f"decoder: {decoder_path}")
    print(f"corpus:  {corpus_path}")
    print(f"dict:    {dict_path}")

    encoder = make_session(encoder_path, args.threads)
    decoder = make_session(decoder_path, args.threads)

    # Signature sanity — abort loudly on drift.
    enc_inputs = {i.name for i in encoder.get_inputs()}
    if 'actual_length' not in enc_inputs:
        print(f"ERROR: encoder signature drift — expected 'actual_length', got {enc_inputs}")
        return 1
    dec_inputs = {i.name for i in decoder.get_inputs()}
    if not {'memory', 'target_tokens', 'actual_src_length'} <= dec_inputs:
        print(f"ERROR: decoder signature drift — got {dec_inputs}")
        return 1

    dictionary = load_dictionary(dict_path)
    print(f"dictionary: {len(dictionary)} words")

    # PRODUCTION mode: build the on-device vocabulary (trie + tiers + freq norm) so the
    # neural side runs its full pipeline (trie-constrained beam + conf/freq rerank).
    prod_vocab = None
    if args.production:
        pt0 = time.time()
        prod_vocab = ProdVocab(load_dictionary_raw(dict_path))
        print(f"ProdVocab built: {len(prod_vocab.vocab)} words, "
              f"trie+tiers in {time.time() - pt0:.1f}s")

    rows = load_corpus_cache(corpus_path, frame_remap=args.frame_remap)
    print(f"corpus rows: {len(rows)}")

    in_dict = [r for r in rows if r[0] in dictionary]
    oov = len(rows) - len(in_dict)
    coverage = len(in_dict) / len(rows) if rows else 0.0
    print(f"in-dict: {len(in_dict)}/{len(rows)} = {coverage * 100:.1f}%  (OOV={oov})")

    if args.skip:
        in_dict = in_dict[args.skip:]
        print(f"SKIP applied: starting at in-dict trace {args.skip}")
    if args.limit:
        in_dict = in_dict[:args.limit]
        print(f"LIMIT applied: decoding {len(in_dict)} in-dict traces")

    if args.training_features:
        feat_mode = "TRAINING-EXACT (0-1 pos + index-1 accel, raw velocity — production-faithful)"
    else:
        feat_mode = "velocity+accel (time-normalized)" if args.velocity else "position-only (velocity/accel ZEROED)"
    print(f"features: {feat_mode}   beam={args.beam}   max_len={args.max_len}")
    if args.production:
        print(f"PRODUCTION mode ON: trie-constrained beam (width={PROD_BEAM_WIDTH}, "
              f"alpha={PROD_BEAM_ALPHA}) + filterPredictions rerank; bare decode also run for delta")
    print("-" * 78)

    raw = Tally()
    filt = Tally()
    raw_by_len = {'2-3': Tally(), '4-6': Tally(), '7+': Tally()}
    filt_by_len = {'2-3': Tally(), '4-6': Tally(), '7+': Tally()}
    # production tallies (only used when --production)
    prod = Tally()
    prod_by_len = {'2-3': Tally(), '4-6': Tally(), '7+': Tally()}

    out_f = None
    if args.out:
        out_f = open(Path(args.out).expanduser(), 'w')

    t0 = time.time()
    errors = 0
    for i, (word, xs, ys, ts) in enumerate(in_dict):
        try:
            if args.training_features:
                # Resample >250-pt traces preserving start+end (audit D) before feats.
                rxs, rys, rts = resample_discard(xs, ys, ts, MAX_SEQUENCE_LENGTH)
                traj, keys = extract_features_training(rxs, rys, rts)
            else:
                traj, keys = extract_features(xs, ys, ts if args.velocity else None, args.velocity)
            traj_t, keys_t, alen_t = create_tensors(traj, keys)
            actual_length = int(alen_t[0])

            memory = encoder.run(
                None,
                {'trajectory_features': traj_t, 'nearest_keys': keys_t, 'actual_length': alen_t},
            )[0]

            beams = run_beam_search(decoder, memory, actual_length, args.beam, args.max_len)
            raw_words = dedup([decode_prediction(seq) for seq, _ in beams])
            filt_words = [w for w in raw_words if w in dictionary]

            r_raw = rank_of(word, raw_words)
            r_filt = rank_of(word, filt_words)
            stratum = len_stratum(word)

            raw.add(r_raw)
            filt.add(r_filt)
            raw_by_len[stratum].add(r_raw)
            filt_by_len[stratum].add(r_filt)

            prod_words = None
            r_prod = -1
            if prod_vocab is not None:
                # Trie-constrained, length-normalized beam (production width/alpha)
                prod_beams = run_production_beam(
                    decoder, memory, actual_length, prod_vocab.trie,
                    PROD_BEAM_WIDTH, args.max_len, PROD_BEAM_ALPHA)
                # conf+freq rerank -> deduped top-10 (word, score)
                reranked = prod_vocab.filter_predictions(prod_beams)
                prod_words = [w for w, _ in reranked]
                r_prod = rank_of(word, prod_words)
                prod.add(r_prod)
                prod_by_len[stratum].add(r_prod)

            if out_f is not None:
                rec = {
                    'word': word, 'len_stratum': stratum,
                    'raw_top5': raw_words[:5], 'raw_rank': r_raw,
                    'filt_top5': filt_words[:5], 'filt_rank': r_filt,
                }
                if prod_words is not None:
                    rec['prod_top5'] = prod_words[:5]
                    rec['prod_rank'] = r_prod
                out_f.write(json.dumps(rec) + "\n")
        except Exception as e:  # noqa: BLE001 — report, keep going
            errors += 1
            raw.add(-1)
            filt.add(-1)
            raw_by_len[len_stratum(word)].add(-1)
            filt_by_len[len_stratum(word)].add(-1)
            if prod_vocab is not None:
                prod.add(-1)
                prod_by_len[len_stratum(word)].add(-1)
            if errors <= 5:
                print(f"  [ERROR] '{word}': {e}")

        if (i + 1) % 500 == 0:
            elapsed = time.time() - t0
            rate = (i + 1) / elapsed
            eta = (len(in_dict) - (i + 1)) / rate if rate else 0
            prod_str = f" prod t1={prod.top1() * 100:.1f}%" if prod_vocab is not None else ""
            print(f"  [{i + 1:5d}/{len(in_dict)}] "
                  f"raw t1={raw.top1() * 100:.1f}% filt t1={filt.top1() * 100:.1f}%{prod_str} "
                  f"| {rate:.1f} tr/s ETA {eta / 60:.1f}m")

    if out_f is not None:
        out_f.close()

    elapsed = time.time() - t0
    print("-" * 78)
    print(f"decoded {raw.n} traces in {elapsed / 60:.1f} min  ({errors} errors)")
    print()
    print(f"features: {feat_mode}   beam={args.beam}")
    print("                 top-1    top-3    top-5")
    print(f"  RAW (any string) {raw.row()}")
    print(f"  DICT-FILTERED    {filt.row()}")
    if prod_vocab is not None:
        print(f"  PRODUCTION       {prod.row()}   <- trie-constrained beam(w={PROD_BEAM_WIDTH}) "
              f"+ conf/freq rerank")
    print()
    print("by length stratum (top-1 / top-3 / top-5):")
    if prod_vocab is not None:
        print("  stratum    RAW                     DICT-FILTERED           PRODUCTION")
        for s in ('2-3', '4-6', '7+'):
            rb = raw_by_len[s]
            fb = filt_by_len[s]
            pb = prod_by_len[s]
            print(f"  {s:<4} n={rb.n:<5} {rb.row()}    {fb.row()}    {pb.row()}")
        print("=" * 78)
        return 0
    print("  stratum    RAW                     DICT-FILTERED")
    for s in ('2-3', '4-6', '7+'):
        rb = raw_by_len[s]
        fb = filt_by_len[s]
        print(f"  {s:<4} n={rb.n:<5} {rb.row()}    {fb.row()}")
    print("=" * 78)
    return 0


def run_legacy_smoke(args):
    root = Path(__file__).resolve().parents[1]
    encoder_path = root / "src/main/assets/models/swipe_encoder_android.onnx"
    decoder_path = root / "src/main/assets/models/swipe_decoder_android.onnx"
    swipes_path = root / "swype-model-training/swipes.jsonl"
    for p, label in ((encoder_path, 'encoder'), (decoder_path, 'decoder'), (swipes_path, 'test data')):
        if not p.exists():
            print(f"ERROR: {label} not found at {p}")
            return 1

    encoder = make_session(encoder_path, args.threads)
    decoder = make_session(decoder_path, args.threads)
    print("Encoder inputs:", [i.name for i in encoder.get_inputs()])
    if 'actual_length' not in {i.name for i in encoder.get_inputs()}:
        print("VALIDATION FAILED: expected Android model with actual_length input")
        return 1
    print("VALIDATION PASSED: Android model architecture (actual_length)")

    swipes = load_legacy_swipes(swipes_path)
    limit = min(args.limit or 100, len(swipes))
    print(f"Running {limit} legacy smoke samples")
    tally = Tally()
    for i, (word, xs, ys, ts) in enumerate(swipes[:limit]):
        try:
            traj, keys = extract_features(xs, ys, ts if args.velocity else None, args.velocity)
            traj_t, keys_t, alen_t = create_tensors(traj, keys)
            memory = encoder.run(
                None,
                {'trajectory_features': traj_t, 'nearest_keys': keys_t, 'actual_length': alen_t},
            )[0]
            beams = run_beam_search(decoder, memory, int(alen_t[0]), args.beam, args.max_len)
            words = dedup([decode_prediction(seq) for seq, _ in beams])
            tally.add(rank_of(word, words))
        except Exception as e:  # noqa: BLE001
            print(f"  [{i + 1}] '{word}' ERROR: {e}")
            tally.add(-1)
    print(f"Top-1/3/5: {tally.row()}  (n={tally.n})")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--corpus', help='gzipped {word,w,h,pts} cache — enables head-to-head mode')
    ap.add_argument('--frame-remap', choices=['futo', 'identity'], default=None, dest='frame_remap',
                    help='map normalized pts into the model frame '
                         '(futo: x=360*nx, y=4.5+177*ny squashed by NORM; '
                         'identity: pass raw 0-1 coords for --training-features)')
    ap.add_argument('--training-features', action='store_true', dest='training_features',
                    help='training-EXACT features (0-1 pos fed directly, index-1 accel, '
                         'raw velocity; requires --frame-remap identity). Production-faithful.')
    ap.add_argument('--dict', default='src/main/assets/dictionaries/en_enhanced.json',
                    help='flat {word:score} dictionary for the in-dict filter')
    ap.add_argument('--beam', type=int, default=BEAM_WIDTH, help='beam width (default 8)')
    ap.add_argument('--max-len', type=int, default=DECODER_SEQ_LENGTH, dest='max_len',
                    help='max decode length (capped at 19 internally)')
    ap.add_argument('--velocity', action='store_true',
                    help='use velocity/accel features (reconfirm the corrupt-timestamp degradation)')
    ap.add_argument('--production', action='store_true',
                    help='ALSO run the production-equivalent decode: trie-constrained '
                         'length-normalized beam (width=6, alpha=1.4) + OptimizedVocabulary '
                         'conf/freq rerank. Reports a PRODUCTION column alongside bare RAW/DICT.')
    ap.add_argument('--limit', type=int, default=0, help='decode only first N in-dict traces (0=all)')
    ap.add_argument('--skip', type=int, default=0, help='skip first N in-dict traces (chunked runs)')
    ap.add_argument('--threads', type=int, default=4, help='ORT intra-op threads')
    ap.add_argument('--out', help='write per-trace results jsonl (LOCAL-ONLY artifact)')
    args = ap.parse_args()

    if args.corpus:
        return run_head_to_head(args)
    return run_legacy_smoke(args)


if __name__ == "__main__":
    sys.exit(main())
