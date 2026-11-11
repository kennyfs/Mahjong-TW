#!/usr/bin/env python3
"""The app's screenshots on an emulator, driven over adb (device-tests.sh starts the emulator and installs the APK).

It plays as a person would, by taps found in the screen's accessibility tree (uiautomator dump): the first start's
welcome card, practice until a hand ends, its review by the network (a decision worth a look, a tile's figures,
everyone's tiles, the list of every action, the step taken to the analysis screen, the result), the analysis screen
with its tables, the records with their statistics, the rules with an explanation open, the settings, the rules
explained (with its quiz answered) and the tour, and battles (the entry's steps, and, when BATTLE_SERVER names a server
the emulator reaches, e.g. 10.0.2.2:8091, a table game's lobby and table). Each screen in landscape (the phone
sideways, first) and portrait, PNGs in OUT (default $WORK/shots/android). ONLY=name,... takes
only those shots; STEPS=step,... runs only those steps (practice, practice_result, analyze, records, settings, guide,
tour, battle, battle_table), e.g. STEPS=battle_table.

Usage: python3 android/scripts/shots.py   (ANDROID_SERIAL picks the emulator, e.g. emulator-5556)
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ.get('ANDROID_HOME', os.path.expanduser('~/Android/Sdk')), 'platform-tools', 'adb')
OUT = os.environ.get('OUT', '$WORK/shots/android')
SERVER = os.environ.get('BATTLE_SERVER', '')
ONLY = [x for x in os.environ.get('ONLY', '').split(',') if x]
STEPS = [x for x in os.environ.get('STEPS', '').split(',') if x]
PKG = 'io.github.kennyfs.mahjong'


def adb(*args, check=True, binary=False):
  r = subprocess.run([ADB, *args], capture_output=True, check=False)
  if check and r.returncode != 0:
    raise RuntimeError(f'adb {" ".join(args)}: {r.stderr.decode(errors="replace")}')
  return r.stdout if binary else r.stdout.decode(errors='replace')


def sh(cmd):
  return adb('shell', cmd)


def log(*a):
  print(time.strftime('%H:%M:%S'), *a, flush=True)


class Node:
  def __init__(self, e):
    self.text = e.get('text') or ''
    self.desc = e.get('content-desc') or ''
    self.clickable = e.get('clickable') == 'true'
    # A Compose test tag (MainActivity sets testTagsAsResourceId).
    self.rid = e.get('resource-id') or ''
    self.enabled = e.get('enabled') == 'true'
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', e.get('bounds')))
    self.box = (x1, y1, x2, y2)

  @property
  def center(self):
    x1, y1, x2, y2 = self.box
    return (x1 + x2) // 2, (y1 + y2) // 2

  def __repr__(self):
    return f'Node({self.text!r}, {self.desc!r}, {self.rid!r}, {self.box})'


def ui():
  """The screen's nodes (tries again: a dump fails while the screen is changing)."""
  for _ in range(5):
    out = sh('uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml')
    if out.startswith('<?xml'):
      try:
        return [Node(e) for e in ET.fromstring(out).iter('node')]
      except ET.ParseError:
        pass
    time.sleep(0.5)
  return []


def find(nodes, text=None, desc=None, exact=True, rid=None):
  for n in nodes:
    if rid is not None and n.rid == rid:
      return n
    if text is not None and (n.text == text if exact else text in n.text):
      return n
    if desc is not None and (n.desc == desc if exact else desc in n.desc):
      return n
  return None


def tap(x, y):
  sh(f'input tap {x} {y}')


def tap_node(n):
  tap(*n.center)


def wait_for(fn, timeout=30, every=0.6):
  end = time.time() + timeout
  while time.time() < end:
    nodes = ui()
    r = fn(nodes)
    if r:
      return r
    time.sleep(every)
  return None


def tap_id(rid, timeout=20):
  n = wait_for(lambda ns: find(ns, rid=rid), timeout)
  if not n:
    raise RuntimeError(f'not found: {rid}')
  tap_node(n)
  time.sleep(0.6)
  return n


