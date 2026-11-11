#!/bin/bash
# The self-play side of the run s2 (features version 2); started by mahjong.sh.
B=$HOME
cd $B/Mahjong-TW-v2 || exit 1
exec $B/mahjong-files/venv/bin/python python/loop1.py --role selfplay --run $B/mahjong_remote/share/s2 \
  --hands 100000 --threads 32 --slots 8 --marks 12 --eval-every 10 --eval-deals 4000 --seed s2
