# The native engine of the app: the repository's C++ (cpp/) plus a JNI wrapper around GuiEngine. No ONNX Runtime yet:
# when a network joins the engine, Thirteen's android/app/src/main/cpp/CMakeLists.txt shows how it is linked.
cmake_minimum_required(VERSION 3.18.1)
project(mahjong_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cpp)

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
  ${CPP}/players/players.cpp
  ${CPP}/players/analysis.cpp
  ${CPP}/command/guiengine.cpp
)
target_include_directories(mahjong_jni PRIVATE ${CPP}/external/nlohmann_json)
# Optimized in debug builds too: the analysis counts waits and tiles for every action.
target_compile_options(mahjong_jni PRIVATE -O2 -Wall -Wno-sign-compare)
target_link_libraries(mahjong_jni PRIVATE log)
