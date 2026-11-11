# The native engine of the app: the repository's C++ (cpp/) plus a JNI wrapper around GuiEngine, with ONNX Runtime
# for the network (the same sources as the web page's web/engine/CMakeLists.txt). ORT_DIR has the layout of the
# onnxruntime-android AAR, unpacked: headers/ and jni/<abi>/libonnxruntime.so (app/build.gradle.kts sets it: the AAR,
# or a reduced build of it).
cmake_minimum_required(VERSION 3.18.1)
project(mahjong_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cpp)
if(NOT ORT_DIR)
  message(FATAL_ERROR "ORT_DIR (the unpacked onnxruntime-android AAR) is not set")
endif()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${ORT_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so)

add_library(mahjong_jni SHARED
  mahjong_jni.cpp
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
target_include_directories(mahjong_jni PRIVATE ${CPP}/external/nlohmann_json ${ORT_DIR}/headers)
target_compile_definitions(mahjong_jni PRIVATE USE_ONNXRUNTIME)
# Optimized in debug builds too: a review evaluates every decision of a hand.
target_compile_options(mahjong_jni PRIVATE -O2 -Wall -Wno-sign-compare)
target_link_libraries(mahjong_jni PRIVATE onnxruntime log)
