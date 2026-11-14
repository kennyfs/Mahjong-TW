#include "../game/shape.h"

#include <algorithm>
#include <unordered_map>

using namespace std;

// ---------------------------------------------------------------------------------------------------------------------
// Winning

// Whether a[lo, lo + len) splits into sets only. Taking pungs first at the lowest kind is enough: three equal chows
// are three pungs.
static bool setsOnly(int* a, int len, bool runs) {
  int b[9];
  for(int i = 0; i < len; i++)
    b[i] = a[i];
  for(int i = 0; i < len; i++) {
    if(b[i] < 0)
      return false;
    int r = b[i] % 3;
    if(r == 0)
      continue;
    if(!runs || i + 2 >= len)
      return false;
    b[i + 1] -= r;
    b[i + 2] -= r;
  }
  return true;
}

bool Shape::isComplete(const Counts& c, int numSets) {
  int a[34];
  int suitTotal[4] = {0, 0, 0, 0};
  for(int k = 0; k < 34; k++) {
    a[k] = c[k];
    suitTotal[k / 9] += c[k];
  }
  if(suitTotal[0] + suitTotal[1] + suitTotal[2] + suitTotal[3] != 3 * numSets + 2)
    return false;
  // The pair is in the one group whose total is 2 mod 3; every other group's total is 0 mod 3.
  int pairSuit = -1;
  for(int s = 0; s < 4; s++) {
    int r = suitTotal[s] % 3;
    if(r == 1)
      return false;
    if(r == 2) {
      if(pairSuit >= 0)
        return false;
      pairSuit = s;
    }
  }
  if(pairSuit < 0)
    return false;
  for(int s = 0; s < 4; s++) {
    if(s == pairSuit)
      continue;
    if(!setsOnly(a + 9 * s, s == 3 ? 7 : 9, s < 3))
      return false;
  }
  int lo = 9 * pairSuit, len = pairSuit == 3 ? 7 : 9;
  for(int i = 0; i < len; i++) {
    if(a[lo + i] < 2)
      continue;
    a[lo + i] -= 2;
    bool ok = setsOnly(a + lo, len, pairSuit < 3);
    a[lo + i] += 2;
    if(ok)
      return true;
  }
  return false;
}

