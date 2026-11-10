# Training

How the network is trained, as commands. The design and its reasons are in [plan.md](plan.md); this file is the
manual and the log of what was measured.

Two machines take part: the **self-play machine** (many CPU threads; it also makes the data of stage 0 and plays
the evaluation matches) and the **training machine** (a GPU). They can be one machine. Paths below are examples.

## Setup

Both machines need this repository and the data library:

```bash
cmake -S cpp -B cpp/build -DCMAKE_BUILD_TYPE=Release        # the self-play machine: add -DONNXRUNTIME_ROOT=DIR
cmake --build cpp/build -j
cpp/build/mahjong runtests
```

- The self-play machine runs the network through ONNX Runtime: unpack a release (1.20 or later, with `include/` and
  `lib/`) and pass its directory as `-DONNXRUNTIME_ROOT`. Without it the `net` player and `selfplay` are disabled.
- The training machine needs only `cpp/build/libmahjong_data.so` (the target `mahjong_data`), and Python with
  PyTorch, NumPy and `onnx` (to export). With `onnxruntime` installed an export is also checked against PyTorch.
- Data files (`.npz`) hold hands as compact records, about 0.2 KB a hand compressed for stage 0 and 0.4 KB for
  self-play; features and targets are recomputed by the library when a batch is drawn. A change of the features
  therefore needs no new data.

## Stage 0: supervised, from greedy playouts

```bash
# Self-play machine: hands between greedy players; at about 5.5 decisions of each, every legal action is finished
# by greedy players on the same wall. 32 threads: about 700 hands and 3,900 labelled decisions a second.
cpp/build/mahjong gen0 -n 7200000 -t 32 -s s0b --hands-per-file 10000 -o DATA/stage0b
cpp/build/mahjong gen0 -n 60000 -t 32 -s eval0 -o DATA/eval0          # an evaluation set, never trained on

# Training machine: the actor (exported for the engine) and the critic (--full: the full state as input).
python python/train0.py --data DATA/stage0b --out RUN/stage0/actor.pt --export RUN/stage0/actor.onnx --amp
python python/train0.py --data DATA/stage0b --full --out RUN/stage0/critic.pt --amp \
    --model-d-model 192 --model-num-layers 6 --model-num-heads 6 --model-ff 576 --model-head 192

# What a checkpoint's choices are worth against greedy's, in points per decision.
python python/eval0.py --data DATA/eval0 RUN/stage0/actor.pt

# Self-play machine: the gate. One network among three greedy players, and three against one.
cpp/build/mahjong match -p net:model=actor.onnx -p greedy -p greedy -p greedy -n 5000 -t 32
cpp/build/mahjong match -p net:model=actor.onnx -p net:model=actor.onnx -p net:model=actor.onnx -p greedy -n 5000 -t 32
```

`train0.py` reads the data once (`--passes`), holds out one hand in a hundred, and logs every loss on both
(`--log-file`). `--grad-report` adds the size of each group of heads' gradient at the trunk, relative to the main
heads'; `--aux-scale` scales the training-only heads' weights and `--weight NAME=VALUE` changes one.

`eval0.py` prints `Q's choice against greedy's`: the result of the action with the best composed Q minus the result
of the action greedy would play, averaged over the decisions. Every action's hand was finished on the same wall, so
this is an unbiased estimate of what one decision by the network gains over greedy when everybody plays greedy
afterwards. Times the 15 decisions a seat makes in a hand, it predicts the match result closely.

## Stage 1: self-play

```bash
# One iteration by hand. Self-play machine:
cpp/build/mahjong selfplay -m RUN/models/actor_0.onnx --opponent greedy -n 300000 -t 32 -o RUN/selfplay/iter_1
# Training machine:
python python/train1.py --data RUN/selfplay/iter_1 --actor-init RUN/ckpt/actor_0.pt --critic-init RUN/ckpt/critic_0.pt \
    --actor-out RUN/ckpt/actor_1.pt --critic-out RUN/ckpt/critic_1.pt --export RUN/models/actor_1.onnx --amp

# The loop, on the self-play machine; it drives the training machine over ssh.
python python/loop1.py --run RUN --remote HOST:REMOTE_DIR --ssh-config SSH_CONFIG --remote-python PYTHON \
    --hands 300000 --threads 32
```

- `selfplay` plays 64 hands at once per thread and evaluates their pending decisions in one batch. Of each hand it
  marks 8 of the learner's decisions (half evenly, half in proportion to max Q − Σ π Q by the network's own heads)
  and stores the playing policy there; at 1 or 2 of them it plays another action and finishes the hand again with
  the same players and the same random numbers (a fork). `--greedy-labels` gives the marks stage 0's labels instead
  (the network's positions, every action finished by greedy players).
- `train1.py` reads an iteration once: the critic learns the outcomes of the actions played out; the policy moves
  along the critic's advantages of all legal actions (`--pg`), corrected by the actual result of the action played
  (`--corr`), held near the playing policy (`--kl`) with an entropy bonus (`--entropy`); the actor's event heads
  learn the critic's and the actual outcome. Its last line reports the held-out hands: `critic_explains` (the share
  of the results' variance the critic explains), `fork_explains` and `fork_slope` (its predicted against the
  realized difference between a fork's two actions), `kl` and `improve`.
- `loop1.py` expects `RUN/models/actor_0.onnx` here, and `REMOTE_DIR/ckpt/actor_0.pt`, `REMOTE_DIR/ckpt/critic_0.pt`
  and the repository at `REMOTE_DIR/Mahjong-TW` there. Self-play of iteration i uses the newest network that has
  come back; training of an iteration starts when its hands are complete. Every `--eval-every` iterations the newest
  network plays duplicate matches against greedy and against network 0; results go to `RUN/eval.jsonl`. Stopping
  and starting again continues. `--bwlimit` limits the copies when the link is slow or shared.

## Measured

On 28 to 32 threads of a 2-socket server (self-play machine) and a desktop GPU with a 6-core CPU (training machine).

| What | Speed |
|---|---|
| `gen0` | 640 hands/s on 28 threads: 3,500 labelled decisions/s |
| Expanding samples (`libmahjong_data.so`) | 1,800 samples/s per thread |
| `train0.py`, actor (1.02M parameters), bf16 | 15,000 samples/s |
| The network, ONNX Runtime, one thread | 1.6 ms a decision alone, 0.8 ms in batches of 64 |
| `selfplay` with forks | about 9 hands/s per thread (100 evaluations a hand) |

Stage 0, first experiments (2.2M labelled decisions, read once; evaluated on 331,000 decisions of other hands;
± 0.005):

| Variant | Q's choice against greedy's, points per decision |
|---|---:|
| As planned | −0.017 |
| Without the training-only heads | −0.105 |
| The data read three times | −0.008 |
| Kind features also straight to the action heads | −0.010 |
| The weight of the paired differences ×4 | −0.008 |
| Batches of 256 | −0.020 |

The training-only heads are worth far more than any other choice tried. With 2.2M decisions the network is not yet
at greedy's level (it loses 0.24 points a hand as one among three greedy players, with 20% wins against 26%).
