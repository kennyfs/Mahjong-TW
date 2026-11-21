#!/usr/bin/env python3
"""What a policy step really gained, from hands it was not trained on (docs/training.md, "The run s2 stopped
improving").

    RUN=share/s2 DATA=dir_with_iter_J python is_improve.py J K1 K2 ...

On the marked decisions of iteration J (played by a network whose probabilities mu are stored), the gain per decision
of playing network K's policy at that one decision instead: the mean of (pi_K(a) / mu(a) - 1) (G - mean G) over the
actions a played and their hands' results G, in typical payments. It is minus what the playing network gained over
K. With K the network that played, the ratio is 1 everywhere (a check of the stored probabilities). 600,000 samples
give a standard error of 0.00025 for a neighbouring network, a sixth of what train1.py's `improve` claims for a step.
"""
import sys, os, glob
import numpy as np, torch, onnxruntime as ort
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mahjong.data import DEFAULT_LIB, Library, batches
from mahjong.losses import payment_unit
J = int(sys.argv[1]); KS = [int(x) for x in sys.argv[2:]]
RUN = os.path.expandvars(os.environ.get("RUN", "$HOME/mahjong_remote/share/s2"))
DATA = os.environ.get("DATA", os.path.join(RUN, "selfplay"))
HERE = os.path.dirname(os.path.abspath(__file__))
M = os.path.join(RUN, "models", "actor_%d.onnx")
THREADS = int(os.environ.get("THREADS", "16"))  # the library's and onnxruntime's each
lib = Library(os.environ.get("LIB", DEFAULT_LIB), threads=THREADS)
files = sorted(glob.glob(os.path.join(DATA, f"iter_{J}", "*.npz")))
opt = ort.SessionOptions(); opt.intra_op_num_threads = THREADS
sess = {k: ort.InferenceSession(M % k, opt, providers=["CPUExecutionProvider"]) for k in KS}
acc = {k: [] for k in KS}; dif = {k: [] for k in KS}; kls = {k: [] for k in KS}; gs = []
MAXN = int(os.environ.get("MAXN", "10000000")); n = 0
for b in batches(lib, files, 4096, part="all", group=64, symmetries=False, drop_last=False, shuffle=False):
    legal = b["legal"] > 0
    mu = b["mu"].astype(np.float64); played = b["played"].astype(np.int64)
    idx = np.arange(len(played))
    unit = payment_unit({"coef": torch.from_numpy(b["coef"])}).numpy()
    g = b["info"][:, 2] / unit
    gs.append(g)
    mu_t = np.clip(mu[idx, played], 1e-4, None)
    x = {"kind": b["kind"].astype(np.float32), "seat": b["seat"].astype(np.float32), "global": b["global"].astype(np.float32)}
    for k in KS:
        logit = sess[k].run(["policy"], x)[0].astype(np.float64)
        logit[~legal] = -1e30
        logit -= logit.max(-1, keepdims=True)
        p = np.exp(logit); p /= p.sum(-1, keepdims=True)
        ratio = p[idx, played] / mu_t
        acc[k].append(np.stack([ratio, np.minimum(ratio, 5.0)], 1))
        dif[k].append(np.abs(p - mu).max(-1))
        kls[k].append((mu * (np.log(np.clip(mu, 1e-9, None)) - np.log(np.clip(p, 1e-9, None))) * legal).sum(-1))
    n += len(played)
    if n >= MAXN: break
g = np.concatenate(gs); gc = g - g.mean()
print(f"iter {J}: {len(g)} samples, result mean {g.mean():+.4f} sd {g.std():.3f}")
for k in KS:
    r = np.concatenate(acc[k]); d = np.concatenate(dif[k]); kl = np.concatenate(kls[k])
    out = []
    for c in (0, 1):
        v = (r[:, c] - 1.0) * gc          # the result centered: E[ratio - 1] = 0, so the mean does not change
        out.append(f"{v.mean():+.5f} +- {v.std() / np.sqrt(len(v)):.5f}")
    print(f"  network {k:3d} instead: gain {out[0]} (ratios capped at 5: {out[1]}); KL(mu||p) {kl.mean():.4f}, "
          f"mean ratio {r[:,0].mean():.3f}, max |p-mu| mean {d.mean():.4f}")
