#include "../command/commands.h"

#include "../core/global.h"
#include "../game/shape.h"
#include "../game/tai.h"

#include <tclap/CmdLine.h>

#include <algorithm>
#include <iostream>

using namespace std;

static int parseWind(const string& s) {
  if(s == "E" || s == "e" || s == "0")
    return 0;
  if(s == "S" || s == "s" || s == "1")
    return 1;
  if(s == "W" || s == "w" || s == "2")
    return 2;
  if(s == "N" || s == "n" || s == "3")
    return 3;
  throw StringError("Bad wind: " + s + " (E, S, W, N)");
}

// "pon:555p", "chi:345m", "kan:1111z", "ankan:9999s", "kakan:2222p".
static Meld parseMeld(const string& s) {
  size_t colon = s.find(':');
  if(colon == string::npos)
    throw StringError("Bad meld (TYPE:TILES): " + s);
  string type = s.substr(0, colon);
  vector<int> t = Tiles::parse(s.substr(colon + 1));
  Meld m;
  m.from = -1;
  if(type == "chi") {
    if(t.size() != 3)
      throw StringError("A chow has 3 tiles: " + s);
    sort(t.begin(), t.end());
    if(Tiles::isHonor(t[0]) || t[1] != t[0] + 1 || t[2] != t[0] + 2 || Tiles::suitOf(t[0]) != Tiles::suitOf(t[2]))
      throw StringError("Not a chow: " + s);
    m.type = Meld::CHI;
    m.kind = m.called = (uint8_t)t[0];
    return m;
  }
  Meld::Type mt;
  size_t n;
  if(type == "pon") {
    mt = Meld::PON;
    n = 3;
  }
  else if(type == "kan") {
    mt = Meld::MINKAN;
    n = 4;
  }
  else if(type == "ankan") {
    mt = Meld::ANKAN;
    n = 4;
  }
  else if(type == "kakan") {
    mt = Meld::KAKAN;
    n = 4;
  }
  else
    throw StringError("Unknown meld type: " + type);
  if(t.size() != n)
    throw StringError("Wrong number of tiles: " + s);
  for(int x : t)
    if(x != t[0])
      throw StringError("Not one kind: " + s);
  m.type = mt;
  m.kind = m.called = (uint8_t)t[0];
  return m;
}

int Commands::tai(vector<string> args) {
  string handStr, meldsStr, winStr, rulesSpec, seatWind, roundWind, flowersStr;
  bool tsumo, robbed, last, replacement, heavenly, earthly, human;
  try {
    TCLAP::CmdLine cmd("Scores a winning hand (docs/rules.md section 9).", ' ', "0");
    TCLAP::ValueArg<string> handArg("", "hand", "The concealed tiles, winning tile included, e.g. 123m456p789s11z",
                                    true, "", "TILES");
    TCLAP::ValueArg<string> meldsArg("", "melds", "Melds, e.g. \"pon:555p chi:345m kan:1111z ankan:9999s\"", false,
                                     "", "MELDS");
    TCLAP::ValueArg<string> winArg("", "win", "The winning tile", true, "", "TILE");
    TCLAP::ValueArg<string> rulesArg("", "rules", "Rules spec (default simple,none)", false, "simple,none", "SPEC");
    TCLAP::ValueArg<string> seatArg("", "seat-wind", "Seat wind E/S/W/N (default E)", false, "E", "WIND");
    TCLAP::ValueArg<string> roundArg("", "round-wind", "Round wind E/S/W/N (default E)", false, "E", "WIND");
    TCLAP::ValueArg<string> flowersArg("", "flowers", "Flowers held, e.g. 1f5f", false, "", "TILES");
    TCLAP::SwitchArg tsumoArg("", "tsumo", "Self-drawn", false);
    TCLAP::SwitchArg robbedArg("", "robbed", "Won by robbing a kong", false);
    TCLAP::SwitchArg lastArg("", "last", "On the last tile (self-draw) or the last discard", false);
    TCLAP::SwitchArg replArg("", "replacement", "Self-drawn on a replacement after a flower or kong", false);
    TCLAP::SwitchArg heavenArg("", "heavenly", "Heavenly hand", false);
    TCLAP::SwitchArg earthArg("", "earthly", "Earthly hand", false);
    TCLAP::SwitchArg humanArg("", "human", "Human hand", false);
    cmd.add(handArg);
    cmd.add(meldsArg);
    cmd.add(winArg);
    cmd.add(rulesArg);
    cmd.add(seatArg);
    cmd.add(roundArg);
    cmd.add(flowersArg);
    cmd.add(tsumoArg);
    cmd.add(robbedArg);
    cmd.add(lastArg);
    cmd.add(replArg);
    cmd.add(heavenArg);
    cmd.add(earthArg);
    cmd.add(humanArg);
    cmd.parse(args);
    handStr = handArg.getValue();
    meldsStr = meldsArg.getValue();
    winStr = winArg.getValue();
    rulesSpec = rulesArg.getValue();
    seatWind = seatArg.getValue();
    roundWind = roundArg.getValue();
    flowersStr = flowersArg.getValue();
    tsumo = tsumoArg.getValue();
    robbed = robbedArg.getValue();
    last = lastArg.getValue();
    replacement = replArg.getValue();
    heavenly = heavenArg.getValue();
    earthly = earthArg.getValue();
    human = humanArg.getValue();
  }
  catch(TCLAP::ArgException& e) {
    cerr << "Error: " << e.error() << " for argument " << e.argId() << endl;
    return 1;
  }
  Rules rules = Rules::parse(rulesSpec);
  Tai::WinInfo w;
  w.concealed = Tiles::countsOf(Tiles::parse(handStr));
  for(const string& ms : Global::split(meldsStr))
    w.melds.push_back(parseMeld(ms));
  vector<int> win = Tiles::parse(winStr);
  if(win.size() != 1)
    throw StringError("--win takes one tile");
  w.winTile = win[0];
  if(w.concealed[w.winTile] == 0)
    throw StringError("The winning tile must be in --hand");
  for(int f : Tiles::parse(flowersStr)) {
    if(!Tiles::isFlower(f))
      throw StringError("--flowers takes flowers (1f-8f)");
    w.flowers |= (uint8_t)(1 << (f - Tiles::FLOWER_BASE));
  }
  w.selfDraw = tsumo;
  w.robbedKong = robbed;
  w.lastTile = last;
  w.replacement = replacement;
  w.heavenly = heavenly;
  w.earthly = earthly;
  w.human = human;
  w.seatWind = parseWind(seatWind);
  w.roundWind = parseWind(roundWind);
  int numSets = 5 - (int)w.melds.size();
  if(Tiles::total(w.concealed) != 3 * numSets + 2)
    throw StringError("The hand needs " + Global::intToString(3 * numSets + 2) + " tiles with " +
                      Global::intToString((int)w.melds.size()) + " melds");
  if(!Shape::isComplete(w.concealed, numSets)) {
    cout << "Not a winning hand." << endl;
    return 1;
  }
  Counts before = w.concealed;
  before[w.winTile]--;
  uint64_t waits = Tai::waits(before, w.melds);
  vector<int> waitTiles;
  for(int k = 0; k < Tiles::NUM_KINDS; k++)
    if(waits & ((uint64_t)1 << k))
      waitTiles.push_back(k);
  cout << "Waits before the winning tile: " << Tiles::toString(waitTiles) << endl;
  cout << Tai::compute(rules, w).toString() << endl;
  return 0;
}
