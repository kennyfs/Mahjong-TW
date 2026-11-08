cmake_minimum_required(VERSION 3.18.2)
project(mahjong LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(USE_NATIVE "Compile with -march=native" ON)

find_package(Threads REQUIRED)

set(CORE_SOURCES
  core/global.cpp
  core/rand.cpp
  core/rand_helpers.cpp
  core/hash.cpp
  core/md5.cpp
  core/sha2.cpp
  core/timer.cpp
  core/test.cpp
  core/bsearch.cpp
  core/multithread.cpp
  core/threadsafecounter.cpp
  core/threadsafequeue.cpp
  core/fancymath.cpp
)
set(GAME_SOURCES
  game/tiles.cpp
  game/shape.cpp
  game/rules.cpp
  game/tai.cpp
  game/game.cpp
)

add_executable(mahjong
  ${CORE_SOURCES}
  ${GAME_SOURCES}
  players/players.cpp
  command/play.cpp
  command/runtests.cpp
  command/match.cpp
  command/show.cpp
  command/tai.cpp
  command/benchmark.cpp
  tests/tests.cpp
  main.cpp
)

target_include_directories(mahjong PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}/external/tclap-1.2.5/include
  ${CMAKE_CURRENT_SOURCE_DIR}/external/nlohmann_json
)
target_link_libraries(mahjong PRIVATE Threads::Threads)
target_compile_options(mahjong PRIVATE -Wall -Wextra -Wno-sign-compare)
if(USE_NATIVE)
  target_compile_options(mahjong PRIVATE -march=native)
endif()
