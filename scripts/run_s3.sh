#!/bin/bash
# The training side of the run s3 (features version 3: 報聽; its self-play is on a workstation, helped by another). Started by
# mahjong.sh.
D=$HOME/mahjong_runs
cd $D/Mahjong-TW-v3
exec nice -n 5 $HOME/ml_venv/bin/python python/train_loop.py --run $D/s3 --share selfplay:s3 \
  --ssh-key $HOME/.ssh/mahjong_sync --logs $D/logs --train-args "--amp --expand-threads 10"
