#!/usr/bin/env bun
/**
 * Standalone Bun/TypeScript CLI test for ONNX swipe prediction
 * Uses onnxruntime-web (WASM) for cross-platform execution
 *
 * Run: bun tools/test_cli_predict.ts
 */

import * as ort from 'onnxruntime-web';
import * as fs from 'fs';
import * as path from 'path';

// Constants matching Python/Kotlin implementations
const MAX_SEQUENCE_LENGTH = 150;
const DECODER_SEQ_LENGTH = 20;
const PAD_IDX = 0;
const UNK_IDX = 1;
const SOS_IDX = 2;
const EOS_IDX = 3;
const BEAM_WIDTH = 8;
const MAX_GENERATED_TOKENS = 15;

// Keyboard layout (qwerty_english) matching Python test
const QWERTY_KEYS: Record<string, [number, number]> = {
    'q': [18, 34], 'w': [54, 34], 'e': [90, 34], 'r': [126, 34], 't': [162, 34],
    'y': [198, 34], 'u': [234, 34], 'i': [270, 34], 'o': [306, 34], 'p': [342, 34],
    'a': [36, 93], 's': [72, 93], 'd': [108, 93], 'f': [144, 93], 'g': [180, 93],
    'h': [216, 93], 'j': [252, 93], 'k': [288, 93], 'l': [324, 93],
    'z': [72, 152], 'x': [108, 152], 'c': [144, 152], 'v': [180, 152], 'b': [216, 152],
    'n': [252, 152], 'm': [288, 152]
};

// Token mappings
const KEY_IDX_TO_CHAR = ['<pad>', '<unk>', '<sos>', '<eos>', ...Array.from('abcdefghijklmnopqrstuvwxyz')];
const CHAR_TO_KEY_IDX: Record<string, number> = {};
KEY_IDX_TO_CHAR.forEach((c, i) => CHAR_TO_KEY_IDX[c] = i);

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

interface TestSwipe {
    word: string;
    curve: {
        x: number[];
        y: number[];
        t: number[];
    };
}

function getNearestKey(x: number, y: number): number {
    let minDist = Infinity;
    let nearest = '<unk>';

    for (const [key, [kx, ky]] of Object.entries(QWERTY_KEYS)) {
        const dist = Math.sqrt((x - kx) ** 2 + (y - ky) ** 2);
        if (dist < minDist) {
            minDist = dist;
            nearest = key;
        }
    }

    return CHAR_TO_KEY_IDX[nearest] ?? UNK_IDX;
}

function extractFeatures(curve: { x: number[], y: number[], t: number[] }): {
    trajectoryFeatures: number[][],
    nearestKeys: number[]
} {
    const trajectoryFeatures: number[][] = [];
    const nearestKeys: number[] = [];

    const { x: xCoords, y: yCoords, t: tCoords } = curve;

    for (let i = 0; i < xCoords.length; i++) {
        // Normalize coordinates (360x280 keyboard)
        const xNorm = xCoords[i] / 360.0;
        const yNorm = yCoords[i] / 280.0;

        // Calculate velocity
        let vx = 0, vy = 0;
        if (i > 0) {
            const dt = Math.max(tCoords[i] - tCoords[i - 1], 1);
            vx = (xCoords[i] - xCoords[i - 1]) / dt;
            vy = (yCoords[i] - yCoords[i - 1]) / dt;
        }

        // Calculate acceleration
        let ax = 0, ay = 0;
        if (i > 1) {
            const dt1 = Math.max(tCoords[i] - tCoords[i - 1], 1);
            const dt2 = Math.max(tCoords[i - 1] - tCoords[i - 2], 1);
            const vxPrev = (xCoords[i - 1] - xCoords[i - 2]) / dt2;
            const vyPrev = (yCoords[i - 1] - yCoords[i - 2]) / dt2;
            ax = (vx - vxPrev) / dt1;
            ay = (vy - vyPrev) / dt1;
        }

        trajectoryFeatures.push([xNorm, yNorm, vx, vy, ax, ay]);
        nearestKeys.push(getNearestKey(xCoords[i], yCoords[i]));
    }

    return { trajectoryFeatures, nearestKeys };
}

