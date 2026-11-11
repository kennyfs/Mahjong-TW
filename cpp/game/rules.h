#ifndef GAME_RULES_H_
#define GAME_RULES_H_

#include <string>
#include <vector>

// The rule parameters of docs/rules.md section 11: the flower settings, the value of every tai item (0 turns it off)
// and a few play options. Everything is a number, so a network can take the rules as input.
struct Rules {
  // --- Flowers (section 10) ---
  enum FlowerMode : int { NO_FLOWERS = 0, SEE_FLOWERS = 1, SEAT_FLOWERS = 2 };
  int flowers = NO_FLOWERS;
  int flowerTai = 1;     // per flower (see) or per flower of one's seat (seat)
  int flowerSet = 0;     // 花槓, per complete set of seasons or plants
  int eightFlowers = 0;  // 八仙過海 (set by the tai presets: 8 in standard and masters)
  int sevenRobOne = 0;   // 七搶一

  // --- Tai (section 9) ---
  int dealer = 1;
  int streak = 1;
  int menqing = 0;
  int zimo = 1;
  int menqingZimo = 0;
  int duting = 0;
  int pinghu = 0;
  int quanqiu = 0;
  int quanqiuDuting = 0;
  int banqiu = 0;
  int roundWind = 0;
  int seatWind = 0;
  int dragon = 0;
  int xiaosanyuan = 0;
  int dasanyuan = 0;
  int xiaosixi = 0;
  int dasixi = 0;
  int dasixiWinds = 0;
  int pengpenghu = 0;
  int sananke = 0;
  int sianke = 0;
  int wuanke = 0;
  int hunyise = 0;
  int qingyise = 0;
  int ziyise = 0;
  int qianggang = 0;
  int gangshang = 0;
  int haidi = 0;
  int hedi = 0;
  int tianhu = 0;
  int dihu = 0;
  int renhu = 0;
  int anyWind = 0;
  int noHonorFlower = 0;
  int kongTai = 0;
  int ankongTai = 0;
  int tianting = 0;  // 天聽 (0: it cannot be declared)
  int diting = 0;    // 地聽

  // --- Play ---
  double base = 2.0;
  int drawStreak = 1;
  int multiRon = 0;
  int keep = 16;
  int minTai = 0;
  int dutingLoose = 0;

  bool hasFlowers() const { return flowers != NO_FLOWERS; }
  int numTiles() const { return hasFlowers() ? 144 : 136; }

  // Every parameter by its name in docs/rules.md (the network's rules input will use this order).
  struct Param {
    const char* name;
    int Rules::* intField;
    double Rules::* doubleField;
  };
  static const std::vector<Param>& params();
  double get(const std::string& name) const;
  void set(const std::string& name, double value);

  // Presets (section 11).
  static const char* const TAI_PRESETS[];
  static const char* const FLOWER_PRESETS[];
  void applyPreset(const std::string& name);

  // "PRESET,PRESET,name=value,..." applied left to right on top of the default (simple1, none). Throws StringError.
  static Rules parse(const std::string& spec);
  // Every parameter as name=value, comma-separated.
  std::string toString() const;
  bool operator==(const Rules& other) const { return toString() == other.toString(); }
};

#endif // GAME_RULES_H_
