# The engine for the web page: the repository's C++ (cpp/) and GuiEngine as WebAssembly, with a reduced ONNX Runtime
# built as a WebAssembly static library (Thirteen's web/build-ort-wasm.sh; its operators cover this network's).
# Configured with emcmake; see web/README.md.
cmake_minimum_required(VERSION 3.18)
project(mahjong_wasm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../cpp)
set(ORT_WASM_DIR "" CACHE PATH "The reduced ONNX Runtime for WebAssembly: include/ and lib/libonnxruntime_webassembly.a")
if(NOT ORT_WASM_DIR)
  message(FATAL_ERROR "ORT_WASM_DIR is not set")
endif()

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
  ${CPP}/learn/actions.cpp
  ${CPP}/learn/outcome.cpp
  ${CPP}/learn/features.cpp
  ${CPP}/learn/netmodel.cpp
  ${CPP}/players/players.cpp
  ${CPP}/players/factory.cpp
  ${CPP}/players/netplayer.cpp
  ${CPP}/players/mcplayer.cpp
  ${CPP}/players/analysis.cpp
  ${CPP}/command/play.cpp
  ${CPP}/command/guiengine.cpp
  ${CPP}/command/guianalysis.cpp
)
target_include_directories(mahjong_engine PRIVATE ${CPP}/external/nlohmann_json ${ORT_WASM_DIR}/include)
target_compile_definitions(mahjong_engine PRIVATE USE_ONNXRUNTIME)
target_link_libraries(mahjong_engine PRIVATE ${ORT_WASM_DIR}/lib/libonnxruntime_webassembly.a)
target_compile_options(mahjong_engine PRIVATE -O3 -msimd128 -fexceptions -Wall -Wno-sign-compare)
# A module for a worker (engine-worker.ts): ES6, growing memory, the engine's two calls, and a file system in memory
# for the network (written there before a request names it).
target_link_options(mahjong_engine PRIVATE
  -O3 -fexceptions -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,worker,node -sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=64MB
  -sSTACK_SIZE=1MB -sEXPORTED_FUNCTIONS=_engine_handle,_engine_free,_malloc,_free
  -sEXPORTED_RUNTIME_METHODS=FS,stringToNewUTF8,UTF8ToString -sFILESYSTEM=1
)
set_target_properties(mahjong_engine PROPERTIES OUTPUT_NAME engine SUFFIX ".js")
