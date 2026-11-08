#include "../game/tai.h"

#include "../game/shape.h"

#include <sstream>

using namespace std;

vector<int> Meld::tiles() const {
  if(type == CHI)
    return {kind, kind + 1, kind + 2};
  if(isKong())
    return {kind, kind, kind, kind};
  return {kind, kind, kind};
}

string Meld::toString() const {
  static const char* const TYPES[5] = {"chi", "pon", "kan", "ankan", "kakan"};
  return string(TYPES[type]) + ":" + Tiles::toString(tiles());
}

const char* const Tai::NAMES[Tai::NUM_ITEMS] = {
  "莊家", "連莊拉莊", "門清", "自摸", "不求人", "獨聽", "平胡", "全求人", "半求", "圈風", "門風", "三元牌",
  "小三元", "大三元", "小四喜", "大四喜", "碰碰胡", "三暗刻", "四暗刻", "五暗刻", "混一色", "清一色", "字一色",
  "搶槓", "槓上開花", "海底撈月", "河底撈魚", "天胡", "地胡", "人胡", "見風見台", "無字無花", "明槓", "暗槓",
  "花牌", "花槓", "八仙過海", "七搶一",
};

const char* const Tai::PARAM_NAMES[Tai::NUM_ITEMS] = {
  "dealer", "streak", "menqing", "zimo", "menqing_zimo", "duting", "pinghu", "quanqiu", "banqiu", "round_wind",
  "seat_wind", "dragon", "xiaosanyuan", "dasanyuan", "xiaosixi", "dasixi", "pengpenghu", "sananke", "sianke", "wuanke",
  "hunyise", "qingyise", "ziyise", "qianggang", "gangshang", "haidi", "hedi", "tianhu", "dihu", "renhu", "any_wind",
  "no_honor_flower", "kong_tai", "ankong_tai", "flower_tai", "flower_set", "eight_flowers", "seven_rob_one",
};

string Tai::Result::toString() const {
  ostringstream out;
  for(size_t i = 0; i < items.size(); i++) {
    if(i > 0)
      out << " ";
    out << NAMES[items[i].first] << items[i].second;
  }
  out << " = " << total;
  return out.str();
}

static int popcount64(uint64_t x) { return __builtin_popcountll(x); }

uint64_t Tai::waits(const Counts& concealed, const vector<Meld>& melds) {
  int numSets = 5 - (int)melds.size();
  uint64_t w = Shape::rawWaits(concealed, numSets);
  int held[34] = {0};
  for(int k = 0; k < 34; k++)
    held[k] = concealed[k];
  for(const Meld& m : melds)
    for(int t : m.tiles())
      held[t]++;
  for(int k = 0; k < 34; k++)
    if(held[k] >= 4)
      w &= ~((uint64_t)1 << k);
  return w;
}

namespace {
  enum WaitShape { TWO_SIDED, EDGE, CLOSED, SINGLE, PUNG_WAIT };

  struct Scorer {
    const Rules& r;
    Tai::Result res;
    explicit Scorer(const Rules& rules) : r(rules) {}
    void add(Tai::Item item, int tai) {
      if(tai == 0)
        return;
      res.items.push_back({item, tai});
      res.total += tai;
    }
  };
}

