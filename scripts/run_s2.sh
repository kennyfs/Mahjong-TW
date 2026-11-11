#!/bin/bash
# The training side of the run s2 (features version 2).
D=$HOME/mahjong_runs
cd $D/Mahjong-TW-v2
exec nice -n 5 $HOME/ml_venv/bin/python python/train_loop.py --run $D/s2 --share selfplay:s2 \
  --ssh-key $HOME/.ssh/mahjong_sync --logs $D/logs --train-args "--amp --expand-threads 10"