uint64_t Shape::rawWaits(const Counts& c, int numSets) {
  Counts d = c;
  uint64_t out = 0;
  for(int k = 0; k < 34; k++) {
    // A wait must be next to (or the same as) a tile in the hand.
    bool near = d[k] > 0;
    if(!near && k < 27) {
      int n = k % 9;
      for(int dd = -2; dd <= 2 && !near; dd++)
        if(dd != 0 && n + dd >= 0 && n + dd < 9 && d[k + dd] > 0)
          near = true;
    }
    if(!near)
      continue;
    d[k]++;
    if(isComplete(d, numSets))
      out |= (uint64_t)1 << k;
    d[k]--;
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------------
// Readings

// Groups containing the lowest remaining kind are taken in the order pair (0), pung (1), chow (2), so each multiset of
// groups is produced once.

static void readRec(int* a, int lastLow, int lastOrder, bool pairUsed, Shape::Reading& cur,
                    vector<Shape::Reading>& out) {
  int i = 0;
  while(i < 34 && a[i] == 0)
    i++;
  if(i == 34) {
    if(pairUsed)
      out.push_back(cur);
    return;
  }
  int minOrder = (i == lastLow) ? lastOrder : 0;
  auto push = [&](Shape::Group::Type t) {
    cur.groups[cur.n].type = t;
    cur.groups[cur.n].kind = (uint8_t)i;
    cur.n++;
  };
  // Pair.
  if(minOrder <= 0 && !pairUsed && a[i] >= 2) {
    a[i] -= 2;
    push(Shape::Group::PAIR);
    readRec(a, i, 0 + 1, true, cur, out);  // a second pair at i is impossible, so continue at pung
    cur.n--;
    a[i] += 2;
  }
  // Pung.
  if(minOrder <= 1 && a[i] >= 3) {
    a[i] -= 3;
    push(Shape::Group::PUNG);
    readRec(a, i, 2, pairUsed, cur, out);  // a second pung at i would need six
    cur.n--;
    a[i] += 3;
  }
  // Chow.
  if(i < 27 && i % 9 <= 6 && a[i + 1] > 0 && a[i + 2] > 0) {
    a[i]--;
    a[i + 1]--;
    a[i + 2]--;
    push(Shape::Group::CHOW);
    readRec(a, i, 2, pairUsed, cur, out);
    cur.n--;
    a[i]++;
    a[i + 1]++;
    a[i + 2]++;
  }
}

void Shape::readings(const Counts& c, int numSets, vector<Reading>& out) {
  out.clear();
  if(!isComplete(c, numSets))
    return;
  int a[34];
  for(int k = 0; k < 34; k++)
    a[k] = c[k];
  Reading cur;
  readRec(a, -1, 0, false, cur, out);
}

Counts Shape::waitTiles(const Counts& c, int numSets, uint64_t waits) {
  Counts out{};
  if(waits == 0)
    return out;
  // The groups (by type and kind) kept by every way to win, as counts: the least over the ways.
  constexpr int KEYS = 3 * 34;
  int kept[KEYS];
  bool any = false;
  vector<Reading> rds;
  for(int w = 0; w < 34; w++) {
    if(!(waits >> w & 1))
      continue;
    Counts d = c;
    d[w]++;
    readings(d, numSets, rds);
    for(const Reading& rd : rds) {
      for(int g = 0; g < rd.n; g++) {
        if(!rd.groups[g].contains(w))
          continue;
        int these[KEYS] = {0};
        for(int h = 0; h < rd.n; h++)
          if(h != g)
            these[rd.groups[h].type * 34 + rd.groups[h].kind]++;
        for(int i = 0; i < KEYS; i++)
          kept[i] = any ? min(kept[i], these[i]) : these[i];
        any = true;
      }
    }
  }
  out = c;
  if(!any)
    return out;
  for(int i = 0; i < KEYS; i++) {
    int k = i % 34;
    for(int n = 0; n < kept[i]; n++) {
      switch(i / 34) {
        case Group::CHOW: out[k]--; out[k + 1]--; out[k + 2]--; break;
        case Group::PUNG: out[k] -= 3; break;
        default: out[k] -= 2; break;
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------------------------------
// Shanten

namespace {
  // For one suit (or the honors): the most partial sets (taatsu: two tiles needing one more for a set, a spare pair
  // included) for each number of pairs (0 or 1, the hand's pair) and sets (0 to 5), or -1 when impossible.
  struct GroupInfo {
    int8_t best[2][6];
  };

  struct ShantenCache {
    unordered_map<uint32_t, GroupInfo> map;
  };

  uint32_t codeOf(const int* a, int len, bool runs) {
    uint32_t code = 0;
    for(int i = 0; i < len; i++)
      code = code * 5 + (uint32_t)a[i];
    return code * 2 + (runs ? 1 : 0);
  }

  void mergeShifted(GroupInfo& res, const GroupInfo& sub, int dp, int dm, int dt) {
    for(int p = 0; p + dp < 2; p++)
      for(int m = 0; m + dm < 6; m++) {
        int v = sub.best[p][m];
        if(v < 0)
          continue;
        int nv = min(v + dt, 100);
        if(nv > res.best[p + dp][m + dm])
          res.best[p + dp][m + dm] = (int8_t)nv;
      }
  }

  const GroupInfo& groupInfo(ShantenCache& cache, int* a, int len, bool runs) {
    uint32_t code = codeOf(a, len, runs);
    auto it = cache.map.find(code);
    if(it != cache.map.end())
      return it->second;
    GroupInfo res;
    for(int p = 0; p < 2; p++)
      for(int m = 0; m < 6; m++)
        res.best[p][m] = -1;
    int i = 0;
    while(i < len && a[i] == 0)
      i++;
    if(i == len) {
      res.best[0][0] = 0;
    }
    else {
      // The lowest tile is left alone (an isolated tile).
      a[i]--;
      mergeShifted(res, groupInfo(cache, a, len, runs), 0, 0, 0);
      a[i]++;
      if(a[i] >= 3) {
        a[i] -= 3;
        mergeShifted(res, groupInfo(cache, a, len, runs), 0, 1, 0);
        a[i] += 3;
      }
      if(a[i] >= 2) {
        a[i] -= 2;
        const GroupInfo& sub = groupInfo(cache, a, len, runs);
        mergeShifted(res, sub, 1, 0, 0);  // the hand's pair
        mergeShifted(res, sub, 0, 0, 1);  // a partial pung
        a[i] += 2;
      }
      if(runs && i + 2 < len && a[i + 1] > 0 && a[i + 2] > 0) {
        a[i]--;
        a[i + 1]--;
        a[i + 2]--;
        mergeShifted(res, groupInfo(cache, a, len, runs), 0, 1, 0);
        a[i]++;
        a[i + 1]++;
        a[i + 2]++;
      }
      if(runs && i + 1 < len && a[i + 1] > 0) {
        a[i]--;
        a[i + 1]--;
        mergeShifted(res, groupInfo(cache, a, len, runs), 0, 0, 1);
        a[i]++;
        a[i + 1]++;
      }
      if(runs && i + 2 < len && a[i + 2] > 0) {
        a[i]--;
        a[i + 2]--;
        mergeShifted(res, groupInfo(cache, a, len, runs), 0, 0, 1);
        a[i]++;
        a[i + 2]++;
      }
    }
    return cache.map.emplace(code, res).first->second;
  }
}

int Shape::shanten(const Counts& c, int numSets) {
  thread_local ShantenCache cache;
  if(cache.map.size() > 4000000)
    cache.map.clear();
  // comb[p][m]: the most partial sets over the groups so far.
  int comb[2][6];
  for(int p = 0; p < 2; p++)
    for(int m = 0; m < 6; m++)
      comb[p][m] = -1;
  comb[0][0] = 0;
  int a[9];
  for(int s = 0; s < 4; s++) {
    int len = s == 3 ? 7 : 9;
    for(int i = 0; i < len; i++)
      a[i] = c[9 * s + i];
    GroupInfo gi = groupInfo(cache, a, len, s < 3);
    int next[2][6];
    for(int p = 0; p < 2; p++)
      for(int m = 0; m < 6; m++)
        next[p][m] = -1;
    for(int p1 = 0; p1 < 2; p1++)
      for(int m1 = 0; m1 < 6; m1++) {
        if(comb[p1][m1] < 0)
          continue;
        for(int p2 = 0; p1 + p2 < 2; p2++)
          for(int m2 = 0; m1 + m2 < 6; m2++) {
            int t2 = gi.best[p2][m2];
            if(t2 < 0)
              continue;
            int v = comb[p1][m1] + t2;
            if(v > next[p1 + p2][m1 + m2])
              next[p1 + p2][m1 + m2] = v;
          }
      }
    for(int p = 0; p < 2; p++)
      for(int m = 0; m < 6; m++)
        comb[p][m] = next[p][m];
  }
  int best = 100;
  for(int p = 0; p < 2; p++)
    for(int m = 0; m <= numSets && m < 6; m++) {
      if(comb[p][m] < 0)
        continue;
      int need = numSets - m;
      int s = 2 * need - min(comb[p][m], need) - p;
      best = min(best, s);
    }
  return best;
}
