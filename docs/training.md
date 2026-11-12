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
