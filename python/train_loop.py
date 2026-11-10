#!/usr/bin/env python3
"""The training machine's side of the stage 1 loop (docs/training.md): for each iteration, in order, fetch its
self-play hands from the self-play machine, train on them once (train1.py), and send the new network back.

    python train_loop.py --run RUN --share HOST:PATH [--ssh-key KEY] --bwlimit 200

RUN/ckpt/actor_0.pt and critic_0.pt must exist. HOST:PATH is the run's directory on the self-play machine, where
`loop1.py --role selfplay` writes selfplay/iter_i and reads models/actor_i.onnx. With a key restricted to rrsync on
that machine (as below), PATH is relative to the directory the key is confined to, and this side needs no other
login; nothing but files crosses: hands in, networks and logs out.

    # here:      ssh-keygen -t ed25519 -N "" -C mahjong_sync -f ~/.ssh/mahjong_sync
    # there, in ~/.ssh/authorized_keys:
    #   command="/usr/bin/rrsync -no-lock /SHARE",restrict ssh-ed25519 AAAA... mahjong_sync

A file train_args.txt in the run's directory there, when present, changes numeric options of train1.py for the
iterations that follow (one NAME VALUE per line: lr, critic-lr, kl, corr, entropy, pg, distill, aux-scale, batch);
a file STOP there ends this loop after the iteration under way.
"""

import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
OPTIONS = ("lr", "critic-lr", "kl", "corr", "entropy", "pg", "distill", "aux-scale", "batch")


def log(run, text):
    line = f"{time.strftime('%m-%d %H:%M:%S')} {text}"
    print(line, flush=True)
    with open(os.path.join(run, "train_loop.log"), "a") as f:
        f.write(line + "\n")


def rsync(args, src, dst, quiet=False):
    ssh = "ssh -o BatchMode=yes -o ConnectTimeout=30 -o ServerAliveInterval=30"
    if args.ssh_key:
        ssh += f" -o IdentitiesOnly=yes -i {args.ssh_key}"
    cmd = ["rsync", "-az", "--partial", "-e", ssh]
    if args.bwlimit:
        cmd.append(f"--bwlimit={args.bwlimit}")
    r = subprocess.run(cmd + [src, dst], capture_output=True, text=True)
    return r.returncode


def newest(run):
    best = -1
    for name in os.listdir(os.path.join(run, "models")):
        m = re.fullmatch(r"actor_(\d+)\.onnx", name)
        if m and os.path.exists(os.path.join(run, "ckpt", f"critic_{m.group(1)}.pt")):
            best = max(best, int(m.group(1)))
    return best


def train_options(args):
    """The options of train1.py changed from the other side: only known names with numbers."""
    path = os.path.join(args.run, "train_args.txt")
    if rsync(args, f"{args.share}/train_args.txt", path) != 0 or not os.path.exists(path):
        return []
    out = []
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) == 2 and parts[0] in OPTIONS and re.fullmatch(r"[0-9]+(\.[0-9]+)?(e-?[0-9]+)?", parts[1]):
                out += ["--" + parts[0], parts[1]]
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True, help="The run's directory here")
    ap.add_argument("--share", required=True, help="HOST:PATH of the run's directory on the self-play machine")
    ap.add_argument("--ssh-key", help="Private key for that machine (default: ssh's own choice)")
    ap.add_argument("--bwlimit", type=int, default=0, help="Limit of the copies, KiB/s")
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--train-args", default="--amp", help="Arguments for train1.py")
    ap.add_argument("--logs", help="A directory of logs to send along (for example, other experiments')")
    ap.add_argument("--keep", type=int, default=20, help="Checkpoints kept (besides every 20th)")
    args = ap.parse_args()
    run = args.run
    for sub in ("models", "ckpt", "selfplay", "logs"):
        os.makedirs(os.path.join(run, sub), exist_ok=True)
    if not all(os.path.exists(os.path.join(run, "ckpt", f"{n}_0.pt")) for n in ("actor", "critic")):
        raise SystemExit(f"Put the first networks at {run}/ckpt/actor_0.pt and critic_0.pt")
    i = max(newest(run), 0) + 1
    log(run, f"waiting for iteration {i}")
    wait = 20
    while True:
        data = os.path.join(run, "selfplay", f"iter_{i}")
        # Only whole iterations appear under this name there. rsync says 23 when it is not there yet; anything else
        # is the link or the login, and is not tried again for five minutes (failed logins in a row get a host
        # banned).
        code = rsync(args, f"{args.share}/selfplay/iter_{i}/", data + ".part/")
        if code != 0:
            if code != 23:
                log(run, f"cannot reach the self-play machine (rsync code {code}); again in five minutes")
                time.sleep(300)
                continue
            if rsync(args, f"{args.share}/STOP", os.path.join(run, "STOP")) == 0:
                log(run, "STOP is set on the other side; ending")
                return
            time.sleep(wait)
            wait = min(120, wait + 10)
            continue
        wait = 20
        os.rename(data + ".part", data)
        log(run, f"iteration {i} fetched; training")
        ck = os.path.join(run, "ckpt")
        cmd = [args.python, os.path.join(HERE, "train1.py"), "--data", data,
               "--actor-init", f"{ck}/actor_{i - 1}.pt", "--critic-init", f"{ck}/critic_{i - 1}.pt",
               "--actor-out", f"{ck}/actor_{i}.pt.part", "--critic-out", f"{ck}/critic_{i}.pt.part",
               "--export", os.path.join(run, "models", f"actor_{i}.onnx.part"),
               "--log-file", os.path.join(run, "train.jsonl")] + args.train_args.split() + train_options(args)
        with open(os.path.join(run, "logs", f"train_{i}.log"), "w") as f:
            code = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT).returncode
        if code != 0:
            log(run, f"training {i} failed (logs/train_{i}.log); ending")
            return
        os.rename(f"{ck}/actor_{i}.pt.part", f"{ck}/actor_{i}.pt")
        os.rename(f"{ck}/critic_{i}.pt.part", f"{ck}/critic_{i}.pt")
        model = os.path.join(run, "models", f"actor_{i}.onnx")
        os.rename(model + ".part", model)
        while rsync(args, model, f"{args.share}/models/") != 0:
            log(run, f"sending network {i} failed; again in a minute")
            time.sleep(60)
        rsync(args, os.path.join(run, "train.jsonl"), f"{args.share}/")
        rsync(args, os.path.join(run, "train_loop.log"), f"{args.share}/")
        if args.logs:
            rsync(args, args.logs.rstrip("/") + "/", f"{args.share}/remote_logs/")
        log(run, f"network {i} sent")
        # The hands are trained on once; old checkpoints go, except every 20th.
        subprocess.run(["rm", "-rf", data])
        old = i - args.keep
        if old > 0 and old % 20 != 0:
            for n in ("actor", "critic"):
                try:
                    os.remove(f"{ck}/{n}_{old}.pt")
                except OSError:
                    pass
        i += 1


if __name__ == "__main__":
    main()
