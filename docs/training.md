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

## Features version 3 (報聽)

Version 3 (2026-11-13) adds 報聽 (docs/rules.md 5.1): a third declared feature of a seat, the rule `baoting` (at the
end of the rules) and its tai item; the action `declare` is the same. `upgrade_features.py` now takes a checkpoint
from version 1 or 2 to 3 a step at a time; `python/check_upgrade.py` runs the old network with the old code and the
upgraded one with the new code on the same inputs (s2's network 211: at most 5e-5 apart, float rounding). The run
`s3` (`scripts/migrate_v3_desk.sh`, `run_s3.sh`, `run_selfplay_s3.sh`) goes on from s2's network 211 with 報聽 on in
every preset (off in a fifth of the training hands), to teach the network when to declare it.

`s3` ran 2026-11-13 14:53 to 15:50: networks 212 to 221 (ten iterations of 100,000 hands, 1.5 forks a hand; the
helper helped until 15:42). Matches of network 221 (one seat) against 211 (three), the policy's first choice, 8,000 deals
each (the difference to the three's mean, its standard error about 0.02 and 0.027):

| Rules | 報聽 on (`baoting=1`) | 報聽 off (`baoting=0`) |
|---|---:|---:|
| `simple1,none` | +0.043 | +0.004 |
| `simple2,see` | +0.041 | +0.066 |

So it is no weaker without 報聽, and perhaps a little stronger with it (two standard errors at most). Of 1,000
hands (four seats of the same network), 211 declared 報聽 180 and 185 times (won 75 and 79 of them), 221 187 and 205
times (won 71 and 85): ten iterations changed little of when it declares. The web page and the app ship network 221
(`mahjong-files/models/actor_s3_221.fp16.onnx`).

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

### Two machines of self-play for one run

`loop1.py --helper HOST` has a second machine play half of every iteration's hands at the same time: the run's
networks are copied to it (it has the same paths and the engine at the same place), it plays into a directory of its
own, and the loop fetches its files into the iteration before the iteration appears. An iteration whose helper
cannot be reached, or whose half fails, has the hands played on the first machine only. `mahjong.sh stop` on the
first machine stops the helper's engine too. The run `s2` (2026-11-12) plays on one workstation with another as its helper, 32
threads each: 100,000 hands in about three minutes (150 s of play at 332 hands/s a machine, the rest copying).

### The weight of the KL term, and the run `s2` (2026-11-12)

Two arms from network 50 of `s1`, nine iterations each on a workstation (16 threads each, 100,000 hands an iteration, 12
marks and 1.5 forks a hand): `s1` with the KL weight 0.1, `s1b` with 0.03. Held out, an iteration of `s1b` moved the
policy 0.0095 nats from the one that played against 0.0042, and its predicted gain (`improve`, a decision) was
0.0009 against 0.0006 (seven iterations each, about 0.0001 either way): a third of the weight gave a third more,
so the weight is not what holds the policy back. For the last two iterations `s1` took the weight 0.03 with batches
of 256 (twice the steps), `s1b` kept 0.03 with the learning rate 0.001: both moved the policy further (0.013 and
0.014) and predicted 0.0011 and 0.0010, no more than noise apart from 0.0009. The critic stayed calibrated in all
of them (`fork_slope` 0.9 to 1.3). Matches of the networks 59 of both arms against each other and against network
50 (6,000 deals each way and under `simple1,none` and `simple2,see`) were all within 1.2 standard errors of zero
(0.025 and 0.035): nine iterations do not show in a match of that size. `s1`'s network 59 wins larger hands (1.65
tai against 1.52) and deals in more (0.146 against 0.139) for the same result.

`s2` goes on from `s1`'s network 59, taken over to features version 2 (`scripts/migrate_v2_desk.sh`; a run taken
over has only the last twenty networks, so `loop1.py` takes the next one there is for an earlier opponent that is
missing). Its parameters and why:

- KL weight 0.03 (`train_args.txt`: `corr 0.05`, `kl 0.03`, `batch 256`).
- Forks 0.17 a hand: a tenth of the evaluations (a fork costs about 31, a hand without one 47; at 1.5 a hand they
  were 45%). With them and the two machines an iteration of 100,000 hands is played in three minutes.
