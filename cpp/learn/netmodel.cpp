#include "../learn/netmodel.h"

#include "../core/global.h"

#include <cstring>
#include <map>
#include <mutex>

#ifdef USE_ONNXRUNTIME
#include <onnxruntime_cxx_api.h>
#endif

using namespace std;

void Learn::NetOutput::compose(const float coefP[NUM_EVENTS], const float coefT[NUM_EVENTS],
                               float q[NUM_ACTIONS]) const {
  for(int a = 0; a < NUM_ACTIONS; a++) {
    float sum = 0.0f;
    for(int e = 0; e < NUM_EVENTS; e++)
      sum += events[a][e] * (coefP[e] + tai[a][e] * coefT[e]);
    q[a] = sum;
  }
}

shared_ptr<const Learn::NetModel> Learn::NetModel::load(const string& path) {
  static mutex cacheMutex;
  static map<string, shared_ptr<const NetModel>> cache;
  lock_guard<mutex> lock(cacheMutex);
  auto it = cache.find(path);
  if(it != cache.end())
    return it->second;
  shared_ptr<const NetModel> model = make_shared<NetModel>(path);
  cache[path] = model;
  return model;
}

#ifdef USE_ONNXRUNTIME

struct Learn::NetModel::Impl {
  unique_ptr<Ort::Session> session;
};

static Ort::Env& ortEnv() {
  static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "mahjong");
  return env;
}

bool Learn::NetModel::available() {
  return true;
}

Learn::NetModel::NetModel(const string& path) : path_(path), impl_(new Impl()) {
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(1);
  options.SetInterOpNumThreads(1);
  options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
  // The arena allocator takes a lock on every allocation, which the threads sharing a session fight over.
  options.DisableCpuMemArena();
  try {
    impl_->session = make_unique<Ort::Session>(ortEnv(), path.c_str(), options);
    // The inputs must be this build's features.
    const int64_t want[3][2] = {{Features::NUM_KINDS, Features::KIND_OBS},
                                {Features::NUM_SEATS, Features::SEAT_OBS},
                                {Features::globalObsSize(), 0}};
    if(impl_->session->GetInputCount() != 3)
      throw StringError("expected the inputs kind, seat, global");
    for(size_t i = 0; i < 3; i++) {
      vector<int64_t> shape = impl_->session->GetInputTypeInfo(i).GetTensorTypeAndShapeInfo().GetShape();
      bool ok = shape.size() == (i < 2 ? 3u : 2u) && shape[1] == want[i][0] && (i == 2 || shape[2] == want[i][1]);
      if(!ok)
        throw StringError("its inputs are not this build's features (version " +
                          Global::intToString(Features::VERSION) + ")");
    }
  }
  catch(const Ort::Exception& e) {
    delete impl_;
    throw StringError("Could not load model " + path + ": " + e.what());
  }
  catch(const StringError& e) {
    delete impl_;
    throw StringError("Could not load model " + path + ": " + e.what());
  }
}

Learn::NetModel::~NetModel() {
  delete impl_;
}

void Learn::NetModel::evaluate(int n, const float* kind, const float* seat, const float* global,
                               NetOutput* out) const {
  if(n <= 0)
    return;
  const int globalSize = Features::globalObsSize();
  Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeCPU);
  const int64_t kindShape[3] = {n, Features::NUM_KINDS, Features::KIND_OBS};
  const int64_t seatShape[3] = {n, Features::NUM_SEATS, Features::SEAT_OBS};
  const int64_t globalShape[2] = {n, globalSize};
  Ort::Value inputs[3] = {
    Ort::Value::CreateTensor<float>(mem, const_cast<float*>(kind),
                                    (size_t)n * Features::NUM_KINDS * Features::KIND_OBS, kindShape, 3),
    Ort::Value::CreateTensor<float>(mem, const_cast<float*>(seat),
                                    (size_t)n * Features::NUM_SEATS * Features::SEAT_OBS, seatShape, 3),
    Ort::Value::CreateTensor<float>(mem, const_cast<float*>(global), (size_t)n * globalSize, globalShape, 2),
  };
  static const char* inputNames[3] = {"kind", "seat", "global"};
  static const char* outputNames[6] = {"policy", "events", "tai", "ready", "danger", "danger_tai"};
  try {
    vector<Ort::Value> outputs = impl_->session->Run(Ort::RunOptions{nullptr}, inputNames, inputs, 3, outputNames, 6);
    const float* src[6];
    for(int i = 0; i < 6; i++)
      src[i] = outputs[i].GetTensorData<float>();
    const size_t sizes[6] = {sizeof(out->policy), sizeof(out->events),    sizeof(out->tai),
                             sizeof(out->ready),  sizeof(out->danger),    sizeof(out->dangerTai)};
    for(int i = 0; i < n; i++) {
      void* dst[6] = {out[i].policy, out[i].events, out[i].tai, out[i].ready, out[i].danger, out[i].dangerTai};
      for(int j = 0; j < 6; j++)
        memcpy(dst[j], (const char*)src[j] + (size_t)i * sizes[j], sizes[j]);
    }
  }
  catch(const Ort::Exception& e) {
    throw StringError("Error running model " + path_ + ": " + e.what());
  }
}

#else

struct Learn::NetModel::Impl {};

bool Learn::NetModel::available() {
  return false;
}

Learn::NetModel::NetModel(const string& path) : path_(path), impl_(nullptr) {
  throw StringError("This build has no ONNX Runtime; configure with -DONNXRUNTIME_ROOT=... to use " + path);
}

Learn::NetModel::~NetModel() {}

void Learn::NetModel::evaluate(int, const float*, const float*, const float*, NetOutput*) const {
  throw StringError("This build has no ONNX Runtime");
}

#endif
