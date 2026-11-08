#!/usr/bin/env python3
"""The app's screenshots on an emulator, driven over adb (device-tests.sh starts the emulator and installs the APK).

It plays as a person would, by taps found in the screen's accessibility tree (uiautomator dump): practice until a hand
ends, its review and a decision's analysis, the analysis screen, the records, the rules, the settings, and battles (the
entry, and, when BATTLE_SERVER names a server the emulator reaches, e.g. 10.0.2.2:8091, a table game's lobby and
table). Each screen in landscape (the phone sideways, first) and portrait, PNGs in OUT (default
$WORK/shots/android). ONLY=name,... takes only those shots; STEPS=step,... runs only those
steps (practice, practice_result, analyze, records, settings, battle, battle_table), e.g. STEPS=battle_table.

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


def practice_result(orient):
  """A hand to its end, its result; its review, and a decision's analysis."""
  go('練習')
  log('playing a hand...')
  if not play_hand():
    log('the hand did not end')
    return
  time.sleep(1.5)
  shot(f'play-result-{orient}')
  tap_id('result_review')
  wait_for(lambda ns: find(ns, rid='replay_step_4'), 15)
  # A few of the player's decisions in, where one is to be made (not the end).
  for _ in range(3):
    n = find(ui(), rid='replay_step_4')
    if not n or not n.enabled:
      break
    tap_node(n)
    time.sleep(0.5)
  if not find(ui(), rid='replay_analyze'):
    tap_id('replay_step_1')
  shot(f'replay-{orient}')
  tap_id('replay_analyze')
  wait_for(lambda ns: find(ns, text='最佳'), 20)
  shot(f'analysis-{orient}')
  back()
  back()


def analyze(orient):
  go('分析')
  tap_id('analyze_random')
  shot(f'analyze-edit-{orient}')
  tap_id('analyze_run')
  wait_for(lambda ns: find(ns, text='最佳'), 20)
  shot(f'analyze-{orient}')
  if orient == 'port':
    back()


def records(orient):
  go('紀錄')
  shot(f'records-{orient}')


def settings(orient):
  go('設定')
  shot(f'settings-{orient}')
  w, h = screen_size()
  if orient == 'port':
    tap_text('規則')
    shot(f'rules-{orient}')
    back()


def battle(orient):
  go('對戰')
  shot(f'battle-entry-{orient}')


def battle_table(orient):
  """A table game on BATTLE_SERVER: the player and three bots; the lobby, then the table."""
  if not SERVER:
    return
  go('對戰')
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
    if wait_for(lambda ns: find(ns, rid='nav_PLAY') or find(ns, text='練習'), 20):
      break
    log('the app did not show; starting it again')
    sh(f'am force-stop {PKG}')
    time.sleep(2)
  else:
    raise RuntimeError('the app did not start')
  steps = [practice, practice_result, analyze, records, settings, battle, battle_table]
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
