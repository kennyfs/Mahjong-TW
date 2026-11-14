#ifndef GAME_SHAPE_H_
#define GAME_SHAPE_H_

#include "../game/tiles.h"

#include <vector>

// Hand shapes: winning, waits, readings and shanten for the concealed part of a hand that needs `numSets` sets and a
// pair (numSets = 5 minus the number of melds, so the concealed part has 3 numSets + 1 tiles, or + 2 after a draw).
namespace Shape {
  // Whether 3 numSets + 2 tiles form numSets sets and one pair.
  bool isComplete(const Counts& c, int numSets);

  // The kinds that complete a hand of 3 numSets + 1 tiles, as a bit mask over the 34 kinds. A kind the hand already
  // holds four of is included (callers that want real waits drop the kinds they hold all four of, melds included).
  uint64_t rawWaits(const Counts& c, int numSets);

  // A group of a reading.
  struct Group {
    enum Type : uint8_t { CHOW = 0, PUNG = 1, PAIR = 2 };
    Type type;
    uint8_t kind;  // the lowest kind for a chow
    bool contains(int k) const { return type == CHOW ? (k >= kind && k <= kind + 2) : k == kind; }
  };
  struct Reading {
    Group groups[6];
    int n = 0;
  };
  // Every way to read a complete hand (3 numSets + 2 tiles) as numSets sets and a pair, each multiset of groups once.
  void readings(const Counts& c, int numSets, std::vector<Reading>& out);

  // The tiles a ready hand (3 numSets + 1 tiles) waits with (聽的相關牌組): all but those of the groups that stay the
  // same whichever of `waits` comes and whichever way the hand then reads, the group the tile comes into aside. So
  // 34567 waiting on 2, 5 and 8 gives all five, and 11 456 78 waiting on 6 and 9 gives 78. None when `waits` is 0.
  Counts waitTiles(const Counts& c, int numSets, uint64_t waits);

  // The number of tiles a hand is from ready (聽牌): 0 when ready, -1 when complete (3 numSets + 2 tiles), so the
  // fewest tiles still needed to win is shanten + 1 (the MTW of Chen et al. 2022). Works for 3 numSets + 1 or + 2
  // tiles. It ignores how many of each kind are left (a hand waiting only on kinds it holds all four of counts as
  // ready).
  int shanten(const Counts& c, int numSets);
}

#endif // GAME_SHAPE_H_
