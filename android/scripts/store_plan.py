#!/usr/bin/env python3
"""The store screenshots' game (shots.py STORE=1): practice from a fixed seed, played here by the engine exactly as the
app plays it (Practice.kt, Claims.kt), the player's seat by the network's policy, so that shots.py can make the same
moves on the emulator. Searches seeds for a first hand worth showing (the player wins it with a few 台, the review finds
a mistake, and some turn has calls and flowers on the table), then plays the rest of the game's hands that the shots
need, and writes the plan: the seed, the rules, and for each hand the player's moves in the order they come.

    python3 android/scripts/store_plan.py [--seeds 1-400] [--hands 4] [--out android/scripts/store-plan.json]

Needs the engine built with ONNX Runtime (cpp/build/mahjong) and the network the app ships (MODEL, default
web/engine-files/actor.onnx). The player's moves are what the screen can make: a discard (a tile of the hand, by its
place in the sorted hand, or the tile drawn), a win, a pung, a pass, and a chow or a kong when it is the only one of
its kind; the network's choice otherwise gives way to the best of those by its policy.
"""
import argparse
import json
import os
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BIN = ROOT / 'cpp' / 'build' / 'mahjong'
MODEL = os.environ.get('MODEL', str(ROOT / 'web' / 'engine-files' / 'actor.onnx'))
ME = 0
RULES = 'simple2,see'
NET = f'net:model={MODEL},mode=policy'
BOTS = [None, NET, NET, NET]
# The grades of Review.kt (a typical payment of 4).
GRADE_STEPS = (0.1, 0.25, 0.5)
UNIQUE_ONLY = ('chi', 'kan', 'ankan', 'kakan')
ALLOWED = ('discard', 'tsumo', 'ron', 'pon', 'pass') + UNIQUE_ONLY
SUITS = 'mpsz'