def tap_text(text, timeout=20, exact=True):
  n = wait_for(lambda ns: find(ns, text=text, exact=exact) or find(ns, desc=text, exact=exact), timeout)
  if not n:
    raise RuntimeError(f'not found: {text}')
  tap_node(n)
  time.sleep(0.6)
  return n


def shot(name):
  if ONLY and name not in ONLY:
    return
  time.sleep(0.8)
  os.makedirs(OUT, exist_ok=True)
  png = adb('exec-out', 'screencap -p', binary=True)
  path = os.path.join(OUT, name + '.png')
  with open(path, 'wb') as f:
    f.write(png)
  log('shot', path)


def rotation():
  m = re.search(r'^\s+mRotation=(\d)', sh('dumpsys window'), re.M)
  return int(m.group(1)) if m else -1


def rotate(landscape):
  """The window manager's own lock, with the app in front (the launcher, upright only, would undo it)."""
  want = 1 if landscape else 0
  for _ in range(5):
    sh(f'wm user-rotation lock {want}')
    time.sleep(1.5)
    if rotation() == want:
      return
  raise RuntimeError(f'the display did not turn to {want}')


def screen_size():
  m = re.search(r'(\d+)x(\d+)', sh('wm size'))
  w, h = int(m.group(1)), int(m.group(2))
  return w, h


def go(tab):
  """A screen by its tab (the bar's or rail's label)."""
  tap_text(tab)


def back():
  sh('input keyevent KEYCODE_BACK')
  time.sleep(0.8)


def hand_tiles(nodes):
  """The player's own tiles (MyHand's tags: hand_0... and hand_drawn), left to right."""
  return [n for n in nodes if n.rid.startswith('hand_')]


def play_hand(limit=500):
  """Plays the player's seat to the end of the hand: wins when it can, passes on claims, discards the drawn tile."""
  for _ in range(limit):
    nodes = ui()
    if find(nodes, rid='result_next'):
      return True
    win = find(nodes, rid='claim_tsumo') or find(nodes, rid='claim_ron')
    if win:
      tap_node(win)
      time.sleep(1.0)
      continue
    p = find(nodes, rid='claim_pass')
    if p:
      tap_node(p)
      time.sleep(0.4)
      continue
    t = find(nodes, rid='hand_drawn')
    if t:
      tap_node(t)
      time.sleep(0.2)
      tap_node(t)
    else:
      mine = hand_tiles(nodes)
      # After a claim (no tile drawn): the last tile.
      if mine and len(mine) % 3 == 2:
        tap_node(mine[-1])
        time.sleep(0.2)
        tap_node(mine[-1])
    time.sleep(0.4)
  return False


def practice(orient):
  go('練習')
  # The last hand's result (from the other orientation's turn) gives way to the next hand.
  n = wait_for(lambda ns: find(ns, rid='result_next') or find(ns, rid='hand_drawn') or (hand_tiles(ns) or None), 20)
  if isinstance(n, Node) and n.rid == 'result_next':
    tap_node(n)
  # A hand in play: the player's turn (the table at rest).
  wait_for(lambda ns: find(ns, rid='hand_drawn'), 40)
  shot(f'play-{orient}')


def swipe_up(times=1):
  """Scrolls what is in the middle of the screen towards its end."""
  w, h = screen_size()
  if rotation() in (1, 3):
    w, h = h, w
  for _ in range(times):
    sh(f'input swipe {w // 3} {int(h * 0.72)} {w // 3} {int(h * 0.22)} 250')
    time.sleep(0.4)


def scroll_to(rid, times=10):
  """Scrolls down until the node with this test tag is on the screen; the node, or None."""
  for _ in range(times):
    n = find(ui(), rid=rid)
    if n:
      return n
    swipe_up()
  return find(ui(), rid=rid)


