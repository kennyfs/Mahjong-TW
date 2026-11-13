#!/bin/bash
# Takes a run over to features version 3 (報聽) on the training machine:
#   migrate_v3_desk.sh OLD N NEW HOST
# OLD: the old run (features version 2); N: its network to go on from; NEW: the new run's name; HOST: the self-play
# machine of the new run. Writes $HOME/mahjong_runs/NEW (checkpoints 0 and N, the networks 0 and N-20..N as .onnx,
# with the code of Mahjong-TW-v3) and sends the networks to HOST:NEW/models.
set -e
D=$HOME/mahjong_runs; OLD=$1; N=$2; NEW=$3; HOST=$4; P=$HOME/ml_venv/bin/python
mkdir -p $D/$NEW/ckpt $D/$NEW/models $D/$NEW/tmp
cd $D/Mahjong-TW-v3
for K in 0 $(seq $((N - 20)) $N); do
  f=$D/$OLD/ckpt/actor_$K.pt
  [ -f $f ] || { echo "no actor $K"; continue; }
  out=$D/$NEW/tmp/actor_$K.pt
  if [ $K = $N ] || [ $K = 0 ]; then out=$D/$NEW/ckpt/actor_$K.pt; fi
  [ -f $D/$NEW/models/actor_$K.onnx ] || $P python/upgrade_features.py $f $out --export $D/$NEW/models/actor_$K.onnx 2>&1 | tail -n 1
done
for K in 0 $N; do
  [ -f $D/$NEW/ckpt/critic_$K.pt ] || $P python/upgrade_features.py $D/$OLD/ckpt/critic_$K.pt $D/$NEW/ckpt/critic_$K.pt | tail -n 1
done
rm -rf $D/$NEW/tmp
rsync -a -e "ssh -i $HOME/.ssh/mahjong_sync -o BatchMode=yes" $D/$NEW/models/ $HOST:$NEW/models/
echo "$NEW: $(ls $D/$NEW/models | wc -l) networks; checkpoints: $(ls $D/$NEW/ckpt | tr '\n' ' ')"
