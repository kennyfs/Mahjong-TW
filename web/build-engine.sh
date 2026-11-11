#!/bin/bash
# Builds the engine (cpp/ and GuiEngine, engine/) as WebAssembly into engine-files/ (engine.js and engine.wasm), and
# copies the network there (actor.onnx, from $MODEL): what the page needs besides its own code, which the page's build
# (vite.config.ts) puts in /assets under hashed names. Needs the emsdk activated (source emsdk_env.sh, for emcmake),
# cmake, ninja, and a reduced ONNX Runtime for WebAssembly in $ORT_WASM_DIR (default build/ort-wasm; Thirteen's
# web/build-ort-wasm.sh builds one whose operators cover this network's). The build goes to $ENGINE_BUILD (default
# build/engine).
set -euo pipefail
cd "$(dirname "$0")"
ort=$(realpath -m "${ORT_WASM_DIR:-build/ort-wasm}")
build=$(realpath -m "${ENGINE_BUILD:-build/engine}")
emcmake cmake -S engine -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release -DORT_WASM_DIR="$ort"
cmake --build "$build" --parallel "${JOBS:-$(nproc)}"
mkdir -p engine-files
cp "$build/engine.js" "$build/engine.wasm" engine-files/
if [ -n "${MODEL:-}" ]; then cp "$MODEL" engine-files/actor.onnx; fi
if [ ! -f engine-files/actor.onnx ]; then echo "engine-files/actor.onnx is missing: set MODEL to a network (.onnx)"; exit 1; fi
ls -l engine-files