def practice_result(orient):
  """A hand to its end and its result; its review: a decision worth a look, a tile's figures, everyone's tiles, the
  list of every action, the step taken to the analysis screen, and the result again."""
  go('練習')
  log('playing a hand...')
  if not play_hand():
    log('the hand did not end')
    return
  # The network's review of the hand comes in a moment after the result.
  wait_for(lambda ns: find(ns, rid='result_review_line'), 30)
  shot(f'play-result-{orient}')
  tap_id('result_review')
  wait_for(lambda ns: find(ns, rid='replay_mistakes'), 40)
  # A decision worth a look (a mistake, or a good move that is not the obvious one); else one of the player's own.
  n = find(ui(), rid='replay_next_key')
  tap_node(n if n and n.enabled else find(ui(), rid='replay_next_mine'))
  time.sleep(1.0)
  shot(f'replay-{orient}')
  mine = hand_tiles(ui())
  if mine:
    tap_node(mine[min(3, len(mine) - 1)])
    time.sleep(0.6)
    shot(f'replay-tile-{orient}')
  tap_id('replay_all')
  time.sleep(1.0)
  shot(f'replay-all-{orient}')
  tap_id('replay_all')
  # The list of every action: its own view where there is no room for it beside the table.
  if find(ui(), rid='replay_panel'):
    tap_id('replay_panel')
    wait_for(lambda ns: find(ns, text='期望') or find(ns, text='最佳'), 20)
    shot(f'replay-list-{orient}')
  n = None
  for _ in range(8):
    n = find(ui(), rid='replay_to_analysis')
    if n:
      break
    swipe_up()
  if n:
    shot(f'replay-list-end-{orient}')
    tap_node(n)
    wait_for(lambda ns: find(ns, rid='analyze_results'), 30)
    shot(f'analyze-from-review-{orient}')
    go('練習')
  if find(ui(), rid='replay_panel'):
    n = find(ui(), rid='replay_to_analysis') or find(ui(), text='期望')
    if n:
      tap_id('replay_panel')
  tap_id('replay_last')
  tap_id('replay_result')
  shot(f'replay-result-{orient}')
  back()


def analyze(orient):
  go('分析')
  tap_id('analyze_random')
  wait_for(lambda ns: find(ns, rid='analyze_results'), 30)
  shot(f'analyze-{orient}')
  if orient == 'port':
    # The palette away, to read the figures.
    n = find(ui(), rid='analyze_palette_toggle')
    if n:
      tap_node(n)
      time.sleep(0.6)
      shot(f'analyze-figures-{orient}')
  n = None
  for _ in range(10):
    n = find(ui(), rid='analyze_whatif')
    if n:
      break
    swipe_up()
  if n:
    tap_node(n)
    wait_for(lambda ns: find(ns, text='摸牌表'), 60)
    swipe_up()
    shot(f'analyze-tables-{orient}')


def records(orient):
  go('紀錄')
  wait_for(lambda ns: find(ns, rid='records_stats'), 10)
  shot(f'records-{orient}')


def settings(orient):
  go('設定')
  shot(f'settings-{orient}')
  if orient == 'port':
    swipe_up(2)
    shot(f'settings-analysis-{orient}')
    sh('input swipe 500 600 500 2000 200')
    time.sleep(0.5)
  tap_text('規則')
  wait_for(lambda ns: find(ns, text='台數'), 10)
  n = scroll_to('rules_advanced')
  if not n:
    raise RuntimeError('not found: rules_advanced')
  tap_node(n)
  time.sleep(0.6)
  n = scroll_to('rules_help_base', 3)
  if n:
    tap_node(n)
    time.sleep(0.5)
  shot(f'rules-{orient}')
  back()


def guide(orient):
  """The rules explained, from the settings: its first page (the rules to explain), the tiles, tai, and the quiz."""
  go('設定')
  tap_id('settings_guide')
  wait_for(lambda ns: find(ns, rid='guide_next'), 10)
  shot(f'guide-rules-{orient}')
  for page in range(1, 7):
    tap_id('guide_next')
    time.sleep(0.5)
    if page == 2:
      shot(f'guide-tiles-{orient}')
    elif page == 5:
      shot(f'guide-tai-{orient}')
  # The quiz: two of the candidates picked, one of them wrong, and the answer.
  for x in ('guide_pick_5s', 'guide_pick_6s'):
    n = scroll_to(x, 3)
    if n:
      tap_node(n)
  shot(f'guide-quiz-{orient}')
  n = scroll_to('guide_check', 3)
  if n:
    tap_node(n)
    time.sleep(0.5)
    shot(f'guide-answer-{orient}')
  tap_id('guide_next')
  time.sleep(0.5)
  shot(f'guide-end-{orient}')
  back()


