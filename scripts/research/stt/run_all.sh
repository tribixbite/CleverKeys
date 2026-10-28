#!/data/data/com.termux/files/usr/bin/bash
# Sequential benchmark queue for the 2026-10-28 STT engine deep dive. Runs one engine at a time
# (parallel runs would contend for the same four visible cores) and stamps `uptime` before each
# step, because the load average on this box is dominated by other sessions' Gradle builds and
# qualifies every number. All inputs live outside the repo under $STT (see the note for the
# download commands); results are JSON files under $STT/results.
#
#   STT=<scratch dir with models/ audio/ results/> scripts/research/stt/run_all.sh [step...]
#
# Steps: whistle_fp16 whistle_stream whistle_xnnpack moonshine moonshine_stream needle needle_stream
#        zipformer whistle_fp32_quiet (re-run of the fp32 batch numbers, for a quieter window)
#        whistle_int8 (graphs from quantize_whistle_int8.py; ran after the main queue on 2026-10-28)
set -u
STT=${STT:?set STT to the scratch dir}
HERE=$(cd "$(dirname "$0")" && pwd)
R=$STT/results
M=$STT/manifest_ids
IDS5=$(cat "$STT/ids5.txt")
IDS15=$(cat "$STT/ids15.txt")
MAN=$STT/audio/manifest.json
W_ONNX=$STT/models/whistle-onnx/onnx
W_CACT=$STT/models/whistle/whistle.cact
MOON=$STT/models/moonshine-tiny-streaming-en
TOK=$MOON/masterx_tokenizer.json
NEEDLE=$STT/models/needle3/android-arm64/needle
ZIP=$STT/models/sherpa/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile
PY="python3 -W ignore"
mkdir -p "$R"
STEPS=${*:-"whistle_fp16 whistle_stream whistle_xnnpack moonshine moonshine_stream needle needle_stream zipformer whistle_fp32_quiet"}

stamp() { echo; echo "### $1  $(date '+%F %T')  $(uptime | sed 's/.*load average/load average/')"; }

