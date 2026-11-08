#ifndef GAME_TAI_H_
#define GAME_TAI_H_

#include "../game/rules.h"
#include "../game/tiles.h"

#include <string>
#include <utility>
#include <vector>

struct Meld {
  enum Type : uint8_t { CHI = 0, PON, MINKAN, ANKAN, KAKAN };
  Type type;
  uint8_t kind;    // CHI: the lowest kind of the chow; otherwise the kind
  uint8_t called;  // the kind taken from a discard (CHI, PON, MINKAN, and KAKAN from its pung)
  int8_t from;     // the seat it was taken from, -1 for ANKAN

  bool isKong() const { return type == MINKAN || type == ANKAN || type == KAKAN; }
  bool isOpen() const { return type != ANKAN; }
  bool isPung() const { return type != CHI; }
  // The kinds of its tiles (4 for a kong).
  std::vector<int> tiles() const;
  std::string toString() const;
};

// The scoring items of docs/rules.md sections 9 and 10.
namespace Tai {
  enum Item : int {
    DEALER = 0,
    STREAK,
    MENQING,
    ZIMO,
    MENQING_ZIMO,
    DUTING,
    PINGHU,
    QUANQIU,
    BANQIU,
    ROUND_WIND,
    SEAT_WIND,
    DRAGON,
    XIAOSANYUAN,
    DASANYUAN,
    XIAOSIXI,
    DASIXI,
    PENGPENGHU,
    SANANKE,
    SIANKE,
    WUANKE,
    HUNYISE,
    QINGYISE,
    ZIYISE,
    QIANGGANG,
    GANGSHANG,
    HAIDI,
    HEDI,
    TIANHU,
    DIHU,
    RENHU,
    ANY_WIND,
    NO_HONOR_FLOWER,
    KONG,
    ANKONG,
    FLOWER,
    FLOWER_SET,
    EIGHT_FLOWERS,
    SEVEN_ROB_ONE,
    NUM_ITEMS
  };
  extern const char* const NAMES[NUM_ITEMS];          // Chinese
  extern const char* const PARAM_NAMES[NUM_ITEMS];    // as in docs/rules.md

  // A winning hand and how it was won.
  struct WinInfo {
    Counts concealed{};  // the hand, including the winning tile
    std::vector<Meld> melds;
    uint8_t flowers = 0;  // bit i: flower i (1f = bit 0)
    int winTile = -1;
    bool selfDraw = false;
    bool robbedKong = false;
    bool lastTile = false;     // self-drawn on the last tile, or won on the last discard
    bool replacement = false;  // self-drawn on a replacement after a flower, a concealed or an added kong
    bool heavenly = false;     // 天胡
    bool earthly = false;      // 地胡
    bool human = false;        // 人胡
    int seatWind = 0;          // 0-3: east, south, west, north
    int roundWind = 0;
  };

  struct Result {
    int total = 0;
    std::vector<std::pair<Item, int>> items;  // the items that scored, with their tai
    std::string toString() const;
  };

  // The tai of a winning hand (the best reading), without the dealer's tai. The hand must be complete.
  Result compute(const Rules& rules, const WinInfo& win);

  // The kinds a ready hand (3 numSets + 1 concealed tiles) really waits on: those that complete it, except kinds
  // the player holds all four of (in the hand and melds).
  uint64_t waits(const Counts& concealed, const std::vector<Meld>& melds);
}

#endif // GAME_TAI_H_