function createTensors(trajectoryFeatures: number[][], nearestKeys: number[], actualLength: number) {
    // Trajectory features: [1, 150, 6]
    const trajData = new Float32Array(1 * MAX_SEQUENCE_LENGTH * 6);
    for (let i = 0; i < Math.min(actualLength, MAX_SEQUENCE_LENGTH); i++) {
        for (let j = 0; j < 6; j++) {
            trajData[i * 6 + j] = trajectoryFeatures[i][j];
        }
    }

    // Nearest keys: [1, 150] as int64
    const keysData = new BigInt64Array(MAX_SEQUENCE_LENGTH);
    for (let i = 0; i < Math.min(actualLength, MAX_SEQUENCE_LENGTH); i++) {
        keysData[i] = BigInt(nearestKeys[i]);
    }

    // Source mask: [1, 150] as bool (true for padded positions)
    const maskData = new Uint8Array(MAX_SEQUENCE_LENGTH);
    for (let i = actualLength; i < MAX_SEQUENCE_LENGTH; i++) {
        maskData[i] = 1;
    }

    return {
        trajectoryTensor: new ort.Tensor('float32', trajData, [1, MAX_SEQUENCE_LENGTH, 6]),
        nearestKeysTensor: new ort.Tensor('int64', keysData, [1, MAX_SEQUENCE_LENGTH]),
        srcMaskTensor: new ort.Tensor('bool', maskData, [1, MAX_SEQUENCE_LENGTH])
    };
}

function softmax(logits: Float32Array): Float32Array {
    const maxLogit = Math.max(...logits);
    const expScores = Array.from(logits).map(l => Math.exp(l - maxLogit));
    const sumExp = expScores.reduce((a, b) => a + b, 0);
    return new Float32Array(expScores.map(e => e / sumExp));
}

function getTopK(probs: Float32Array, k: number): { idx: number, prob: number }[] {
    const indexed = Array.from(probs).map((prob, idx) => ({ idx, prob }));
    indexed.sort((a, b) => b.prob - a.prob);
    return indexed.slice(0, k);
}

function decodeTokens(tokens: number[]): string {
    let word = '';
    for (const token of tokens) {
        if (token === EOS_IDX) break;
        if (token === SOS_IDX || token === PAD_IDX) continue;
        const char = KEY_IDX_TO_CHAR[token];
        if (char && !char.startsWith('<')) {
            word += char;
        }
    }
    return word;
}

async function beamSearchDecode(
    decoderSession: ort.InferenceSession,
    memory: ort.Tensor,
    srcMask: ort.Tensor
): Promise<{ word: string, score: number }[]> {
    let beams: Beam[] = [{
        tokens: [SOS_IDX],
        score: 0,
        finished: false
    }];

    for (let step = 0; step < MAX_GENERATED_TOKENS; step++) {
        const allCandidates: Beam[] = [];

        for (const beam of beams) {
            if (beam.finished) {
                allCandidates.push(beam);
                continue;
            }

            // Prepare decoder inputs - pad to fixed length
            const paddedTokens = new BigInt64Array(DECODER_SEQ_LENGTH);
            for (let i = 0; i < Math.min(beam.tokens.length, DECODER_SEQ_LENGTH); i++) {
                paddedTokens[i] = BigInt(beam.tokens[i]);
            }

            // Target mask (1 for padded positions)
            const tgtMask = new Uint8Array(DECODER_SEQ_LENGTH);
            for (let i = beam.tokens.length; i < DECODER_SEQ_LENGTH; i++) {
                tgtMask[i] = 1;
            }

            const decoderInputs = {
                memory: memory,
                target_tokens: new ort.Tensor('int64', paddedTokens, [1, DECODER_SEQ_LENGTH]),
                target_mask: new ort.Tensor('bool', tgtMask, [1, DECODER_SEQ_LENGTH]),
                src_mask: srcMask
            };

            const outputs = await decoderSession.run(decoderInputs);
            const logits = outputs.logits as ort.Tensor;
            const logitsData = logits.data as Float32Array;

            // Get logits for current position
            const vocabSize = 30;
            const tokenPosition = Math.min(beam.tokens.length - 1, DECODER_SEQ_LENGTH - 1);
            const startIdx = tokenPosition * vocabSize;
            const relevantLogits = new Float32Array(logitsData.slice(startIdx, startIdx + vocabSize));

            const probs = softmax(relevantLogits);
            const topK = getTopK(probs, BEAM_WIDTH);

            for (const { idx, prob } of topK) {
                const newTokens = [...beam.tokens, idx];
                const finished = idx === EOS_IDX;

                allCandidates.push({
                    tokens: newTokens,
                    score: beam.score + Math.log(prob),
                    finished
                });
            }
        }

        // Keep top beams
        beams = allCandidates
            .sort((a, b) => b.score - a.score)
            .slice(0, BEAM_WIDTH);

        if (beams.every(b => b.finished)) break;
    }

    return beams
        .map(beam => ({
            word: decodeTokens(beam.tokens),
            score: Math.exp(beam.score / beam.tokens.length)
        }))
        .filter(p => p.word.length > 0);
}

