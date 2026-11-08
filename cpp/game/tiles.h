#ifndef GAME_TILES_H_
#define GAME_TILES_H_

#include <array>
#include <cstdint>
#include <string>
#include <vector>

// Tile kinds (docs/rules.md section 1):
//   0-8 characters 1m-9m, 9-17 dots 1p-9p, 18-26 bamboo 1s-9s,
//   27-30 winds east, south, west, north (1z-4z), 31-33 dragons white, green, red (5z-7z),
//   34-41 flowers: seasons 春夏秋冬 (1f-4f), plants 梅蘭菊竹 (5f-8f).
namespace Tiles {
  constexpr int NUM_KINDS = 34;
  constexpr int NUM_FLOWERS = 8;
  constexpr int FLOWER_BASE = 34;
  constexpr int NUM_ALL = 42;
  constexpr int EAST = 27;
  constexpr int WHITE = 31;

  inline bool isFlower(int t) { return t >= FLOWER_BASE; }
  // 0, 1, 2 for the number suits, 3 for honors.
  inline int suitOf(int k) { return k / 9; }
  // 0-8 for numbers 1-9.
  inline int numberOf(int k) { return k % 9; }
  inline bool isHonor(int k) { return k >= 27; }
  inline bool isWind(int k) { return k >= 27 && k < 31; }
  inline bool isDragon(int k) { return k >= 31 && k < 34; }
  inline bool isTerminal(int k) { return k < 27 && (k % 9 == 0 || k % 9 == 8); }

  // "1m", "5z", "3f".
  std::string name(int t);
  // "一萬", "東", "春".
  std::string chineseName(int t);
  // Parses a list like "123m 456p 11z 5f" (spaces optional, a suit letter ends each group of digits) into tiles in
  // order. Throws StringError on bad input.
  std::vector<int> parse(const std::string& s);
  // Compact form of a list of tiles: sorted, grouped by suit ("123m456p11z").
  std::string toString(const std::vector<int>& tiles);
}

// Counts of each of the 34 kinds.
using Counts = std::array<uint8_t, Tiles::NUM_KINDS>;

namespace Tiles {
  Counts countsOf(const std::vector<int>& tiles);
  std::vector<int> tilesOf(const Counts& c);
  std::string toString(const Counts& c);
  int total(const Counts& c);
}

#endif // GAME_TILES_H_
