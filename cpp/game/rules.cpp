#include "../game/rules.h"

#include "../core/global.h"

#include <sstream>

using namespace std;

const vector<Rules::Param>& Rules::params() {
  static const vector<Param> PARAMS = {
    {"flowers", &Rules::flowers, nullptr},
    {"flower_tai", &Rules::flowerTai, nullptr},
    {"flower_set", &Rules::flowerSet, nullptr},
    {"eight_flowers", &Rules::eightFlowers, nullptr},
    {"seven_rob_one", &Rules::sevenRobOne, nullptr},
    {"dealer", &Rules::dealer, nullptr},
    {"streak", &Rules::streak, nullptr},
    {"menqing", &Rules::menqing, nullptr},
    {"zimo", &Rules::zimo, nullptr},
    {"menqing_zimo", &Rules::menqingZimo, nullptr},
    {"duting", &Rules::duting, nullptr},
    {"pinghu", &Rules::pinghu, nullptr},
    {"quanqiu", &Rules::quanqiu, nullptr},
    {"quanqiu_duting", &Rules::quanqiuDuting, nullptr},
    {"banqiu", &Rules::banqiu, nullptr},
    {"round_wind", &Rules::roundWind, nullptr},
    {"seat_wind", &Rules::seatWind, nullptr},
    {"dragon", &Rules::dragon, nullptr},
    {"xiaosanyuan", &Rules::xiaosanyuan, nullptr},
    {"dasanyuan", &Rules::dasanyuan, nullptr},
    {"xiaosixi", &Rules::xiaosixi, nullptr},
    {"dasixi", &Rules::dasixi, nullptr},
    {"dasixi_winds", &Rules::dasixiWinds, nullptr},
    {"pengpenghu", &Rules::pengpenghu, nullptr},
    {"sananke", &Rules::sananke, nullptr},
    {"sianke", &Rules::sianke, nullptr},
    {"wuanke", &Rules::wuanke, nullptr},
    {"hunyise", &Rules::hunyise, nullptr},
    {"qingyise", &Rules::qingyise, nullptr},
    {"ziyise", &Rules::ziyise, nullptr},
    {"qianggang", &Rules::qianggang, nullptr},
    {"gangshang", &Rules::gangshang, nullptr},
    {"haidi", &Rules::haidi, nullptr},
    {"hedi", &Rules::hedi, nullptr},
    {"tianhu", &Rules::tianhu, nullptr},
    {"dihu", &Rules::dihu, nullptr},
    {"renhu", &Rules::renhu, nullptr},
    {"any_wind", &Rules::anyWind, nullptr},
    {"no_honor_flower", &Rules::noHonorFlower, nullptr},
    {"kong_tai", &Rules::kongTai, nullptr},
    {"ankong_tai", &Rules::ankongTai, nullptr},
    {"base", nullptr, &Rules::base},
    {"draw_streak", &Rules::drawStreak, nullptr},
    {"multi_ron", &Rules::multiRon, nullptr},
    {"keep", &Rules::keep, nullptr},
    {"min_tai", &Rules::minTai, nullptr},
    {"duting_loose", &Rules::dutingLoose, nullptr},
  };
  return PARAMS;
}

double Rules::get(const string& name) const {
  for(const Param& p : params())
    if(name == p.name)
      return p.intField ? (double)(this->*p.intField) : this->*p.doubleField;
  throw StringError("Unknown rule parameter: " + name);
}

void Rules::set(const string& name, double value) {
  for(const Param& p : params()) {
    if(name != p.name)
      continue;
    if(p.intField) {
      int v = (int)value;
      if((double)v != value)
        throw StringError("Rule parameter " + name + " takes an integer: " + Global::doubleToString(value));
      this->*p.intField = v;
    }
    else {
      this->*p.doubleField = value;
    }
    return;
  }
  throw StringError("Unknown rule parameter: " + name);
}

