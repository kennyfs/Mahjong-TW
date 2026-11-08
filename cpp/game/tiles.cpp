#include "../game/tiles.h"

#include "../core/global.h"

#include <algorithm>

using namespace std;

static const char SUIT_CHARS[5] = {'m', 'p', 's', 'z', 'f'};

string Tiles::name(int t) {
  if(t < 0 || t >= NUM_ALL)
    return "??";
  if(isFlower(t))
    return string(1, (char)('1' + (t - FLOWER_BASE))) + "f";
  return string(1, (char)('1' + t % 9)) + SUIT_CHARS[t / 9];
}

string Tiles::chineseName(int t) {
  static const char* const NUMS[9] = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
  static const char* const SUITS[3] = {"萬", "筒", "條"};
  static const char* const HONORS[7] = {"東", "南", "西", "北", "白", "發", "中"};
  static const char* const FLOWERS[8] = {"春", "夏", "秋", "冬", "梅", "蘭", "菊", "竹"};
  if(t < 0 || t >= NUM_ALL)
    return "??";
  if(isFlower(t))
    return FLOWERS[t - FLOWER_BASE];
  if(isHonor(t))
    return HONORS[t - 27];
  return string(NUMS[t % 9]) + SUITS[t / 9];
}

vector<int> Tiles::parse(const string& s) {
  vector<int> out;
  vector<int> digits;
  for(char ch : s) {
    if(ch == ' ' || ch == ',' || ch == '\t')
      continue;
    if(ch >= '1' && ch <= '9') {
      digits.push_back(ch - '1');
      continue;
    }
    int suit = -1;
    for(int i = 0; i < 5; i++)
      if(ch == SUIT_CHARS[i])
        suit = i;
    if(suit < 0)
      throw StringError("Bad tile character '" + string(1, ch) + "' in: " + s);
    if(digits.empty())
      throw StringError("Suit letter without digits in: " + s);
    for(int d : digits) {
      if(suit == 3 && d >= 7)
        throw StringError("Honors are 1z-7z: " + s);
      if(suit == 4 && d >= 8)
        throw StringError("Flowers are 1f-8f: " + s);
      out.push_back(suit == 4 ? FLOWER_BASE + d : suit * 9 + d);
    }
    digits.clear();
  }
  if(!digits.empty())
    throw StringError("Digits without a suit letter in: " + s);
  return out;
}

string Tiles::toString(const vector<int>& tiles) {
  vector<int> sorted = tiles;
  sort(sorted.begin(), sorted.end());
  string out;
  for(int suit = 0; suit < 5; suit++) {
    string digits;
    for(int t : sorted) {
      int ts = isFlower(t) ? 4 : t / 9;
      if(ts == suit)
        digits += (char)('1' + (isFlower(t) ? t - FLOWER_BASE : t % 9));
    }
    if(!digits.empty())
      out += digits + SUIT_CHARS[suit];
  }
  return out;
}

Counts Tiles::countsOf(const vector<int>& tiles) {
  Counts c{};
  for(int t : tiles) {
    if(t < 0 || t >= NUM_KINDS)
      throw StringError("Not a suit or honor tile: " + name(t));
    c[t]++;
  }
  return c;
}

vector<int> Tiles::tilesOf(const Counts& c) {
  vector<int> out;
  for(int k = 0; k < NUM_KINDS; k++)
    for(int i = 0; i < c[k]; i++)
      out.push_back(k);
  return out;
}

string Tiles::toString(const Counts& c) { return toString(tilesOf(c)); }

int Tiles::total(const Counts& c) {
  int n = 0;
  for(int k = 0; k < NUM_KINDS; k++)
    n += c[k];
  return n;
}