for step in $STEPS; do
  case $step in
    whistle_fp16)
      stamp "whistle fp16 bench 5s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --fp16 --threads 4 bench "$MAN" --ids "$IDS5" --repeat 2 --out "$R/whistle_fp16_5s.json"
      stamp "whistle fp16 bench 15s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --fp16 --threads 4 bench "$MAN" --ids "$IDS15" --repeat 2 --out "$R/whistle_fp16_15s.json" ;;
    whistle_stream)
      stamp "whistle pseudo-stream 1 s chunks, 5s set"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS5" --chunk 1.0 --out "$R/whistle_stream_5s.json"
      stamp "whistle pseudo-stream 1 s chunks, 15s set (verbose)"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 1.0 --verbose --out "$R/whistle_stream_15s.json"
      stamp "whistle pseudo-stream 1 s chunks + forced prefix, 15s set"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 1.0 --force-prefix --out "$R/whistle_stream_15s_forced.json"
      stamp "whistle pseudo-stream 0.5 s chunks, 15s set"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 0.5 --out "$R/whistle_stream_15s_c05.json" ;;
    whistle_xnnpack)
      stamp "whistle fp32 XNNPACK EP bench 5s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --ep xnnpack --threads 4 bench "$MAN" --ids "$IDS5" --repeat 2 --out "$R/whistle_fp32_xnnpack_5s.json"
      stamp "whistle fp32 1 thread bench 5s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 1 bench "$MAN" --ids "$IDS5" --repeat 2 --out "$R/whistle_fp32_1thr_5s.json" ;;
    moonshine)
      stamp "moonshine tiny streaming (official .ort, quantized) bench 5s"; $PY "$HERE/moonshine_stream_ort.py" --model-dir "$MOON" --tokenizer "$TOK" --threads 4 bench "$MAN" --ids "$IDS5" --repeat 2 --out "$R/moonshine_5s.json"
      stamp "moonshine bench 15s"; $PY "$HERE/moonshine_stream_ort.py" --model-dir "$MOON" --tokenizer "$TOK" --threads 4 bench "$MAN" --ids "$IDS15" --repeat 2 --out "$R/moonshine_15s.json" ;;
    moonshine_stream)
      stamp "moonshine stream 1 s chunks 5s"; $PY "$HERE/moonshine_stream_ort.py" --model-dir "$MOON" --tokenizer "$TOK" --threads 4 stream "$MAN" --ids "$IDS5" --chunk 1.0 --out "$R/moonshine_stream_5s.json"
      stamp "moonshine stream 1 s chunks 15s (verbose)"; $PY "$HERE/moonshine_stream_ort.py" --model-dir "$MOON" --tokenizer "$TOK" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 1.0 --verbose --out "$R/moonshine_stream_15s.json"
      stamp "moonshine stream 0.5 s chunks 15s"; $PY "$HERE/moonshine_stream_ort.py" --model-dir "$MOON" --tokenizer "$TOK" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 0.5 --out "$R/moonshine_stream_15s_c05.json" ;;
    needle)
      stamp "needle CLI (official engine, 5 beams) batch 5s"; $PY "$HERE/needle_cli_bench.py" --needle "$NEEDLE" --cact "$W_CACT" "$MAN" --ids "$IDS5" --repeat 2 --out "$R/needle_5s.json"
      stamp "needle CLI batch 15s"; $PY "$HERE/needle_cli_bench.py" --needle "$NEEDLE" --cact "$W_CACT" "$MAN" --ids "$IDS15" --repeat 2 --out "$R/needle_15s.json"
      stamp "needle CLI batch 5s depth 4"; $PY "$HERE/needle_cli_bench.py" --needle "$NEEDLE" --cact "$W_CACT" "$MAN" --ids "$IDS5" --repeat 2 --depth 4 --out "$R/needle_5s_depth4.json" ;;
    needle_stream)
      stamp "needle CLI --audio-stream 1 s chunks 5s"; $PY "$HERE/needle_cli_bench.py" --needle "$NEEDLE" --cact "$W_CACT" "$MAN" --ids "$IDS5" --repeat 1 --stream --chunk 1 --out "$R/needle_stream_5s.json"
      stamp "needle CLI --audio-stream 1 s chunks 15s"; $PY "$HERE/needle_cli_bench.py" --needle "$NEEDLE" --cact "$W_CACT" "$MAN" --ids "$IDS15" --repeat 1 --stream --chunk 1 --out "$R/needle_stream_15s.json" ;;
    zipformer)
      stamp "sherpa zipformer 20M int8 stream 5s"; $PY "$HERE/sherpa_zipformer_ort.py" --model-dir "$ZIP" "$MAN" --ids "$IDS5" --repeat 2 --chunk 0.32 --out "$R/zipformer_5s.json"
      stamp "sherpa zipformer 20M int8 stream 15s"; $PY "$HERE/sherpa_zipformer_ort.py" --model-dir "$ZIP" "$MAN" --ids "$IDS15" --repeat 2 --chunk 0.32 --out "$R/zipformer_15s.json" ;;
    whistle_fp32_quiet)
      stamp "whistle fp32 bench 5s (re-run)"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 bench "$MAN" --ids "$IDS5" --repeat 3 --out "$R/whistle_fp32_5s_rerun.json"
      stamp "whistle fp32 bench 15s (re-run)"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W_ONNX" --cact "$W_CACT" --threads 4 bench "$MAN" --ids "$IDS15" --repeat 3 --out "$R/whistle_fp32_15s_rerun.json" ;;
    whistle_int8)
      W8=$STT/models/whistle-onnx/onnx-int8
      stamp "whistle int8 bench 5s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W8" --cact "$W_CACT" --threads 4 bench "$MAN" --ids "$IDS5" --repeat 3 --out "$R/whistle_int8_5s.json"
      stamp "whistle int8 bench 15s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W8" --cact "$W_CACT" --threads 4 bench "$MAN" --ids "$IDS15" --repeat 3 --out "$R/whistle_int8_15s.json"
      stamp "whistle int8 stream 15s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W8" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS15" --chunk 1.0 --out "$R/whistle_int8_stream_15s.json"
      stamp "whistle int8 stream 5s"; $PY "$HERE/whistle_ort.py" --onnx-dir "$W8" --cact "$W_CACT" --threads 4 stream "$MAN" --ids "$IDS5" --chunk 1.0 --out "$R/whistle_int8_stream_5s.json" ;;
    *) echo "unknown step $step" ;;
  esac
done
stamp "QUEUE-DONE"