def tour(orient):
  """The tour, from the settings: its first steps (the hand, the top of practice, the analysis' tab) and its end."""
  go('設定')
  tap_id('settings_tour')
  wait_for(lambda ns: find(ns, rid='tour_card'), 10)
  for i in range(7):
    time.sleep(0.6)
    if i in (0, 1, 2, 6):
      shot(f'tour-{i + 1}-{orient}')
    tap_id('tour_next')
  time.sleep(0.6)


def battle(orient):
  """The entry: its first page (nearby: no server set), a new room's kinds, and joining."""
  go('對戰')
  shot(f'battle-entry-{orient}')
  tap_id('battle_create')
  time.sleep(0.5)
  shot(f'battle-create-{orient}')
  back()
  tap_id('battle_join')
  time.sleep(0.5)
  shot(f'battle-join-{orient}')
  back()


def battle_table(orient):
  """A table game on BATTLE_SERVER: the player and three bots; the lobby, then the table."""
  if not SERVER:
    return
  go('對戰')
  tap_text('線上')
  tap_id('battle_create')
  nodes = ui()
  if not find(nodes, rid='battle_server'):
    n = find(nodes, text='更改')
    if n:
      tap_node(n)
  field = wait_for(lambda ns: find(ns, rid='battle_server'), 10)
  if field and SERVER not in (field.text or ''):
    tap_node(field)
    sh(f'input text {SERVER}')
    sh('input keyevent KEYCODE_BACK')
    time.sleep(0.5)
  tap_id('battle_create_table')
  # Android 17 asks for local network access first (the server is on the emulator's host): allow it, as a person would.
  allow = ('com.android.permissioncontroller:id/permission_allow_button',
           'com.android.permissioncontroller:id/permission_allow_foreground_only_button')
  n = wait_for(lambda ns: find(ns, rid='battle_start') or next((x for x in ns if x.rid in allow), None), 30)
  if n and n.rid in allow:
    log('allowing local network access')
    tap_node(n)
  if not wait_for(lambda ns: find(ns, rid='battle_start'), 30):
    raise RuntimeError('not found: battle_start')
  shot(f'battle-lobby-{orient}')
  tap_id('battle_start')
  wait_for(lambda ns: len(hand_tiles(ns)) >= 16, 40)
  time.sleep(1.0)
  shot(f'battle-table-{orient}')
  # Out of the room again: Back asks, Leave confirms.
  back()
  n = wait_for(lambda ns: [x for x in ns if x.text == '離開'], 10)
  if n:
    tap_node(n[-1])
  time.sleep(1.0)


def main():
  log('device', adb('get-serialno').strip())
  sh(f'am force-stop {PKG}')
  sh(f'pm clear {PKG}')
  # pm clear kills the app on its own time: a start right after can lose that race and leave the splash screen up
  # with no process behind it. So wait, and start again until the app shows its navigation.
  time.sleep(2)
  for _ in range(3):
    sh(f'am start -W -n {PKG}/.MainActivity')
    if wait_for(lambda ns: find(ns, rid='nav_PLAY') or find(ns, text='練習') or find(ns, rid='welcome_skip'), 20):
      break
    log('the app did not show; starting it again')
    sh(f'am force-stop {PKG}')
    time.sleep(2)
  else:
    raise RuntimeError('the app did not start')
  # The first start's welcome card: shown once, then put away.
  if find(ui(), rid='welcome_skip'):
    shot('welcome')
    tap_id('welcome_skip')
    time.sleep(0.6)
  steps = [practice, practice_result, analyze, records, settings, guide, tour, battle, battle_table]
  for orient in ('land', 'port'):
    rotate(orient == 'land')
    for step in steps:
      if not STEPS or step.__name__ in STEPS:
        step(orient)
  rotate(False)
  log('done')


if __name__ == '__main__':
  try:
    main()
  except Exception as e:
    log('error', e)
    shot('error')
    sys.exit(1)
