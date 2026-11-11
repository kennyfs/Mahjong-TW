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

# The loop. Self-play machine; SHARE/RUN is all the training machine may read and write:
python python/loop1.py --role selfplay --run SHARE/RUN --hands 100000 --threads 32
# Training machine, with stage 0's actor and critic at RUN2/ckpt/actor_0.pt and RUN2/ckpt/critic_0.pt:
python python/train_loop.py --run RUN2 --share HOST:RUN --ssh-key ~/.ssh/mahjong_sync --bwlimit 200 --train-args "--amp"
```

- `selfplay` plays several hands at once per thread (`--slots`) and evaluates their pending decisions in one batch. Of each hand it
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
- The loop has two sides that share one directory on the self-play machine, and only data crosses between them:
  hands one way, networks and logs the other. `loop1.py --role selfplay` expects `SHARE/RUN/models/actor_0.onnx`. It
  plays iteration i with the newest network there into `selfplay/iter_i` (the name appears when the iteration is
  complete), at most `--ahead` iterations beyond the newest network, against itself and, in a fifth of the hands,
  greedy players and earlier networks. Every `--eval-every` iterations the newest network plays duplicate matches
  against greedy and against network 0; results go to `eval.jsonl`.
- `train_loop.py` runs on the training machine. It copies iteration i when it is there, runs `train1.py` from
  iteration i − 1's checkpoints, sends back `models/actor_i.onnx`, `train.jsonl`, its log and the directory of
  `--logs` (to `remote_logs/`), and deletes the hands. Before each iteration it reads `train_args.txt` from the
  shared directory: lines of `NAME VALUE` for `lr`, `critic-lr`, `kl`, `corr`, `entropy`, `pg`, `distill`,
  `aux-scale` and `batch` (numbers only). A file `STOP` there ends it when it has nothing to train. Both sides
  continue where they were when started again.
- The training machine needs no more than rsync into that one directory:

  ```bash
  # Training machine:
  ssh-keygen -t ed25519 -N "" -C mahjong_sync -f ~/.ssh/mahjong_sync
  # Self-play machine, one line in ~/.ssh/authorized_keys (SHARE in full):
  command="/usr/bin/rrsync -no-lock SHARE",restrict ssh-ed25519 AAAA... mahjong_sync
  ```

  Paths the training machine names (`HOST:RUN`) are then relative to SHARE. After an error other than "not there
  yet" it waits five minutes, so a missing key does not look like an attack.
- `loop1.py --role both --remote HOST:REMOTE_DIR --ssh-config SSH_CONFIG --remote-python PYTHON` is the other way
  round: the self-play machine starts each training run on the training machine over ssh (the repository at
  `REMOTE_DIR/Mahjong-TW`, the checkpoints at `REMOTE_DIR/ckpt`).

## Measured

On 28 to 32 threads of a 2-socket server (self-play machine) and a desktop GPU with a 6-core CPU (training machine).

| What | Speed |
|---|---|
| `gen0` | 640 hands/s on 28 threads: 3,500 labelled decisions/s |
| Expanding samples (`libmahjong_data.so`) | 1,800 samples/s per thread |
| `train0.py`, actor (1.02M parameters), bf16 | 15,000 samples/s |
| The network, ONNX Runtime, one thread | 1.6 ms a decision alone, 0.8 ms in batches of 64 |
| `train0.py`, critic (192 wide, 6 layers), bf16 | 9,500 samples/s |
| `train1.py`, actor and that critic | about 4,000 samples/s |
| `selfplay` with forks (88 evaluations a hand), 64 hands at once per thread | 11 hands/s on 1 thread, 87 on 8, 183 on 32 |
| The same on 32 threads with 32, 16, 8, 4 hands at once | 187, 215, 225, 215 hands/s (the loop uses 8) |

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

| The training-only heads' weights ×0.5, ×2, ×4 | −0.036, −0.017, −0.015 |
| A ResNet (128 wide, 6 blocks) in place of the Transformer | −0.028 |

The training-only heads are worth far more than any other choice tried; their weights stay as they are. The ResNet
is no better and slower for its size, so the Transformer stays.

More data alone made it worse: 12.8M decisions gave −0.047. The policy head's target (the best action by the
labels, nearly one-hot) is noisy, and its gradient through the shared layers hurt the outcome heads. Since then the
policy has a head of its own, and in stage 0 it learns from the trunk without changing it (`--policy-through`
restores the old way). With that, on 9.2M decisions (10 minutes on the GPU; `s128`):

| Measure | Result |
|---|---:|
| Q's choice against greedy's, points per decision | +0.0165 ± 0.0052 |
| The policy head's choice against greedy's | +0.020 |
| One network among three greedy players, points per hand (`simple1,none`) | +0.136 ± 0.032 (wins 25.3%, deals in 13.6%; greedy 19.7%) |
| One greedy player among three networks | −0.088 ± 0.035 |
| Playing the policy head's best action instead | +0.108 ± 0.039 |
| Sampling from the policy head (as self-play does) | +0.004 ± 0.038 |

Sizes of the actor, each on the same 9.2M decisions; the matches are 10,000 deals with the four seatings:

| Actor | Parameters | Q's choice against greedy's | One among three greedy players |
|---|---:|---:|---:|
| 96 wide, 4 layers | 0.52M | +0.0137 ± 0.0050 | +0.112 ± 0.018 |
| 128 wide, 5 layers | 1.06M | +0.0165 ± 0.0052 | +0.127 ± 0.017 |
| 160 wide, 6 layers | 1.89M | +0.0214 ± 0.0053 | +0.110 ± 0.017 |

No size is better than another here (one of 96 among three of 128: +0.003 ± 0.019), so 128 stays; whether a
stronger player needs the capacity is not something greedy opponents can show. The critic (192 wide, 6 layers,
2.71M parameters, 21.4M decisions) reaches an event loss of 1.62 on held-out hands where the actor has 2.04.

So stage 0 ends above greedy, which was its gate. The self-play machine is the slow side: 225 hands a second on 32
threads feed about 1,800 samples a second, half of what the GPU trains. (Small batches are faster on many threads:
a thread's working memory then stays in its own cache.) `mc` (a Monte Carlo player over
greedy playouts) is no yardstick: with 400 playouts a decision it loses 0.6 points a hand to greedy, because one
hand's result varies by several points whatever the discard.

Stage 1, the first iteration (100,000 hands by the stage 0 network, 790,000 samples, held-out hands): the critic
explains 27% of the variance of the results; over 1,489 forks its predicted difference between the two actions has
slope 0.99 against the realized one (0.34 against 1.19 points in size, so it explains 8% of a single fork). The
exported policy is 0.022 in KL from the playing one, and better by 0.0010 points a decision by the critic's
values. Training an iteration takes 130 seconds (6,100 samples a second), fetching it 140 (at 200 KiB/s), playing
it 450: the GPU idles two thirds of the time.

## A change of the features: version 2 (declared ready hands)

Features version 2 (2026-11-11) adds the action `declare`, two features of a seat, two rules and two tai items.
`python/upgrade_features.py OLD.pt NEW.pt [--export NEW.onnx]` takes a checkpoint of version 1 over: the new inputs
get weights of zero, so the network computes exactly what it did on every old position (checked on a stage 0
network: the largest difference of any output is 0), the new action starts from the embedding of `tsumo`, and the
optimizer's statistics stay. Self-play files, the data library and the `.onnx` networks of the two versions do not
mix: a run is taken over as a whole (its newest checkpoints and the earlier networks it plays against).

## Starting, stopping and watching a run

Each machine has one script for its side of a run (copies of `scripts/`; the paths inside are those of the two
machines in use):

- the training machine: `$HOME/mahjong_runs/mahjong.sh start|stop|status [RUN]` (`scripts/train_side.sh`; a run
  `RUN` is started by `$HOME/mahjong_runs/run_RUN.sh`, as in `scripts/run_s2.sh`);
- the self-play machine: `$WORK/bin/mahjong.sh start|stop|status [RUN]`
  (`scripts/selfplay_side.sh`, with `bin/run_selfplay_RUN.sh`).

Without `RUN` they take the name in the file `current_run` next to them; `stop` without `RUN` stops every run of
that machine. Stopping looks for processes whose command lines carry the run's own directory (the loop, the trainer
or the engine, and what they started), so nothing else on the machine is touched. Either side can be stopped and
started at any time: the other one waits, and a half-done iteration is done again.

`status` shows the cycle (the newest network), what the side is doing now and how far it is, and the last log
lines. The numbers come from two small files that are rewritten as the work goes: the engine's
`selfplay --progress FILE` (the seed, hands finished, hands to play, seconds; `loop1.py` puts it at
`RUN/selfplay_progress.txt`, where the training machine can read it too) and `train1.py --progress FILE` (steps
done, steps to do, seconds; `train_loop.py` puts it at `RUN/train_progress.txt`).

The link between the two machines is the slow part: about 0.1 to 0.2 MB/s when measured on 2026-11-12, so an
iteration of 100,000 hands with 12 marks a hand (38 MB) takes three minutes or more to fetch.