- The training machine reads about 3,700 samples a second whatever the batch (it is the ten threads that expand the
  samples, not the GPU, that set the pace: 215 s for 800,000 samples at batches of 384, 233 s at 256), so it, not
  self-play, is the slow side now. Six marks a hand (600,000 samples an iteration) take it about as long as
  self-play takes to play the next iteration, and batches of 256 make 2,340 steps of them, as many as an iteration
  of the arms had. Self-play waits when it is two iterations ahead of the newest network (`--ahead`).
- Matches against network 0 every 20 networks (4,000 deals; about ten minutes in which nothing is played).


### The run `s2` stopped improving (2026-11-12, networks 60 to 201)

Eight hours and 142 iterations (14 million hands) of `s2` as above gave nothing that a match shows:

- The matches of the loop (every 20 networks, 4,000 deals, one against three of the run's first network): +0.07
  a hand at network 60 (the mean of the two rule sets), +0.10 at 80, and between +0.08 and +0.12 from then to 200
  (standard errors 0.03 and 0.044). Against greedy the same picture.
- Those matches play the action of the best Q from the event heads (`mode=q`, the player's default), not the policy
  that self-play trains. So the policy's first choice was played too (`mode=policy`, 8,000 deals, one against three
  of the first network by Q): +0.124 and +0.150 (`simple1,none`, `simple2,see`; ± 0.021 and 0.030) at network 60,
  +0.136 and +0.210 at 100, +0.119 and +0.194 at 140, +0.105 and +0.221 at 201. Flat as well. The first network's
  own policy against its own Q: +0.005 and −0.008.
- Of network 201, one against three of itself by Q: the policy's first choice +0.038 ± 0.022 and +0.079 ± 0.030,
  drawing from the policy −0.033 and −0.029; three by the policy against one by Q: the one by Q loses 0.011 and
  gains 0.036 (nothing). So a player should take the policy's first choice (the app's computer players take Q's).
- All the while `train1.py` reported the same on held-out hands: `fork_slope` 1.0 to 1.25, `critic_explains` 0.30
  to 0.33, the policy 0.012 nats from the one that played, and `improve` 0.0011 to 0.0017 a decision, every
  iteration.

`python/is_improve.py` asks the hands themselves what a step gained: on the next iteration's hands, which the
network was not trained on, the results weighted by π_K(a) / μ(a) say what network K's policy at one decision would
have gained over the playing network's. On iteration 202 (600,000 decisions, played by network 201): network 200
instead +0.0001 ± 0.00024, network 199 +0.0004 ± 0.00025, network 191 0.0000 ± 0.0004; on iteration 101 (played by
99): network 90 +0.0006 ± 0.0004, network 60 +0.0009 ± 0.0009; on iteration 66 (by 64): network 55 +0.0006 ±
0.0005. So a step gained nothing (the critic's `improve` of 0.0014 is six standard errors away), and ten steps
gained nothing. The policies' distances say the same: 0.012 nats to the network before, 0.014 to the one before
that, 0.034 to the one ten before. The policy moves about a point, each iteration toward that iteration's errors of
the critic and back.

What `improve` measures is the critic's opinion of the new policy, and the policy was moved toward that opinion; on
held-out hands it is still the same critic. With 0.17 forks a hand, 3 decisions in 100 have a second action played
out, so the critic's values of the actions not played are nearly all inferred, and a fork's check of them
(`fork_slope`) is of actions drawn from the policy, not of the actions the step moves toward. The run `s1`, whose
59 iterations did gain (+0.12 to +0.15 over the network of stage 0), had 1.5 forks a hand.

More forks alone did not change it. From iteration 203 `s2` played 1.5 forks a hand again (189 hands a second a
machine, 100,000 hands in four and a half minutes). The held-out reports moved a little (`fork_explains` 0.11 to
0.16 where it was about 0.10, 1,500 forks to judge by in place of 160; the policy's entropy from 0.86 to 0.82 in
nine iterations, after 140 iterations without a trend), and `improve` stayed 0.0012 to 0.0021. On iteration 209
(played by network 207): network 206 instead −0.0001 ± 0.00023, network 202 (five steps back, the last of 0.17
forks) 0.0000 ± 0.00035. The run was stopped at network 211 (2026-11-12 11:30; both sides start again where they were): as it is set up it does not get
stronger, and it kept two workstations and the GPU busy.

What is known and not:

- Not known: whether this is the method's fixed point or as well as these inputs and this size can play. The
  policy's first choice beats drawing from it by about 0.1 a hand, so the policy that plays the hands is far from
  what the same network plays best, and a one-step change of it toward any of its neighbours gains nothing.
- The critic's `improve` cannot be trusted as it is computed, and neither can `fork_slope` vouch for it. The next
  thing to find out is where the two part: on forks, the realized difference against the predicted one weighted by
  how far the step moved the policy toward the other action (the direction of the step, not a draw from the
  policy).
- `is_improve.py` makes a variant cheap to judge: ten iterations and one more to measure on, to about 0.0004 a
  decision, where a match of 8,000 deals cannot see 140 iterations. A larger actor or critic, a lower learning
  rate, a smaller entropy bonus or targets from playouts would each be judged that way.

### Do the predictions get better? (2026-11-12)

What an analysis shows comes from the actor's other heads, so they were measured apart from the play:
`python/eval_heads.py`, the averaged weights of the run's checkpoints on the 598,000 marked decisions of iteration
212, which no network was trained on (the trainer's loss terms, the action played as the known outcome; ± is the
standard error of the difference to network 60, over batches of the same samples).

| Actor | `events` | `q_mse` | `ready` | `danger` | `win_tile` | `final_hand` |
|---|---:|---:|---:|---:|---:|---:|
| 0 (the run's first) | 2.1527 | 1.2279 | 0.9575 | 1.1394 | 1.1530 | 12.589 |
| 60 | 2.0941 | 1.2146 | 0.8608 | 1.0545 | 1.1241 | 11.523 |
| 100 | 2.0899 | 1.2140 | 0.8535 | 1.0483 | 1.1222 | 11.482 |
| 140 | 2.0894 | 1.2138 | 0.8521 | 1.0469 | 1.1217 | 11.470 |
| 180 | 2.0890 | 1.2137 | 0.8510 | 1.0448 | 1.1210 | 11.463 |
| 211 | 2.0883 | 1.2134 | 0.8496 | 1.0431 | 1.1205 | 11.436 |
| ± | 0.0003 | 0.0002 | 0.0002 | 0.0002 | 0.0001 | 0.0009 |

Every head got better at every step of the ladder, and none by much: from network 60 to 211 the events' loss
fell by 0.28%, the result's squared error by 0.1%, a tenth of what the first 60 networks gained. The critic (2.7M
parameters, the full state) on the same decisions, with a fork's second action: `events` 1.6700 at network 60,
1.6540 at 100, 1.6437 at 180, 1.6371 at 211 (± 0.0006), `q_mse` 0.9930 to 0.9765 (± 0.0009): 2% and 1.7%, and
still falling.

And a larger actor? `python/capacity.py` trained two from scratch at once on the same stream (iterations 196 to
211, 9.2 million decisions read once, the action played and the other heads' labels, no critic), held out the first
100,000 decisions of iteration 212:

| Actor | Parameters | GPU seconds | `events` | `q_mse` | `ready` | `danger` | `win_tile` | `final_hand` |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 128 wide, 5 layers, after 4.1M | 1.07M | 146 | 2.1079 | 1.2227 | 0.8713 | 1.0956 | 1.1392 | 11.705 |
| 256 wide, 8 layers, after 4.1M | 6.06M | 363 | 2.1062 | 1.2194 | 0.8683 | 1.0901 | 1.1346 | 11.639 |
| 128 wide, 5 layers, after 9.2M | | 328 | 2.1032 | 1.2214 | 0.8647 | 1.0789 | 1.1328 | 11.628 |
| 256 wide, 8 layers, after 9.2M | | 812 | 2.1017 | 1.2188 | 0.8625 | 1.0733 | 1.1291 | 11.567 |
| The run's network 211 | 1.07M | | 2.0900 | 1.2139 | 0.8489 | 1.0468 | 1.1204 | 11.443 |

The larger one is ahead at every one of nine reports and on every head, by little: 0.07% of the events' loss at
the end (0.13% early), 0.5% of `danger` and `final_hand`, for 5.7 times the parameters and 2.5 times the time. That
is a quarter to a half of what the run's last 150 iterations gained, and far less than what the run's network has
over both (it has read twenty times as many hands, and the critic's outcomes of every action). So the size is not
what holds the predictions where they are: nearly all of these losses is what cannot be known from a seat (a hand's
result varies by more than a typical payment, `q_mse` 1.21, whatever is discarded), and the part a network can
still take off is within a percent.

## Making the analysis' outputs more accurate (2026-11-21)

An analysis shows what the actor's heads say: Q (the events and their tai, composed), the policy, `ready`, `danger`,
`danger_tai`, `ready_within` and `tai_items`. This section is a day of trying to make them more accurate than
network 221's (run `s3`, the one that ships) without making its policy weaker. In short: every output was already
calibrated on average; a difference of Q between two actions below about 0.15 is about half noise, and a loss to
Q's best is noise on average where the policy prefers the action played; nothing tried, a network seven times
larger included, made small differences sharper; a fine-tune of 221 against a critic trained further improves
every output a little, plays as 221 does, and is the candidate.

### Held-out hands and the tools

- Iteration 223 of `s3` (hands of network 221 by the training mix of rules, never trained on): 600,000 marked
  decisions, 150,000 of them with a fork.
- 450,000 new hands of network 221 by the two main rules (`simple1,none` and `simple2,see`): 150,000 with three
  forks a hand (in a fifth of them one to three seats are greedy or an older network, as in the new training hands
  below; 1.2 million marked decisions) and 300,000 with six; 2.25 million forks. A fork is a decision whose second
  action was finished too, on the same wall, so its realized difference is the true difference plus luck that does
  not depend on what any network says. Differences are in points for a typical payment of 4, the unit of the app's
  grades.

The tools, in `python/`:

- `eval_accuracy.py`: each output's loss, Brier score and calibration (ECE), Q's errors by context (shanten, melds,
  place, flowers, 報聽 by rule and by who declared, ...), the tai by the predicted and by the realized size, and
  what Q's choice and the policy's would gain where they differ.
- `fork_resolution.py`: by |dQ|, how much of a predicted difference between the two actions of a fork is real.
- `fork_paired.py`: networks against a reference on the same forks, grouped by the reference's |dQ|, as the paired
  difference of the squared errors to the realized difference (the luck largely cancels), and the realized gain of
  choosing by the network instead of the reference; by each network's |dQ|, the share of the difference that is
  real where its policy prefers the same action as its Q and where it does not.
- `train_acc.py`: several actors ("arms") trained from one network at once on the same stream, each learning its
  heads' labels, the critic's events and tai of every legal action (distillation) and a KL to 221's policy, beside
  a critic that keeps learning or is held fixed (`--critic-lr 0`). `--compile` runs every network through
  `torch.compile`: 2,450 samples a second where it was 900 (five arms, the teacher and the critic each step). On a
  power-limited GPU, float16 autocast runs as fast as bfloat16 with an eighth of its rounding error.
- `grow_actor.py`: a deeper or wider actor that computes exactly what the smaller one did (new blocks and new
  units start with zero outgoing weights), to train on from there.
- `wall_share.py`: how much of the critic's noise as a target is the order of the undrawn tiles.
- `onnx_to_ckpt.py`: an actor's weights rebuilt from its exported `.onnx`, for networks whose checkpoints are gone.

### Network 221

On iteration 223, every probability is right on average to within 0.003 and calibrated across its range
(`ece`, the mean gap between predicted and realized frequency over 40 bins of equal count):

| Output | Log-loss | ECE | Mean predicted | Realized |
|---|---:|---:|---:|---:|
| win | 0.4253 | 0.0028 | 0.2102 | 0.2083 |
| deal in | 0.4004 | 0.0032 | 0.1490 | 0.1458 |
| another's self-draw | 0.5397 | 0.0027 | 0.2412 | 0.2430 |
| `ready` | 0.1945 | 0.0008 | 0.1552 | 0.1555 |
| `danger` | 0.0300 | 0.00004 | 0.0078 | 0.0078 |
| `ready_within` | 0.3908 | 0.0015 | 0.3678 | 0.3677 |
| `tai_items` | 0.0182 | 0.00009 | 0.0068 | 0.0067 |

The tai of a win is calibrated by its prediction (where 4 to 5 tai are predicted, 4.64 on average, 4.67 are won;
6 to 7: 6.59 and 6.71) and pulled toward the mean by its realization (wins of 6 to 7 tai were predicted at 4.8):
that is what a seat cannot know, not a bias. `danger_tai` the same.

What is not right is the difference of Q between two actions when it is small. On the 2.25 million forks, by
the network's own |dQ|, the realized difference in Q's direction over the predicted one (`fork_paired.py`'s
saved differences):

| \|dQ\| | 0.02 to 0.05 | 0.05 to 0.1 | 0.1 to 0.15 | 0.15 to 0.25 | 0.25 to 0.5 | 0.5 to 1 | 1 and more |
|---|---:|---:|---:|---:|---:|---:|---:|
| 221 | 0.63 ± 0.19 | 0.35 ± 0.12 | 0.59 ± 0.11 | 0.85 ± 0.07 | 0.85 ± 0.04 | 0.98 ± 0.03 | 1.02 ± 0.01 |
| The candidate | 0.67 ± 0.22 | 0.46 ± 0.13 | 0.62 ± 0.11 | 0.82 ± 0.07 | 0.89 ± 0.04 | 0.98 ± 0.03 | 1.01 ± 0.01 |

Below 0.15 about half of a predicted difference is real, from 0.15 up 85% or more, from 0.5 up all of it.

The app's grades count the played action's loss to Q's best, max Q − Q(played). On the forks where that is the
case (the action not played is Q's best of all the legal ones: 510,000 forks, as the played one was drawn from the
policy), the same share, also by whether the policy prefers Q's best to the played action (`fork_paired.py`; the
share of forks in brackets):

| Loss | 221 | 221, the policy agrees | 221, the policy prefers the played | Candidate | Candidate, agrees | Candidate, prefers the played |
|---|---:|---:|---:|---:|---:|---:|
| 0.05 to 0.15 | 0.08 ± 0.18 | 0.31 ± 0.21 | −0.48 ± 0.36 (29%) | 0.35 ± 0.19 | 0.73 ± 0.22 | −0.59 ± 0.39 (30%) |
| 0.15 to 0.5 | 0.72 ± 0.17 | 0.96 ± 0.20 | +0.26 ± 0.30 (32%) | 0.83 ± 0.17 | 1.01 ± 0.19 | +0.42 ± 0.32 (30%) |
| 0.05 to 0.5 | 0.31 ± 0.13 | 0.53 ± 0.15 | −0.19 ± 0.25 (30%) | 0.54 ± 0.13 | 0.84 ± 0.16 | −0.18 ± 0.26 (30%) |
| 0.5 and more | 0.84 ± 0.24 | 1.20 ± 0.31 | +0.38 ± 0.38 (44%) | 1.06 ± 0.22 | 1.29 ± 0.28 | +0.75 ± 0.34 (43%) |

Where the policy prefers the played action, a loss from 0.05 to 0.5 is not real on average (−0.2 ± 0.25 for both
networks; below 0.15 −0.5 ± 0.4, from 0.15 to 0.5 +0.3 ± 0.3); where it agrees, half of a loss below 0.15 and all of
one above are. The two networks' rows share the forks and their luck, so they are not two confirmations. Below 0.15
the two held-out sets apart agree for 221 (−0.95 ± 0.83 and −0.36 ± 0.41), and for the candidate the larger one
does (−0.79 ± 0.43) while the smaller says nothing (+0.23 ± 0.89); where the cutoff lies between 0.15 and 0.5 is
not resolved. The played action of a fork is drawn from the policy; a human's choices differ, which these shares
do not cover.

By the policy's first choice instead (the same forks, losses from 0.05 to 0.15; 221, then the candidate): where it
is Q's best (64% of them), 0.34 ± 0.22 and 0.65 ± 0.24 of the loss is real; where it is the played action (28%),
−0.59 ± 0.38 and −0.68 ± 0.40; where it is a third action (9%), too few forks to tell (0.0 ± 0.7 to +1.4 ± 1.5,
nothing that says noise). From 0.15 to 0.5: 0.90 ± 0.20 and 0.98 ± 0.20 where it is Q's best, +0.25 ± 0.31 and
+0.35 ± 0.32 where it is the played action. So the noise is where the policy's first choice is the action played.

### What was tried

The fine-tunes started from 221 (or a network grown from it) and were trained by `train_acc.py` on iterations 212
to 222 and new hands of network 221; each, and some of the loop's earlier networks, is compared with 221 on the
2.25 million forks (`fork_paired.py`). The mean squared error of a difference to the realized one is 28 points
squared, nearly all of it luck, so what a network can change is small; ± is the standard error of the paired
difference.

| Network | mse, 221's \|dQ\| in [0.02, 0.1) | mse, every fork | gain a fork, every fork |
|---|---:|---:|---:|
| Run 4 `base` (221's size) | −0.0009 ± 0.0004 | −0.0051 ± 0.0006 | +0.0023 ± 0.0011 |
| Run 4 `deep10` (10 layers, 1.9M parameters) | −0.0009 ± 0.0004 | −0.0050 ± 0.0006 | +0.0020 ± 0.0011 |
| Run 4 `wide10` (10 layers, wider, 3.7M) | −0.0009 ± 0.0004 | −0.0052 ± 0.0006 | +0.0014 ± 0.0012 |
| Run 4 `big16` (16 layers, wider, 7.9M) | −0.0011 ± 0.0004 | −0.0054 ± 0.0007 | +0.0023 ± 0.0012 |
| Run 3 `rank16` | −0.0011 ± 0.0005 | −0.0040 ± 0.0007 | +0.0003 ± 0.0012 |
| `swa10` (the loop's 212 to 221, weights averaged) | −0.0011 ± 0.0004 | −0.0031 ± 0.0006 | +0.0021 ± 0.0012 |
| Run 5 `mix` (the candidate) | −0.0007 ± 0.0005 | −0.0052 ± 0.0007 | +0.0015 ± 0.0013 |
| The loop's network 192 | +0.0003 ± 0.0007 | +0.0081 ± 0.0013 | −0.0006 ± 0.0015 |
| 202 | +0.0005 ± 0.0007 | +0.0048 ± 0.0012 | −0.0002 ± 0.0015 |
| 211 | −0.0003 ± 0.0007 | −0.0009 ± 0.0010 | −0.0014 ± 0.0015 |
| 216 | +0.0004 ± 0.0007 | −0.0010 ± 0.0009 | −0.0016 ± 0.0016 |

- The loop's last thirty networks got better on the large differences (+0.008 at network 192 to 0 at 221) and not
  on the small ones. A fine-tune of 221 with a critic trained further on the same hands (runs 1 to 3: the critic
  kept learning, with the certain results of a win on offer as its targets) took Q's error over all forks down by
  another 0.005, as much as the loop's last twenty iterations; nearly all of it on differences above 0.1.
- Size: three networks grown from 221 (`grow_actor.py`) and trained beside run 4's `base` in the same way are the
  same as it within one standard error in every group. So the capacity of the actor is not what holds the small
  differences where they are. This was tested only on networks fine-tuned from 221, not on a larger network
  trained from the start; `capacity.py` above (2026-11-12) found 0.07% of the events' loss in that case.
- A rank loss on Q's differences among the actions worth considering (run 3, `rank4` to `rank64`) looked like the
  answer by the critic's account: by its own |dQ| in [0.02, 0.1) the critic's difference is 0.62 to 0.76 of what
  the network says, where 221's is 0.49. On the realized results it gained nothing (the table) and did less on
  large differences. A network distilled from a critic is judged by that critic in its favour: only the realized
  results rank networks.
- Averages of networks: the weights of the loop's networks 212 to 221 (`swa10`) or the mean of three to six
  networks' Q gained less than the fine-tune.
- The wall: on forks, three quarters of the critic's spread around the actor's difference (0.77 of 1.02 points
  squared) is the order of the undrawn tiles, which given every hand is uniformly random; the critic's mean over 16
  reshuffled orders (`data.full_reshuffled`, the C function `mj_full_reshuffled`) leaves 0.21. As the
  distillation's target, the mean over 8 reshuffled orders (run 5 `mixwall`, `--wall-k 8`) changed nothing next to
  the same arm on the recorded order: −0.00001 ± 0.00005 in [0.02, 0.1), +0.0001 ± 0.0001 over all forks. The
  actor averages that noise away by itself over many samples; what is left is the critic's own error.
- The played action's own result (rms 5.3 points) and a fork's: run 5 `low` (no forks, the own results at a tenth)
  and `distonly` (no forks, no own results for the events and tai) were worse than `mix` over all forks by 0.0007
  ± 0.0002 and 0.0008 ± 0.0003, all of it on differences above 0.25. The realized results keep the large
  differences honest (forks and own results were dropped together, so which of the two does it is not known).
- `tai_skip` (the tai head adds the tai stated in the input, `model.py`), a frozen trunk, the certain results of
  a win on offer as the distillation's target (run 1), and ten times the weight on hands of 4 tai or more (run 2):
  the same as the plain arm or worse.

The critic is not at its limit. The same comparison with critics in place of actors (each critic's difference
against the realized one, against 221's critic, on the 2.25 million forks):

| Critic | mse, [0.02, 0.1) | mse, every fork | gain a fork, every fork |
|---|---:|---:|---:|
| The loop's 219 | +0.001 ± 0.003 | −0.003 ± 0.006 | +0.0014 ± 0.0015 |
| The loop's 220 | +0.007 ± 0.003 | +0.009 ± 0.006 | −0.0012 ± 0.0014 |
| Run 1 (from 221's: one pass of iterations 212 to 222, the certain results of a win on offer as targets) | −0.017 ± 0.002 | −0.177 ± 0.005 | +0.0108 ± 0.0012 |
| Run 2 (from run 1's: iterations 212 to 222 again and 250,000 new hands of 221, three forks a hand) | −0.020 ± 0.003 | −0.208 ± 0.005 | +0.0136 ± 0.0013 |
| Run 3 (and 100,000 more; the critic of runs 4 and 5) | −0.022 ± 0.003 | −0.219 ± 0.006 | +0.0150 ± 0.0013 |

(The groups are by the reference critic's |dQ|, so the [0.02, 0.1) group is a different set of forks from the
actors'.) Each run's critic is better than the one before it, by less each time; the loop's last critics are not.
But the actor distilled from run 3's critic gained 0.005 over all forks and 0.0009 in [0.02, 0.1), where the
critic gained 0.22 and 0.022: about 2% to 4% of the critic's improvement reached the actor. Why is not known. One
guess: the actor learns the critic's mean over what a seat cannot see, and the critic's errors that vary with the
hidden hands and the wall, and their corrections, mostly average out in that mean. Another: much of the critics'
gain is the certain results of a win on offer, which the actors were given directly (`exact_wins`) as well. Either
way a better critic did little for the analysis; it may do more for self-play, where it is what the policy is
improved by.

### The candidate

Run 5 `mix`: run 4's `base` (two passes of iterations 212 to 222 and 350,000 new hands of the main rules, run 3's
critic held fixed) trained one more pass on iterations 212 to 222 and 300,000 new hands by the training mix of
rules (three forks a hand), so that every rule set is covered as the shipped network's was. 221's architecture and
inputs; its `.onnx` has the same inputs, outputs and metadata, and 2.2 MB in float16. The float32 export matches
the PyTorch weights to 1.2e-5 (221's to 1.5e-5), the float16 file differs from it as much as 221's does (at most
0.01 on any output, over 4,000 decisions), and one decision takes 2.8 ms on one CPU thread (221: 2.7 ms).

On both held-out sets, the trainer's loss terms and calibration (`eval_accuracy.py`; "main rules" is the 150,000
hands with three forks a hand), 221 → the candidate:

| | Iteration 223 (training mix) | Main rules |
|---|---|---|
| `events` | 2.0876 → 2.0866 | 2.0924 → 2.0912 |
| `tai` | 0.0054 → 0.0054 | 0.0033 → 0.0032 |
| `ready` | 0.8118 → 0.8104 | 0.8426 → 0.8410 |
| `danger` | 1.0193 → 1.0166 | 1.0309 → 1.0280 |
| `danger_tai` | 0.0011 → 0.0010 | 0.0006 → 0.0006 |
| `tai_items` | 0.7458 → 0.7441 | 0.6528 → 0.6509 |
| `my_tai` (the tai of my win) | 0.2161 → 0.2150 | 0.1905 → 0.1896 |
| `ready_within` | 1.5632 → 1.5622 | 1.5580 → 1.5569 |
| Q's squared error | 1.2434 → 1.2429 | 1.3107 → 1.3100 |
| Q's mean error | +0.0026 → −0.0012 | +0.0035 → +0.0002 |
| ECE of win, deal in, another's self-draw | 0.0028, 0.0032, 0.0027 → 0.0021, 0.0018, 0.0020 | 0.0028, 0.0043, 0.0019 → 0.0018, 0.0019, 0.0020 |
| ECE of `ready`, `ready_within` | 0.0008, 0.0015 → 0.0014, 0.0014 | 0.0017, 0.0029 → 0.0018, 0.0027 |

Every loss is lower on both sets, by 0.05% (`events`, `ready_within`, Q) to 0.3% (`danger`, `tai_items`) and 0.5%
(`my_tai`): about what thirty iterations of the loop gained after network 60 (above, 2026-11-12). The mean
probabilities of a win and of dealing in, which 221 put 0.002 to 0.004 too high, are within 0.0015; `ready`'s
calibration is 0.0006 worse on iteration 223 and the same on the main rules. Run 4's `base`, trained on the main
rules only, is the same as the candidate to within 0.0003 on every term of both sets.

Q against the policy (`eval_accuracy.py`: at each decision, what Q's first choice and the policy's would gain over
the playing network's choice, importance-weighted over the actions played): on iteration 223 the policy's choice
is the better by 0.007 ± 0.002 a decision for 221 and 0.005 ± 0.002 for the candidate; on the main rules' hands
the two are level (Q ahead by 0.001 ± 0.002 for both). They choose the same action in 68% of decisions, and where
they disagree about a small loss, Q's difference is not real on average (the table above). The fine-tune's 0.005
against 221's 0.007 is within the noise. In matches (one playing the policy's first choice among three of the
same network playing best Q, 20,000 duplicate deals, float16 as shipped), the one playing the policy got, under
`simple1,none` and `simple2,see`: 221 +0.032 ± 0.014 and +0.032 ± 0.019 a hand, the candidate −0.003 ± 0.013 and
+0.047 ± 0.019. The policy is the stronger player by about 0.03 a hand for both (the two networks' 0.01 apart is
noise), less than network 201's 0.04 and 0.08 above; the computer players should keep playing it.

The policy's strength (`is_improve.py`, iteration 223's 600,000 decisions, played by 221): the candidate's policy
instead of 221's at one decision gains +0.00000 ± 0.00007 (run 4's `base` +0.00005 ± 0.00007; 221 itself exactly
0, the check of the stored probabilities). Its policy is 0.0014 nats from 221's, where the KL term held it: the
fine-tune moved the predictions, not the play.

### For the app's grades

The app counts a decision's loss, max Q − Q(played), as nothing below `EQUAL` (0.05) and grades it from 0.1, 0.25
and 0.5 for a typical payment of 4 (`web/src/lib/review.ts`, `Review.kt`; the expert level marks from 0.1). By the
second table of "Network 221" above:

- Where the policy's first choice is Q's best, or a third action, `EQUAL` at 0.05 and the steps can stay: a loss
  below 0.15 is about half real where it is Q's best (0.34 ± 0.22 for 221, 0.65 ± 0.24 for the candidate), from
  0.15 up all of it, and nothing says otherwise for a third action.
- Where the policy's first choice is the played action (28% of the losses from 0.05 to 0.5 in the network's own
  play, fewer for a human), a loss is not real on average: −0.6 ± 0.4 below 0.15, +0.3 ± 0.3 from 0.15 to 0.5.
  Showing such a loss as ≈ or as uncertain, below 0.15 at least and up to 0.5 at most, is a judgment for the app;
  every action's figures already carry its `policy`. (The pairwise rule, the policy preferring the played action
  to Q's best, picks out nearly the same decisions.)
- Raising `EQUAL` alone (without the policy) would drop losses the policy agrees with, which are partly real.
- Scaling the shown losses by the shares above did not help: fitted on one of the two sets of held-out hands and
  tested on the other (an ad hoc check, not a script in the repository), it brought Q's differences closer to the
  realized ones below 0.15 by 0.0005 to 0.001 points squared and further from them above 0.15; each share is known
  to ± 0.1 at best.
