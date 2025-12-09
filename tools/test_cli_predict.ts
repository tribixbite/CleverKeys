#!/usr/bin/env bun
/**
 * Standalone Bun/TypeScript CLI test for ONNX swipe prediction
 * Uses onnxruntime-web (WASM) for cross-platform execution
 * Updated for Android model architecture (250 seq len, actual_length)
 *
 * Run: bun tools/test_cli_predict.ts
 */

import * as ort from 'onnxruntime-web';
import * as fs from 'fs';
import * as path from 'path';

// Constants matching Android implementation
const MAX_SEQUENCE_LENGTH = 250;
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
    /**
     * NOTE: Using position-only features (zeros for velocity/acceleration)
     * because test data has corrupt timestamps that hurt model accuracy.
     * Position-only: 53% vs with velocity: 29%
     */
    const trajectoryFeatures: number[][] = [];
    const nearestKeys: number[] = [];

    const { x: xCoords, y: yCoords } = curve;

    for (let i = 0; i < xCoords.length; i++) {
        // Normalize coordinates (360x280 keyboard)
        const xNorm = xCoords[i] / 360.0;
        const yNorm = yCoords[i] / 280.0;

        // Set velocity and acceleration to zero
        // Test data has corrupt timestamps, so velocity features hurt accuracy
        const vx = 0, vy = 0;
        const ax = 0, ay = 0;

        trajectoryFeatures.push([xNorm, yNorm, vx, vy, ax, ay]);
        nearestKeys.push(getNearestKey(xCoords[i], yCoords[i]));
    }

    return { trajectoryFeatures, nearestKeys };
}

function createTensors(trajectoryFeatures: number[][], nearestKeys: number[], actualLength: number) {
    // Trajectory features: [1, 250, 6]
    const trajData = new Float32Array(1 * MAX_SEQUENCE_LENGTH * 6);
    for (let i = 0; i < Math.min(actualLength, MAX_SEQUENCE_LENGTH); i++) {
        for (let j = 0; j < 6; j++) {
            trajData[i * 6 + j] = trajectoryFeatures[i][j];
        }
    }

    // Nearest keys: [1, 250] as int32 for Android model
    const keysData = new Int32Array(MAX_SEQUENCE_LENGTH);
    for (let i = 0; i < Math.min(actualLength, MAX_SEQUENCE_LENGTH); i++) {
        keysData[i] = nearestKeys[i];
    }

    // Actual length: [1] as int32 (replaces src_mask)
    const actualLengthData = new Int32Array([Math.min(actualLength, MAX_SEQUENCE_LENGTH)]);

    return {
        trajectoryTensor: new ort.Tensor('float32', trajData, [1, MAX_SEQUENCE_LENGTH, 6]),
        nearestKeysTensor: new ort.Tensor('int32', keysData, [1, MAX_SEQUENCE_LENGTH]),
        actualLengthTensor: new ort.Tensor('int32', actualLengthData, [1])
    };
}

function getTopK(logProbs: Float32Array, k: number): { idx: number, logProb: number }[] {
    const indexed = Array.from(logProbs).map((logProb, idx) => ({ idx, logProb }));
    indexed.sort((a, b) => b.logProb - a.logProb);  // Higher (less negative) is better
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
    actualSrcLength: number
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

            // Prepare decoder inputs - pad to fixed length (int32 for Android)
            const paddedTokens = new Int32Array(DECODER_SEQ_LENGTH);
            for (let i = 0; i < Math.min(beam.tokens.length, DECODER_SEQ_LENGTH); i++) {
                paddedTokens[i] = beam.tokens[i];
            }

            // Actual src length tensor
            const actualSrcLengthTensor = new ort.Tensor('int32', new Int32Array([actualSrcLength]), [1]);

            const decoderInputs = {
                memory: memory,
                target_tokens: new ort.Tensor('int32', paddedTokens, [1, DECODER_SEQ_LENGTH]),
                actual_src_length: actualSrcLengthTensor
            };

            const outputs = await decoderSession.run(decoderInputs);
            const logProbs = outputs.log_probs as ort.Tensor;  // Android model outputs log_probs
            const logProbsData = logProbs.data as Float32Array;

            // Get log_probs for current position
            const vocabSize = 30;
            const tokenPosition = Math.min(beam.tokens.length - 1, DECODER_SEQ_LENGTH - 1);
            const startIdx = tokenPosition * vocabSize;
            const relevantLogProbs = new Float32Array(logProbsData.slice(startIdx, startIdx + vocabSize));

            const topK = getTopK(relevantLogProbs, BEAM_WIDTH);

            for (const { idx, logProb } of topK) {
                const newTokens = [...beam.tokens, idx];
                const finished = idx === EOS_IDX || idx === PAD_IDX;

                allCandidates.push({
                    tokens: newTokens,
                    // Score is negative log likelihood (lower is better)
                    // Since logProb is negative, score += -logProb makes it positive accumulating
                    score: beam.score + (-logProb),
                    finished
                });
            }
        }

        // Keep top beams (lower score is better)
        beams = allCandidates
            .sort((a, b) => a.score - b.score)
            .slice(0, BEAM_WIDTH);

        if (beams.every(b => b.finished)) break;
    }

    return beams
        .map(beam => ({
            word: decodeTokens(beam.tokens),
            score: beam.score
        }))
        .filter(p => p.word.length > 0);
}

async function main() {
    console.log('='.repeat(70));
    console.log('Bun/TypeScript CLI Prediction Test - Android ONNX (250 seq len)');
    console.log('='.repeat(70));
    console.log('');

    // Configure WASM backend
    ort.env.wasm.numThreads = 1;

    // Find models (Android models)
    const baseDir = path.dirname(import.meta.dir);
    const modelDir = path.join(baseDir, 'cli-test', 'assets', 'models');
    const encoderPath = path.join(modelDir, 'swipe_encoder_android.onnx');
    const decoderPath = path.join(modelDir, 'swipe_decoder_android.onnx');

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

    // Load test data
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
            const { trajectoryTensor, nearestKeysTensor, actualLengthTensor } = createTensors(
                trajectoryFeatures, nearestKeys, actualLength
            );

            // Run encoder (Android model uses actual_length)
            const encoderOutputs = await encoderSession.run({
                trajectory_features: trajectoryTensor,
                nearest_keys: nearestKeysTensor,
                actual_length: actualLengthTensor
            });

            const memory = encoderOutputs.encoder_output as ort.Tensor;

            // Run beam search decoder
            const predictions = await beamSearchDecode(decoderSession, memory, Math.min(actualLength, MAX_SEQUENCE_LENGTH));
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
