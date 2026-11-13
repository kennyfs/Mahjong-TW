#include "../learn/sampling.h"

#include <algorithm>

using namespace std;

namespace {
  const char* pick(Rand& rand, const char* const names[], const double weights[], size_t n) {
    return names[rand.nextUInt(weights, n)];
  }
}

Rules Learn::sampleRules(const string& spec, Rand& rand) {
  if(spec != "mix")
    return Rules::parse(spec);
  static const char* const FLOWERS[3] = {"none", "see", "seat"};
  static const char* const TAI[5] = {"simple1", "simple2", "standard", "masters", "random"};
  static const double TAI_WEIGHTS[5] = {0.30, 0.30, 0.15, 0.10, 0.15};
  static const double FLOWER_WEIGHTS[5][3] = {
    {0.70, 0.25, 0.05}, {0.45, 0.50, 0.05}, {0.25, 0.25, 0.50}, {0.25, 0.25, 0.50}, {1.0, 1.0, 1.0}};
  const uint32_t t = rand.nextUInt(TAI_WEIGHTS, 5);
  const char* flowers = pick(rand, FLOWERS, FLOWER_WEIGHTS[t], 3);
  Rules r;
  if(t < 4) {
    r.applyPreset(TAI[t]);
    r.applyPreset(flowers);
  }
  else {
    // Values around a preset: every item of the tai table may be switched off, halved, set to the full table's
    // value or doubled.
    r.applyPreset(TAI[rand.nextUInt(4)]);
    r.applyPreset(flowers);
    Rules full;
    full.applyPreset("masters");
    full.applyPreset("seat");
    static const char* const KEPT[] = {"flowers", "base", "draw_streak", "multi_ron", "keep", "min_tai",
                                       "duting_loose", "dasixi_winds", "quanqiu_duting", "any_wind", "kong_tai",
                                       "ankong_tai", "no_honor_flower", "dealer"};
    for(const Rules::Param& p : Rules::params()) {
      if(!p.intField || rand.nextDouble() >= 0.2)
        continue;
      bool kept = false;
      for(const char* name : KEPT)
        kept = kept || string(name) == p.name;
      if(kept)
        continue;
      int ref = max(1, full.*p.intField);
      switch(rand.nextUInt(4)) {
        case 0: r.*p.intField = 0; break;
        case 1: r.*p.intField = (ref + 1) / 2; break;
        case 2: r.*p.intField = ref; break;
        default: r.*p.intField = 2 * ref; break;
      }
    }
    if(!r.hasFlowers())
      r.eightFlowers = r.sevenRobOne = 0;
  }
  // 報聽 (off in every preset, a setting the players may turn on) on in four fifths of the hands, as s3 was trained.
  if(rand.nextDouble() < 0.8)
    r.baoting = 1;
  // The settings the app offers, varied in a quarter of the hands.
  if(rand.nextDouble() < 0.25) {
    r.base = 1 + (int)rand.nextUInt(5);
    r.multiRon = (int)rand.nextUInt(2);
    r.streak = 1 + (int)rand.nextUInt(2);
    r.drawStreak = (int)rand.nextUInt(2);
    r.dutingLoose = (int)rand.nextUInt(2);
  }
  return r;
}

HandSetup Learn::sampleSetup(Rand& rand) {
  HandSetup setup;
  setup.dealer = (int)rand.nextUInt(4);
  setup.eastSeat = rand.nextDouble() < 0.9 ? setup.dealer : (int)rand.nextUInt(4);
  setup.roundWind = (int)rand.nextUInt(4);
  double u = rand.nextDouble();
  setup.streak = u < 0.60 ? 0 : u < 0.85 ? 1 : u < 0.95 ? 2 : 3;
  return setup;
}
