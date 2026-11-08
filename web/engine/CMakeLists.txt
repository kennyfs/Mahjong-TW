# The engine for the web page: the repository's C++ (cpp/) and GuiEngine as WebAssembly. Configured with emcmake; see
# web/README.md. (A network, later, adds a reduced ONNX Runtime for WebAssembly as Thirteen's web/build-ort-wasm.sh
# builds it, linked here as Thirteen's web/engine/CMakeLists.txt does.)
cmake_minimum_required(VERSION 3.18)
project(mahjong_wasm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../cpp)

add_executable(mahjong_engine
  engine.cpp
  ${CPP}/core/global.cpp
  ${CPP}/core/rand.cpp
  ${CPP}/core/rand_helpers.cpp
  ${CPP}/core/hash.cpp
  ${CPP}/core/md5.cpp
  ${CPP}/core/sha2.cpp
  ${CPP}/core/timer.cpp
  ${CPP}/core/bsearch.cpp
  ${CPP}/game/tiles.cpp
  ${CPP}/game/shape.cpp
  ${CPP}/game/rules.cpp
  ${CPP}/game/tai.cpp
  ${CPP}/game/game.cpp
  ${CPP}/players/players.cpp
  ${CPP}/players/analysis.cpp
  ${CPP}/command/guiengine.cpp
)
target_include_directories(mahjong_engine PRIVATE ${CPP}/external/nlohmann_json)
target_compile_options(mahjong_engine PRIVATE -O3 -msimd128 -fexceptions -Wall -Wno-sign-compare)
# A module for a worker (engine-worker.ts): ES6, growing memory, the engine's two calls.
target_link_options(mahjong_engine PRIVATE
  -O3 -fexceptions -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,worker,node -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=32MB
  -sSTACK_SIZE=1MB -sEXPORTED_FUNCTIONS=_engine_handle,_engine_free,_malloc,_free
  -sEXPORTED_RUNTIME_METHODS=stringToNewUTF8,UTF8ToString
)
set_target_properties(mahjong_engine PROPERTIES OUTPUT_NAME engine SUFFIX ".js")
