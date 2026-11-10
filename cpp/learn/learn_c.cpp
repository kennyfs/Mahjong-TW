#include "../core/global.h"
#include "../learn/expand.h"

#include <cstring>
#include <mutex>
#include <thread>

// The C interface of libmahjong_data, loaded with ctypes by python/mahjong/data.py.

using namespace std;

extern "C" {
// The sizes of everything a batch holds, so that Python allocates by what this build says:
//   0 Features::VERSION, 1 actions, 2 kind obs, 3 seat obs, 4 global obs, 5 kind full, 6 seat full, 7 global full,
//   8 events, 9 info, 10 future, 11 tai items, 12 rule parameters, 13 rule features, 14 where the rules begin in
//   the global vector, 15 where the special actions' blocks begin, 16 their size.
void mj_sizes(int32_t* out) {
  out[0] = Features::VERSION;
  out[1] = Learn::NUM_ACTIONS;
  out[2] = Features::KIND_OBS;
  out[3] = Features::SEAT_OBS;
  out[4] = Features::globalObsSize();
  out[5] = Features::KIND_FULL;
  out[6] = Features::SEAT_FULL;
  out[7] = Features::GLOBAL_FULL;
  out[8] = Learn::NUM_EVENTS;
  out[9] = Learn::INFO_SIZE;
  out[10] = Learn::FUTURE_SIZE;
  out[11] = Tai::NUM_ITEMS;
  out[12] = (int32_t)Rules::params().size();
  out[13] = Features::NUM_RULE_FEATURES;
  out[14] = Features::GO_RULES;
  out[15] = Features::GO_ACTIONS;
  out[16] = Features::GA_SIZE;
}

// For each of the ten events of a seat's result: its bit in the 16 event bits, and whose tai it carries.
void mj_event_tables(int32_t* bits, int32_t* winners) {
  for(int e = 0; e < Learn::NUM_EVENTS; e++) {
    bits[e] = Learn::EVENT_BIT[e];
    winners[e] = Learn::EVENT_WINNER[e];
  }
}

// The arrays of a batch of n samples, each [n, ...] with the row sizes of Learn::Row.
struct MjBatch {
  float* kind;
  float* seat;
  float* global;
  float* kindFull;
  float* seatFull;
  float* globalFull;
  uint8_t* legal;
  int32_t* played;
  float* mu;
  uint8_t* known;
  uint16_t* events;
  uint8_t* tai;
  float* coef;
  float* info;
  uint8_t* oppHand;
  uint8_t* danger;
  uint8_t* dangerTai;
  uint8_t* oppShanten;
  uint8_t* nextChow;
  int8_t* winTile;
  uint8_t* taiItems;
  uint8_t* future;
  uint8_t* finalHand;
  int8_t* nextDiscard;
  int32_t* greedy;
};

// Expands n samples: sample i is mark markIdx[i] of hand handIdx[i] of a data file (its hands, offsets and rules
// arrays), seen through the symmetry drawn from symSeeds[i] (0: as recorded). Returns 0, or -1 with a message.
int mj_expand(const uint8_t* hands, const int64_t* offsets, const float* rules, int numRuleParams, int64_t n,
              const int32_t* handIdx, const int32_t* markIdx, const uint64_t* symSeeds, const MjBatch* b,
              int numThreads, char* error, int errorLen) {
  const int globalSize = Features::globalObsSize();
  mutex errorMutex;
  bool failed = false;
  auto fail = [&](const string& msg) {
    lock_guard<mutex> lock(errorMutex);
    if(!failed && error && errorLen > 0) {
      strncpy(error, msg.c_str(), (size_t)errorLen - 1);
      error[errorLen - 1] = 0;
    }
    failed = true;
  };
  auto worker = [&](int threadIdx) {
    for(int64_t i = threadIdx; i < n; i += numThreads) {
      try {
        const int64_t h = handIdx[i];
        const uint8_t* data = hands + offsets[h];
        const size_t len = (size_t)(offsets[h + 1] - offsets[h]);
        const Rules r =
          Learn::rulesFromVector(rules + (size_t)Learn::HandRecord::rulesIndexOf(data) * numRuleParams, numRuleParams);
        const Learn::HandRecord rec = Learn::HandRecord::parse(data, len, r);
        const Learn::Symmetry sym = symSeeds && symSeeds[i] ? Learn::Symmetry::random(symSeeds[i]) : Learn::Symmetry();
        Learn::Row row;
        row.kind = b->kind + i * 34 * Features::KIND_OBS;
        row.seat = b->seat + i * 4 * Features::SEAT_OBS;
        row.global = b->global + i * globalSize;
        row.kindFull = b->kindFull + i * 34 * Features::KIND_FULL;
        row.seatFull = b->seatFull + i * 4 * Features::SEAT_FULL;
        row.globalFull = b->globalFull + i * Features::GLOBAL_FULL;
        row.legal = b->legal + i * Learn::NUM_ACTIONS;
        row.played = b->played + i;
        row.mu = b->mu + i * Learn::NUM_ACTIONS;
        row.known = b->known + i * Learn::NUM_ACTIONS;
        row.events = b->events + i * Learn::NUM_ACTIONS;
        row.tai = b->tai + i * Learn::NUM_ACTIONS * 4;
        row.coef = b->coef + i * 2 * Learn::NUM_EVENTS;
        row.info = b->info + i * Learn::INFO_SIZE;
        row.oppHand = b->oppHand + i * 3 * 34;
        row.danger = b->danger + i * 3 * 34;
        row.dangerTai = b->dangerTai + i * 3 * 34;
        row.oppShanten = b->oppShanten + i * 3;
        row.nextChow = b->nextChow + i * 34;
        row.winTile = b->winTile + i * 4;
        row.taiItems = b->taiItems + i * Tai::NUM_ITEMS;
        row.future = b->future + i * Learn::FUTURE_SIZE;
        row.finalHand = b->finalHand + i * 34;
        row.nextDiscard = b->nextDiscard + i * 3;
        row.greedy = b->greedy + i;
        Learn::expand(rec, markIdx[i], sym, row);
      }
      catch(const exception& e) {
        fail(string("Sample ") + Global::int64ToString(i) + ": " + e.what());
        return;
      }
    }
  };
  if(numThreads <= 1) {
    numThreads = 1;
    worker(0);
  }
  else {
    vector<thread> threads;
    for(int t = 0; t < numThreads; t++)
      threads.emplace_back(worker, t);
    for(thread& t : threads)
      t.join();
  }
  return failed ? -1 : 0;
}
}