const char* const Rules::TAI_PRESETS[] = {"simple1", "simple2", "standard", "masters", nullptr};
const char* const Rules::FLOWER_PRESETS[] = {"none", "see", "seat", nullptr};

void Rules::applyPreset(const string& name) {
  if(name == "simple1" || name == "simple2" || name == "standard" || name == "masters") {
    // Every tai item off, then the preset's (docs/rules.md section 9). The presets also set the base and whether a
    // discard can make several winners.
    dealer = 1;
    zimo = 1;
    menqing = menqingZimo = duting = qingyise = 0;
    eightFlowers = sevenRobOne = 0;
    pinghu = quanqiu = quanqiuDuting = banqiu = 0;
    roundWind = seatWind = dragon = xiaosanyuan = dasanyuan = xiaosixi = dasixi = dasixiWinds = 0;
    pengpenghu = sananke = sianke = wuanke = hunyise = ziyise = 0;
    qianggang = gangshang = haidi = hedi = tianhu = dihu = renhu = 0;
    anyWind = noHonorFlower = kongTai = ankongTai = 0;
    if(name == "simple1") {
      // Southern Taiwan: dealer, streak and self-draw only; base 2 tai; 截胡.
      streak = 1;
      base = 2;
      multiRon = 0;
      return;
    }
    menqing = menqingZimo = duting = 1;
    if(name == "simple2") {
      // The family rules: 門清一摸三, strict single wait, pure suit 1; 一炮多響.
      streak = 1;
      qingyise = 1;
      base = 1;
      multiRon = 1;
      return;
    }
    streak = 2;
    qingyise = 8;
    base = 1;
    multiRon = 0;
    eightFlowers = sevenRobOne = 8;
    pinghu = 2;
    quanqiu = 2;
    roundWind = seatWind = dragon = 1;
    xiaosanyuan = 4;
    dasanyuan = 8;
    xiaosixi = 8;
    dasixi = 16;
    pengpenghu = 4;
    sananke = 2;
    sianke = 5;
    wuanke = 8;
    hunyise = 4;
    ziyise = 8;
    qianggang = gangshang = haidi = hedi = 1;
    tianhu = 24;
    dihu = 16;
    if(name == "masters") {
      quanqiuDuting = 1;
      banqiu = 1;
      dasixiWinds = 1;
      ziyise = 16;
      tianhu = 16;
      renhu = 16;
    }
    return;
  }
  if(name == "none") {
    flowers = NO_FLOWERS;
    return;
  }
  if(name == "see" || name == "seat") {
    flowers = name == "see" ? SEE_FLOWERS : SEAT_FLOWERS;
    flowerTai = 1;
    flowerSet = name == "see" ? 0 : 2;
    return;
  }
  throw StringError("Unknown rules preset: " + name);
}

Rules Rules::parse(const string& spec) {
  Rules r;
  for(const string& rawTok : Global::split(spec, ',')) {
    string tok = Global::trim(rawTok);
    if(tok.empty())
      continue;
    size_t eq = tok.find('=');
    if(eq == string::npos) {
      r.applyPreset(tok);
      continue;
    }
    string name = Global::trim(tok.substr(0, eq));
    string value = Global::trim(tok.substr(eq + 1));
    double v;
    if(!Global::tryStringToDouble(value, v))
      throw StringError("Bad value for rule parameter " + name + ": " + value);
    r.set(name, v);
  }
  if(r.flowers < 0 || r.flowers > 2)
    throw StringError("flowers must be 0, 1 or 2");
  if(r.keep < 0 || r.keep > 60)
    throw StringError("keep must be between 0 and 60");
  if(r.base < 0)
    throw StringError("base must not be negative");
  return r;
}

string Rules::toString() const {
  ostringstream out;
  bool first = true;
  for(const Param& p : params()) {
    if(!first)
      out << ",";
    first = false;
    out << p.name << "=";
    if(p.intField)
      out << this->*p.intField;
    else
      out << this->*p.doubleField;
  }
  return out.str();
}