Tai::Result Tai::compute(const Rules& r, const WinInfo& w) {
  int numSets = 5 - (int)w.melds.size();
  vector<Shape::Reading> readings;
  Shape::readings(w.concealed, numSets, readings);
  if(readings.empty())
    return Result();

  // Facts that do not depend on the reading.
  bool hasHonor = false;
  int suits = 0;
  for(int k = 0; k < 34; k++) {
    if(w.concealed[k] == 0)
      continue;
    if(Tiles::isHonor(k))
      hasHonor = true;
    else
      suits |= 1 << Tiles::suitOf(k);
  }
  int openMelds = 0, openKongs = 0, ankans = 0, chiMelds = 0;
  for(const Meld& m : w.melds) {
    if(Tiles::isHonor(m.kind))
      hasHonor = true;
    else
      suits |= 1 << Tiles::suitOf(m.kind);
    if(m.isOpen())
      openMelds++;
    if(m.type == Meld::ANKAN)
      ankans++;
    else if(m.isKong())
      openKongs++;
    if(m.type == Meld::CHI)
      chiMelds++;
  }
  bool menqing = openMelds == 0;
  int numFlowers = __builtin_popcount(w.flowers);
  Counts before = w.concealed;
  before[w.winTile]--;
  int numWaits = popcount64(waits(before, w.melds));
  bool allMelded = w.melds.size() == 5 && openMelds == 5;

  Result best;
  best.total = -1;
  for(const Shape::Reading& rd : readings) {
    for(int g = 0; g < rd.n; g++) {
      const Shape::Group& grp = rd.groups[g];
      if(!grp.contains(w.winTile))
        continue;
      bool dup = false;
      for(int h = 0; h < g; h++)
        if(rd.groups[h].type == grp.type && rd.groups[h].kind == grp.kind)
          dup = true;
      if(dup)
        continue;

      WaitShape shape;
      if(grp.type == Shape::Group::PAIR)
        shape = SINGLE;
      else if(grp.type == Shape::Group::PUNG)
        shape = PUNG_WAIT;
      else {
        int pos = w.winTile - grp.kind;
        if(pos == 1)
          shape = CLOSED;
        else if(pos == 0)
          shape = (grp.kind % 9 == 6) ? EDGE : TWO_SIDED;
        else
          shape = (grp.kind % 9 == 0) ? EDGE : TWO_SIDED;
      }

      int chows = chiMelds, pungsInHand = 0, pairKind = -1;
      int windPungs = 0, dragonPungs = 0, windMask = 0;
      auto notePung = [&](int k) {
        if(Tiles::isWind(k)) {
          windPungs++;
          windMask |= 1 << (k - Tiles::EAST);
        }
        else if(Tiles::isDragon(k)) {
          dragonPungs++;
        }
      };
      for(int h = 0; h < rd.n; h++) {
        const Shape::Group& x = rd.groups[h];
        if(x.type == Shape::Group::CHOW)
          chows++;
        else if(x.type == Shape::Group::PUNG) {
          pungsInHand++;
          notePung(x.kind);
        }
        else
          pairKind = x.kind;
      }
      for(const Meld& m : w.melds)
        if(m.isPung())
          notePung(m.kind);
      int concealedPungs = pungsInHand + ankans - ((shape == PUNG_WAIT && !w.selfDraw) ? 1 : 0);
      bool duting = r.dutingLoose ? (shape == SINGLE || shape == EDGE || shape == CLOSED) : numWaits == 1;

      Scorer s(r);
      // Concealed, self-draw, and the hands that replace them.
      bool tianhu = w.heavenly && r.tianhu > 0;
      bool dihu = w.earthly && r.dihu > 0;
      bool renhu = w.human && r.renhu > 0;
      if(tianhu)
        s.add(TIANHU, r.tianhu);
      else if(dihu)
        s.add(DIHU, r.dihu);
      else if(renhu)
        s.add(RENHU, r.renhu);
      if(!tianhu && !dihu && !renhu && menqing)
        s.add(MENQING, r.menqing);
      if(!tianhu && !dihu && w.selfDraw)
        s.add(ZIMO, r.zimo);
      if(!tianhu && !dihu && menqing && w.selfDraw)
        s.add(MENQING_ZIMO, r.menqingZimo);
      // Waits.
      bool quanqiu = allMelded && !w.selfDraw && r.quanqiu > 0;
      if(quanqiu)
        s.add(QUANQIU, r.quanqiu);
      if(allMelded && w.selfDraw)
        s.add(BANQIU, r.banqiu);
      if(duting && (!quanqiu || r.quanqiuDuting))
        s.add(DUTING, r.duting);
      if(chows == 5 && !hasHonor && numFlowers == 0 && !w.selfDraw && shape == TWO_SIDED &&
         (r.dutingLoose || numWaits >= 2))
        s.add(PINGHU, r.pinghu);
      // Winds and dragons.
      bool dasixi = windPungs == 4 && r.dasixi > 0;
      if(r.anyWind > 0) {
        s.add(ANY_WIND, r.anyWind * windPungs);
      }
      else if(!dasixi || r.dasixiWinds) {
        if(windMask & (1 << w.roundWind))
          s.add(ROUND_WIND, r.roundWind);
        if(windMask & (1 << w.seatWind))
          s.add(SEAT_WIND, r.seatWind);
      }
      if(dasixi)
        s.add(DASIXI, r.dasixi);
      else if(windPungs == 3 && pairKind >= 0 && Tiles::isWind(pairKind))
        s.add(XIAOSIXI, r.xiaosixi);
      if(dragonPungs == 3 && r.dasanyuan > 0)
        s.add(DASANYUAN, r.dasanyuan);
      else if(dragonPungs == 2 && pairKind >= 0 && Tiles::isDragon(pairKind) && r.xiaosanyuan > 0)
        s.add(XIAOSANYUAN, r.xiaosanyuan);
      else
        s.add(DRAGON, r.dragon * dragonPungs);
      // Pungs.
      if(chows == 0)
        s.add(PENGPENGHU, r.pengpenghu);
      if(concealedPungs >= 5)
        s.add(WUANKE, r.wuanke);
      else if(concealedPungs == 4)
        s.add(SIANKE, r.sianke);
      else if(concealedPungs == 3)
        s.add(SANANKE, r.sananke);
      // Suits.
      if(suits == 0)
        s.add(ZIYISE, r.ziyise);
      else if(__builtin_popcount(suits) == 1)
        s.add(hasHonor ? HUNYISE : QINGYISE, hasHonor ? r.hunyise : r.qingyise);
      // How it was won.
      if(w.robbedKong)
        s.add(QIANGGANG, r.qianggang);
      if(w.selfDraw && w.lastTile)
        s.add(HAIDI, r.haidi);
      else if(w.selfDraw && w.replacement)
        s.add(GANGSHANG, r.gangshang);
      if(!w.selfDraw && w.lastTile)
        s.add(HEDI, r.hedi);
      // Flowers and kongs.
      if(r.flowers == Rules::SEE_FLOWERS) {
        s.add(FLOWER, r.flowerTai * numFlowers);
      }
      else if(r.flowers == Rules::SEAT_FLOWERS) {
        int own = ((w.flowers >> w.seatWind) & 1) + ((w.flowers >> (w.seatWind + 4)) & 1);
        s.add(FLOWER, r.flowerTai * own);
        int sets = ((w.flowers & 0x0F) == 0x0F ? 1 : 0) + ((w.flowers & 0xF0) == 0xF0 ? 1 : 0);
        s.add(FLOWER_SET, r.flowerSet * sets);
      }
      if(!hasHonor && numFlowers == 0)
        s.add(NO_HONOR_FLOWER, r.noHonorFlower);
      s.add(KONG, r.kongTai * openKongs);
      s.add(ANKONG, r.ankongTai * ankans);

      if(s.res.total > best.total)
        best = s.res;
    }
  }
  return best;
}