class Engine:
  def __init__(self):
    self.p = subprocess.Popen([str(BIN), 'gui'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1)

  def __call__(self, **kw):
    self.p.stdin.write(json.dumps(kw) + '\n')
    self.p.stdin.flush()
    r = json.loads(self.p.stdout.readline())
    if 'error' in r:
      raise RuntimeError(f"{kw.get('cmd')}: {r['error']}")
    return r


def parse(s):
  out, digits = [], ''
  for c in s or '':
    if c.isdigit():
      digits += c
    else:
      out += [d + c for d in digits]
      digits = ''
  return out


def order(t):
  return 'mpszf'.index(t[1]) * 10 + int(t[0])


class Sim:
  def __init__(self, e, game_id):
    self.e, self.id = e, game_id

  def act(self, g, s, a):
    return self.e(cmd='act', game=g, seat=s, action=a)['game']

  def view(self, g, s=ME):
    return self.e(cmd='view', game=g, seat=s)

  def preclaim(self, g, seed):
    """Claims.preclaim: (the game, robbed) or None (the player's own turn)."""
    p = self.e(cmd='pending', game=g)
    if p['phase'] in ('self', 'over') or ME not in p['pending']:
      return None
    others = [s for s in p['pending'] if s != ME and BOTS[s]]
    answers = [self.e(cmd='bot', game=g, seat=s, player=BOTS[s], seed=f'{seed}/{s}')['action'] for s in others]
    g2 = g
    for s, a in zip(others, answers):
      g2 = self.act(g2, s, a)
    acts = g['actions']
    since = next((k for k in range(len(acts) - 1, -1, -1) if acts[k][1] in ('discard', 'kakan')), -1)
    before = any(a[1] != 'pass' for a in acts[since + 1:])
    if all(a['type'] == 'pass' for a in answers) and not before:
      return g2, False
    options = [a for a in self.view(g2)['legal'] if a['type'] != 'pass']
    for o in options:
      tried = self.act(g2, ME, o)
      if self.e(cmd='steps', game=tried)['taken'][len(tried['actions']) - 1]:
        return g2, False
    done = self.act(g2, ME, {'type': 'pass'})
    return done, True

  def choose(self, g, v, seed):
    """The player's move: the network's, or the best by its policy of what the screen can make."""
    legal = v['legal']
    def ok(a):
      return a['type'] in ALLOWED and (a['type'] not in UNIQUE_ONLY or sum(x['type'] == a['type'] for x in legal) == 1)
    a = self.e(cmd='bot', game=g, seat=ME, player=NET, seed=seed)['action']
    if ok(a):
      return a, False
    an = self.e(cmd='analyze', game=g, seat=ME, model=MODEL)
    best = max((f for f in an['actions'] if ok(f['action'])), key=lambda f: f['policy'])
    return best['action'], True

  def move(self, v, a, phase):
    """What the screen does for `a`: the tile's place in the sorted hand, or the drawn tile."""
    m = {'phase': phase, 'type': a['type']}
    if a.get('tile'):
      m['tile'] = a['tile']
    if a['type'] == 'discard':
      pos = v['position']
      hand = parse(pos['hand'])
      drawn = pos['drawn'] if v['phase'] == 'self' and v['current'] == ME else None
      if drawn and a['tile'] == drawn:
        m['index'] = 'drawn'
      else:
        if drawn:
          hand.remove(drawn)
        hand.sort(key=order)
        m['index'] = hand.index(a['tile'])
    if a['type'] == 'chi':
      m['tiles'] = a.get('tiles')
    return m

  def hand(self, g, hands):
    """One hand to its end as practice plays it: (the record, the player's moves, the table at each self turn)."""
    moves, turns, gave_way = [], [], 0
    for _ in range(2000):
      p = self.e(cmd='pending', game=g)
      if p['phase'] == 'over':
        return g, moves, turns, gave_way
      seed = f'{self.id}/{hands}/{len(g["actions"])}'
      if ME in p['pending']:
        pc = self.preclaim(g, seed)
        if pc is not None:
          g2, robbed = pc
          g = g2
          if robbed:
            continue
        v = self.view(g)
        phase = 'self' if v['phase'] == 'self' else 'claim'
        a, gw = self.choose(g, v, seed)
        gave_way += gw
        m = self.move(v, a, phase)
        if phase == 'self':
          pos = v['position']
          turns.append({'move': len(moves), 'wall_left': pos['wall_left'],
                        'melds': [len(pl['melds']) for pl in pos['players']],
                        'flowers': [len(parse(pl['flowers'])) for pl in pos['players']],
                        'discards': sum(len(pl['discards']) for pl in pos['players'])})
        moves.append(m)
        g = self.act(g, ME, a)
        continue
      g = self.e(cmd='advance', game=g, players=BOTS, seed=seed, steps=1)['game']
    raise RuntimeError('the hand did not end')


def first_hand(n):
  """Seed n's first hand and how good it is for the store."""
  e = Engine()
  gid = f'store-{n}'
  sim = Sim(e, gid)
  dealer = 0
  g = e(cmd='new', rules=RULES, setup={'dealer': dealer}, seed=f'{gid}/0')['game']
  g, moves, turns, gave_way = sim.hand(g, 0)
  res = e(cmd='view', game=g, seat=ME, reveal=True)['result']
  won = [w for w in res['winners'] if w['seat'] == ME]
  out = {'n': n, 'moves': len(moves), 'gave_way': gave_way, 'won': bool(won)}
  if not won:
    e.p.kill()
    return out
  rv = e(cmd='review', game=g, seat=ME, model=MODEL)
  unit = rv.get('unit') or 4.0
  grades = [0, 0, 0]
  for d in rv['decisions']:
    x = max(0.0, d.get('loss') or 0.0) / (unit / 4)
    k = 3 if x >= GRADE_STEPS[2] else 2 if x >= GRADE_STEPS[1] else 1 if x >= GRADE_STEPS[0] else 0
    if k:
      grades[k - 1] += 1
  # The table worth a look: a turn with calls and flowers out, half the wall gone.
  best = None
  for t in turns:
    score = sum(t['melds']) * 2 + sum(t['flowers']) + (3 if t['melds'][ME] else 0)
    if 18 <= t['wall_left'] <= 45 and (best is None or score > best[0]):
      best = (score, t)
  out.update(tai=won[0]['tai'], items=[(i['name'], i['tai']) for i in won[0]['items']], self_draw=res['self_draw'],
             grades=grades, table=best[1] if best else None, table_score=best[0] if best else 0, loser=res['loser'])
  e.p.kill()
  return out


def plan(n, hands):
  e = Engine()
  gid = f'store-{n}'
  sim = Sim(e, gid)
  dealer = 0
  g = e(cmd='new', rules=RULES, setup={'dealer': dealer}, seed=f'{gid}/0')['game']
  out = []
  for h in range(hands):
    g, moves, turns, gave_way = sim.hand(g, h)
    res = e(cmd='view', game=g, seat=ME, reveal=True)['result']
    out.append({'moves': moves, 'turns': turns, 'gave_way': gave_way,
                'result': {'winners': [(w['seat'], w['tai']) for w in res['winners']], 'loser': res['loser'],
                           'self_draw': res['self_draw'], 'points': res['points']}})
    if h + 1 < hands:
      nx = e(cmd='next', game=g, start_dealer=dealer, seed=f'{gid}/next/{h + 1}')
      if nx['game_over']:
        break
      g = nx['game']
  e.p.kill()
  return out


def main():
  ap = argparse.ArgumentParser()
  ap.add_argument('--seeds', default='1-400')
  ap.add_argument('--hands', type=int, default=4)
  ap.add_argument('--jobs', type=int, default=24)
  ap.add_argument('--pick', type=int, help='this seed, without searching')
  ap.add_argument('--out', default=str(ROOT / 'android' / 'scripts' / 'store-plan.json'))
  a = ap.parse_args()
  if a.pick is None:
    lo, hi = map(int, a.seeds.split('-'))
    with ProcessPoolExecutor(a.jobs) as ex:
      found = list(ex.map(first_hand, range(lo, hi + 1)))
    good = [f for f in found if f['won'] and f['grades'][1] + f['grades'][2] > 0 and f['table'] and f['gave_way'] == 0]
    good.sort(key=lambda f: (-min(f['tai'], 6), -f['table_score']))
    for f in good[:15]:
      print(json.dumps(f, ensure_ascii=False))
    print(f'{sum(f["won"] for f in found)} of {len(found)} first hands won by the player; {len(good)} worth showing')
    if not good:
      sys.exit(1)
    a.pick = good[0]['n']
  hs = plan(a.pick, a.hands)
  doc = {'seed': f'store-{a.pick}', 'rules': RULES, 'hands': hs}
  Path(a.out).write_text(json.dumps(doc, ensure_ascii=False, indent=1) + '\n')
  for i, h in enumerate(hs):
    print(f'hand {i + 1}: {len(h["moves"])} moves, gave way {h["gave_way"]}, {h["result"]}')
  print('wrote', a.out)


if __name__ == '__main__':
  main()