async function main() {
    console.log('='.repeat(70));
    console.log('Bun/TypeScript CLI Prediction Test - ONNX Neural Swipe');
    console.log('='.repeat(70));
    console.log('');

    // Configure WASM backend
    ort.env.wasm.numThreads = 1;

    // Find models
    const baseDir = path.dirname(import.meta.dir);
    const modelDir = path.join(baseDir, 'cli-test', 'assets', 'models');
    const encoderPath = path.join(modelDir, 'swipe_model_character_quant.onnx');
    const decoderPath = path.join(modelDir, 'swipe_decoder_character_quant.onnx');

    console.log('✅ Loading encoder model...');
    console.log(`   Path: ${encoderPath}`);
    // Load model as ArrayBuffer for onnxruntime-web
    const encoderBuffer = fs.readFileSync(encoderPath);
    const encoderSession = await ort.InferenceSession.create(encoderBuffer.buffer);
    console.log('✅ Encoder loaded successfully');
    console.log('');

    console.log('✅ Loading decoder model...');
    console.log(`   Path: ${decoderPath}`);
    const decoderBuffer = fs.readFileSync(decoderPath);
    const decoderSession = await ort.InferenceSession.create(decoderBuffer.buffer);
    console.log('✅ Decoder loaded successfully');
    console.log('');

    // Load test data (use the swypes.jsonl from swype-model-training in cleverkeys)
    const swipesPath = path.join(baseDir, 'swype-model-training', 'swipes.jsonl');
    console.log(`✅ Loading test data from ${swipesPath}...`);

    const swipesData = fs.readFileSync(swipesPath, 'utf-8');
    const testSwipes: TestSwipe[] = swipesData
        .split('\n')
        .filter(line => line.trim())
        .map(line => JSON.parse(line));

    console.log(`✅ Loaded ${testSwipes.length} test swipes`);
    console.log('');

    // Run tests (limit to first 100 for speed)
    const testLimit = Math.min(100, testSwipes.length);
    console.log('='.repeat(70));
    console.log(`Running Prediction Tests (${testLimit} samples)`);
    console.log('='.repeat(70));

    let top1Count = 0;
    let top3Count = 0;
    let top5Count = 0;
    let total = 0;

    for (let i = 0; i < testLimit; i++) {
        const swipe = testSwipes[i];
        const targetWord = swipe.word;

        try {
            const { trajectoryFeatures, nearestKeys } = extractFeatures(swipe.curve);
            const actualLength = trajectoryFeatures.length;
            const { trajectoryTensor, nearestKeysTensor, srcMaskTensor } = createTensors(
                trajectoryFeatures, nearestKeys, actualLength
            );

            // Run encoder
            const encoderOutputs = await encoderSession.run({
                trajectory_features: trajectoryTensor,
                nearest_keys: nearestKeysTensor,
                src_mask: srcMaskTensor
            });

            const memory = encoderOutputs.encoder_output as ort.Tensor;

            // Run beam search decoder
            const predictions = await beamSearchDecode(decoderSession, memory, srcMaskTensor);
            const predictedWord = predictions[0]?.word || '<none>';
            const top3Words = predictions.slice(0, 3).map(p => p.word);
            const top5Words = predictions.slice(0, 5).map(p => p.word);

            const isTop1 = predictedWord === targetWord;
            const isTop3 = top3Words.includes(targetWord);
            const isTop5 = top5Words.includes(targetWord);

            let status = '❌';
            if (isTop1) status = '✅';
            else if (isTop3) status = '🔶';  // in top 3
            else if (isTop5) status = '🔷';  // in top 5

            console.log(`  [${(i + 1).toString().padStart(3)}/${testLimit}] Target: '${targetWord.padEnd(10)}' → Predicted: '${predictedWord.padEnd(10)}' ${status}`);

            if (isTop1) top1Count++;
            if (isTop3) top3Count++;
            if (isTop5) top5Count++;
            total++;

        } catch (e) {
            console.error(`  [${i + 1}/${testLimit}] Error processing '${targetWord}':`, e);
            total++;
        }
    }

    console.log('');
    console.log('='.repeat(70));
    console.log('Results Summary');
    console.log('='.repeat(70));
    console.log(`Total predictions: ${total}`);
    console.log('');
    const top1Acc = total > 0 ? (top1Count / total * 100) : 0;
    const top3Acc = total > 0 ? (top3Count / total * 100) : 0;
    const top5Acc = total > 0 ? (top5Count / total * 100) : 0;
    console.log(`Top-1 accuracy: ${top1Acc.toFixed(1)}% (${top1Count}/${total})`);
    console.log(`Top-3 accuracy: ${top3Acc.toFixed(1)}% (${top3Count}/${total})`);
    console.log(`Top-5 accuracy: ${top5Acc.toFixed(1)}% (${top5Count}/${total})`);
    console.log('');

    // Use top-3 accuracy for pass/fail (standard for prediction systems)
    if (top3Acc >= 60) {
        console.log('🎉 TOP-3 ACCURACY TARGET MET (≥60%)');
    } else {
        console.log(`⚠️  Top-3 accuracy below target (${top3Acc.toFixed(1)}% < 60%)`);
    }

    console.log('');
    console.log('✅ Bun/TypeScript prediction test complete');
}

main().catch(console.error);
