#!/bin/bash
# Builds the engine (cpp/ and GuiEngine, engine/) as WebAssembly into engine-files/ (engine.js and engine.wasm): what the
# page needs besides its own code, which the page's build (vite.config.ts) puts in /assets under hashed names. Needs
# the emsdk activated (source emsdk_env.sh, for emcmake), cmake and ninja. The build goes to $ENGINE_BUILD (default
# build/engine).
set -euo pipefail
cd "$(dirname "$0")"
build=$(realpath -m "${ENGINE_BUILD:-build/engine}")
emcmake cmake -S engine -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --parallel "${JOBS:-$(nproc)}"
mkdir -p engine-files
cp "$build/engine.js" "$build/engine.wasm" engine-files/
ls -l engine-files
