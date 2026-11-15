#!/usr/bin/env python3
"""The app's screenshots on an emulator, driven over adb (device-tests.sh starts the emulator and installs the APK).

It plays as a person would, by taps found in the screen's accessibility tree (uiautomator dump): the first start's
welcome card, practice until a hand ends, its review by the network (a decision worth a look, a tile's figures,
everyone's tiles, the list of every action, the step taken to the analysis screen, the result), the analysis screen
with its tables, the records with their statistics, the rules with an explanation open, the settings, the rules
explained (with its quiz answered) and the tour, and battles (the entry's steps, and, when BATTLE_SERVER names a server
the emulator reaches, e.g. 10.0.2.2:8091, a table game's lobby and table). Each screen in landscape (the phone
sideways, first) and portrait, PNGs in OUT (default $WORK/shots/android). ONLY=name,... takes
only those shots; STEPS=step,... runs only those steps (practice, practice_result, analyze, records, settings, manual,
guide, tour, battle, battle_table), e.g. STEPS=battle_table. APP_LOCALE=en-US: the app in English, its words found so.

Usage: python3 android/scripts/shots.py   (ANDROID_SERIAL picks the emulator, e.g. emulator-5556)

STORE=1: Google Play's screenshots instead (device-tests.sh STORE=1 sets up the emulator: 1920 x 1080 sideways), in
store order into OUT (default $WORK/shots/store): the game of store-plan.json (store_plan.py: a fixed seed, the
player's moves made as planned) with the table mid-hand, the hand the player wins, its review at a mistake, a
decision's figures, the analysis screen with a step of it, the records with their statistics, a table game on the
battle server (BATTLE_SERVER, default the app's own), and the rules explained. Each file is checked to be 1920 x 1080.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.join(os.environ.get('ANDROID_HOME', os.path.expanduser('~/Android/Sdk')), 'platform-tools', 'adb')
STORE = os.environ.get('STORE', '0') == '1'
OUT = os.environ.get('OUT', os.path.join(os.environ.get('WORK', 'build'), 'shots', 'store' if STORE else 'android'))
PLAN = os.environ.get('PLAN', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'store-plan.json'))
# The store's screenshots: Google Play's size, sideways.
STORE_SIZE = (1920, 1080)
SERVER = os.environ.get('BATTLE_SERVER', '')
ONLY = [x for x in os.environ.get('ONLY', '').split(',') if x]
STEPS = [x for x in os.environ.get('STEPS', '').split(',') if x]
# The app's language (device-tests.sh APP_LOCALE): the words looked for here are Chinese, found by their English
# strings when the app is in English (values-en/strings.xml, by the names of the Chinese ones).
LOCALE = os.environ.get('APP_LOCALE', '')


def _strings(d):
  path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res', d, 'strings.xml')
  root = ET.parse(path).getroot()
  return {e.get('name'): (e.text or '').strip().strip('"').replace("\\'", "'") for e in root.iter('string')}


WORDS = {}
if LOCALE.startswith('en'):
  _en = _strings('values-en')
  for _name, _zh in _strings('values').items():
    WORDS.setdefault(_zh, _en.get(_name, _zh))


def W(text):
  return WORDS.get(text, text) if text is not None else None


# A test build's id has MAHJONG_ID_SUFFIX (.test) after it.
PKG = 'io.github.kennyfs.mahjong' + os.environ.get('MAHJONG_ID_SUFFIX', '')


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
    # (A dump can fail while the screen changes, and then there is no file: not an error, try again.)
    out = adb('shell', 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml', check=False)
    if out.startswith('<?xml'):
      try:
        return [Node(e) for e in ET.fromstring(out).iter('node')]
      except ET.ParseError:
        pass
    time.sleep(0.5)
  return []


def find(nodes, text=None, desc=None, exact=True, rid=None):
  text, desc = W(text), W(desc)
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


def rotation_name():
  return 'land' if rotation() in (1, 3) else 'port'


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
    # The pause at the end of the hand (the table as it ended) before the result.
    if find(nodes, rid='result_skip'):
      shot(f'play-end-{rotation_name()}')
      wait_for(lambda ns: find(ns, rid='result_next'), 10)
      continue
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
    wait_for(lambda ns: find(ns, text='最佳'), 20)
    shot(f'replay-list-{orient}')
  # What the list's figures mean: a dialog.
  n = find(ui(), rid='list_help')
  if n:
    tap_node(n)
    time.sleep(0.6)
    shot(f'replay-list-help-{orient}')
    back()
  n = None
  for _ in range(8):
    n = find(ui(), rid='replay_to_analysis')
    if n:
      break
    swipe_up()
  if n:
    # Found by its first sliver over the panel under the list: a little further, so that the tap lands on it.
    sh('input swipe 500 1100 500 800 300')
    time.sleep(0.6)
    n = find(ui(), rid='replay_to_analysis') or n
    shot(f'replay-list-end-{orient}')
    tap_node(n)
    wait_for(lambda ns: find(ns, rid='analyze_results'), 30)
    shot(f'analyze-from-review-{orient}')
    go('練習')
  if find(ui(), rid='replay_panel'):
    n = find(ui(), rid='replay_to_analysis') or find(ui(), text='最佳')
    if n:
      tap_id('replay_panel')
  tap_id('replay_last')
  tap_id('replay_result')
  shot(f'replay-result-{orient}')
  # 下一局: the practice's next hand, from the review's end.
  if find(ui(), rid='replay_next_hand'):
    tap_id('replay_next_hand')
    wait_for(lambda ns: find(ns, rid='hand_drawn') or (hand_tiles(ns) or None), 40)
    shot(f'replay-next-hand-{orient}')
  else:
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


def manual(orient):
  """Practice at the manual pace (the settings): the computer players' steps wait for the button. A hand to its end by
  the button and the player's own turns, the words of the calls on the way (they stay until the next tap), the table
  at the end (it stays, too); then the pace as it was."""
  go('設定')
  if not scroll_to('settings_speed', 6):
    raise RuntimeError('not found: settings_speed')
  tap_text('手動')
  time.sleep(0.3)
  shot(f'manual-settings-{orient}')
  go('練習')
  n = wait_for(lambda ns: find(ns, rid='result_next') or find(ns, rid='next_step') or find(ns, rid='hand_drawn'), 40)
  if isinstance(n, Node) and n.rid == 'result_next':
    tap_node(n)
    time.sleep(1.0)
  presses = calls = 0
  ended = False
  for _ in range(260):
    nodes = ui()
    if find(nodes, rid='result_next'):
      ended = True
      break
    # The table at the hand's end stays until a tap.
    if find(nodes, rid='result_skip'):
      shot(f'manual-end-{orient}')
      time.sleep(3.0)
      if not find(ui(), rid='result_skip'):
        log('manual: the table at the end did not stay')
      w, h = screen_size()
      if rotation() in (1, 3):
        w, h = h, w
      tap(w // 2, h // 2)
      time.sleep(0.8)
      continue
    nx = find(nodes, rid='next_step')
    if nx:
      if presses == 0:
        shot(f'manual-wait-{orient}')
      tap_node(nx)
      presses += 1
      time.sleep(0.7)
      after = ui()
      if find(after, rid='callout') and calls < 4 and not find(after, rid='result_skip'):
        calls += 1
        shot(f'manual-call{calls}-{orient}')
        # The words stay at the manual pace.
        time.sleep(2.6)
        if not find(ui(), rid='callout'):
          log('manual: the words did not stay')
      continue
    win = find(nodes, rid='claim_tsumo') or find(nodes, rid='claim_ron')
    p = find(nodes, rid='claim_pass')
    t = find(nodes, rid='hand_drawn')
    if win:
      tap_node(win)
      time.sleep(1.0)
    elif p:
      tap_node(p)
    elif t:
      tap_node(t)
      time.sleep(0.2)
      tap_node(t)
    else:
      mine = hand_tiles(nodes)
      if mine and len(mine) % 3 == 2:
        tap_node(mine[-1])
        time.sleep(0.2)
        tap_node(mine[-1])
    time.sleep(0.4)
  log(f'manual: {presses} presses, {calls} calls shown, the hand ended: {ended}')
  if ended:
    shot(f'manual-result-{orient}')
  go('設定')
  if scroll_to('settings_speed', 6):
    tap_text('中')


def guide(orient):
  """The rules explained, from the settings: its first page (the rules to explain), the tiles, tai, and the quiz."""
  go('設定')
  # (Further down in English, or with a large font: scrolled to.)
  scroll_to('settings_guide')
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


def png_size(path):
  with open(path, 'rb') as f:
    head = f.read(24)
  return int.from_bytes(head[16:20], 'big'), int.from_bytes(head[20:24], 'big')


def wait_gone(rid, timeout=10):
  end = time.time() + timeout
  while time.time() < end:
    if not find(ui(), rid=rid):
      return True
    time.sleep(0.3)
  return False


def play_planned(moves, table_at=None, name=None):
  """A hand to its end by the plan's moves (store_plan.py), each when the screen asks for it: a claim's buttons, or the
  player's own turn (a tile more than a whole hand). At move `table_at` the tile is lifted first and the table shot."""
  k = 0
  for _ in range(3000):
    nodes = ui()
    if find(nodes, rid='result_skip') or find(nodes, rid='result_next'):
      if k != len(moves):
        raise RuntimeError(f'the hand ended after {k} of {len(moves)} planned moves')
      return
    if k >= len(moves):
      time.sleep(0.5)
      continue
    m = moves[k]
    asked = find(nodes, rid='claim_pass')
    mine = hand_tiles(nodes)
    own = not asked and len(mine) % 3 == 2
    if m['phase'] == 'claim':
      if not asked:
        if own:
          raise RuntimeError(f'move {k}: planned a claim ({m}), the screen has the player\'s own turn')
        time.sleep(0.3)
        continue
      n = find(nodes, rid=f"claim_{m['type']}")
      if not n:
        raise RuntimeError(f'move {k}: no button for {m}')
      tap_node(n)
      if not wait_gone('claim_pass'):
        raise RuntimeError(f'move {k}: the claim did not go')
      k += 1
      continue
    if asked:
      raise RuntimeError(f'move {k}: planned the player\'s own turn ({m}), the screen asks about a claim')
    if not own:
      time.sleep(0.3)
      continue
    if m['type'] != 'discard':
      n = wait_for(lambda ns: find(ns, rid=f"claim_{m['type']}"), 5)
      if not n:
        raise RuntimeError(f'move {k}: no button for {m}')
      tap_node(n)
      time.sleep(1.0)
      k += 1
      continue
    rid = 'hand_drawn' if m['index'] == 'drawn' else f"hand_{m['index']}"
    t = find(nodes, rid=rid)
    if not t:
      raise RuntimeError(f'move {k}: no tile {rid} for {m}')
    tap_node(t)
    if k == table_at:
      # The words of the last call gone, the lifted tile at rest.
      time.sleep(3.0)
      shot(name)
      t = find(ui(), rid=rid) or t
    else:
      time.sleep(0.25)
    tap_node(t)
    # The discard taken: the tiles back to a whole hand.
    end = time.time() + 10
    while time.time() < end and len(hand_tiles(ui())) % 3 == 2:
      time.sleep(0.2)
    k += 1
  raise RuntimeError('the hand did not end')


def to_result():
  """From the table at a hand's end to its result, with the network's review in its line."""
  n = wait_for(lambda ns: find(ns, rid='result_skip') or find(ns, rid='result_next'), 30)
  if isinstance(n, Node) and n.rid == 'result_skip':
    tap_node(n)
  if not wait_for(lambda ns: find(ns, rid='result_next'), 20):
    raise RuntimeError('no result')
  if not wait_for(lambda ns: find(ns, rid='result_review_line'), 120):
    raise RuntimeError('the review did not come')


def store_review():
  """The first hand's review: at its first mistake (3-review), then the player's last discard before the win with every
  choice's figures (4-figures), and that decision on the analysis screen (5-analysis)."""
  tap_id('result_review')
  if not wait_for(lambda ns: find(ns, rid='replay_mistakes'), 120):
    raise RuntimeError('the review did not open')
  # To the decisions worth a look, until one is a mistake (its grade's words).
  for _ in range(12):
    n = find(ui(), rid='replay_next_key')
    if not n or not n.enabled:
      raise RuntimeError('no mistake in the review')
    tap_node(n)
    time.sleep(1.2)
    g = find(ui(), rid='replay_grade')
    if g and '失誤' in g.text:
      break
  else:
    raise RuntimeError('no mistake found')
  time.sleep(1.0)
  shot('3-review')
  # The player's decisions on to the win, and back one: the discard that left the hand ready (its danger now is more
  # than nothing, late in the hand).
  for _ in range(40):
    c = find(ui(), rid='replay_choice')
    if c and '胡' in c.text:
      break
    tap_id('replay_next_mine')
    time.sleep(0.6)
  else:
    raise RuntimeError('no win in the review')
  tap_id('replay_prev_mine')
  time.sleep(1.0)
  if not find(ui(), text='當下危險'):
    tap_id('replay_panel')
  wait_for(lambda ns: find(ns, text='當下危險'), 20)
  time.sleep(1.0)
  shot('4-figures')
  # The list's end (its middle column), and the step to the analysis screen.
  n = None
  for _ in range(12):
    n = find(ui(), rid='replay_to_analysis')
    if n:
      break
    sh('input swipe 1030 900 1030 250 300')
    time.sleep(0.6)
  if not n:
    raise RuntimeError('not found: replay_to_analysis')
  # Found by its first sliver: a little further, so that the tap lands on it.
  sh('input swipe 1030 900 1030 700 300')
  time.sleep(0.6)
  tap_node(find(ui(), rid='replay_to_analysis') or n)
  if not wait_for(lambda ns: find(ns, rid='analyze_results'), 60):
    raise RuntimeError('no analysis')
  time.sleep(1.5)
  # The right column (the palette, then the figures) moved up so that the figures' legend comes right under its bar,
  # the palette's last row out of sight above it.
  if not find(ui(), text='和最佳相比，差在：'):
    sh('input swipe 1500 950 1500 450 1500')
    time.sleep(0.8)
  if not nudge('和最佳相比，差在：', 155, x=1500):
    log('the figures did not come where they should')
  shot('5-analysis')


def store():
  import json
  plan = json.load(open(PLAN))
  hands = plan['hands']
  first = hands[0]
  table_at = max(first['turns'], key=lambda t: sum(t['melds']) * 2 + sum(t['flowers']) + (3 if t['melds'][0] else 0)
                 if 18 <= t['wall_left'] <= 45 else -1)['move']
  log('plan', plan['seed'], plan['rules'], len(hands), 'hands; the table at move', table_at)
  sh(f'am force-stop {PKG}')
  sh(f'pm clear {PKG}')
  # The app's language (pm clear forgets it): APP_LOCALE's, or the system's.
  sh(f"cmd locale set-app-locales {PKG} --locales '{LOCALE}'")
  time.sleep(2)
  # A new task and a new process (-S, --activity-clear-task): the system restarts an app it has just updated (Android
  # 17), without the extras, and a start without these flags only comes to that one. The store's rules on the screen
  # say the extras were read.
  for _ in range(3):
    sh(f"am start -S -W --activity-clear-task -n {PKG}/io.github.kennyfs.mahjong.MainActivity "
       f"--es store_seed {plan['seed']} --es store_rules {plan['rules']}")
    # (The first start's welcome card is a window of its own: the table's nodes are not in the dump under it.)
    if wait_for(lambda ns: find(ns, rid='welcome_skip') or find(ns, rid='nav_PLAY'), 20):
      if wait_for(lambda ns: find(ns, rid='welcome_skip'), 8):
        tap_id('welcome_skip')
      if wait_for(lambda ns: find(ns, text='見花見台', exact=False), 10):
        break
    log('the app did not start with the store\'s game; starting it again')
    sh(f'am force-stop {PKG}')
    sh(f'pm clear {PKG}')
    # The app's language (pm clear forgets it): APP_LOCALE's, or the system's.
    sh(f"cmd locale set-app-locales {PKG} --locales '{LOCALE}'")
    time.sleep(2)
  else:
    raise RuntimeError('the app did not start with the store\'s game')
  rotate(True)
  w, h = screen_size()
  log('screen', w, h)
  play_planned(first['moves'], table_at, '1-table')
  to_result()
  time.sleep(1.0)
  shot('2-result')
  store_review()
  store_rest(hands)
  rotate(False)
  bad = []
  for f in sorted(os.listdir(OUT)):
    if f.endswith('.png'):
      size = png_size(os.path.join(OUT, f))
      log('size', f, size)
      if size != STORE_SIZE:
        bad.append(f)
  if bad:
    raise RuntimeError(f'not {STORE_SIZE}: {bad}')


def store_rest(hands):
  """Back to practice from the analysis screen (the review under it stays on practice's tab), the game's other hands,
  then the records, a battle and the rules explained."""
  go('練習')
  if wait_for(lambda ns: find(ns, rid='replay_back'), 5):
    tap_id('replay_back')
  for i, hd in enumerate(hands[1:], 2):
    tap_id('result_next', 30)
    log('hand', i)
    play_planned(hd['moves'])
    to_result()
  go('紀錄')
  wait_for(lambda ns: find(ns, rid='records_stats'), 10)
  time.sleep(1.0)
  shot('6-records')
  store_battle()
  store_rules()


def nudge(text, y_want, x=1000, tries=4):
  """Scrolls what is under x until the node with this text has its top at y_want (slow swipes, which do not fling;
  a swipe moves the page by its length less the touch slop)."""
  for _ in range(tries):
    n = find(ui(), text=text)
    if not n:
      return False
    d = n.box[1] - y_want
    if abs(d) <= 6:
      return True
    slop = 20 if d > 0 else -20
    sh(f'input swipe {x} 700 {x} {max(150, min(1000, 700 - d - slop))} 1200')
    time.sleep(0.8)
  return False


def store_battle():
  """A table game on the battle server: the player and three bots. The player discards the tile drawn (and lets
  every claim go) for a few turns, so that the rivers fill, and the table is shot on the player's turn."""
  go('對戰')
  tap_text('線上')
  tap_id('battle_create')
  if SERVER:
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
  allow = ('com.android.permissioncontroller:id/permission_allow_button',
           'com.android.permissioncontroller:id/permission_allow_foreground_only_button')
  n = wait_for(lambda ns: find(ns, rid='battle_start') or next((x for x in ns if x.rid in allow), None), 30)
  if n and n.rid in allow:
    tap_node(n)
  if not wait_for(lambda ns: find(ns, rid='battle_start'), 30):
    raise RuntimeError('not found: battle_start')
  tap_id('battle_start')
  wait_for(lambda ns: len(hand_tiles(ns)) >= 16, 40)
  turns = 0
  end = time.time() + 120
  while time.time() < end:
    nodes = ui()
    p = find(nodes, rid='claim_pass')
    if p:
      tap_node(p)
      time.sleep(0.5)
      continue
    mine = hand_tiles(nodes)
    if len(mine) % 3 != 2:
      time.sleep(0.4)
      continue
    if turns >= 6:
      # The words of the last call gone.
      time.sleep(3.0)
      shot('7-battle')
      break
    t = find(nodes, rid='hand_drawn') or (mine[-1] if mine else None)
    tap_node(t)
    time.sleep(0.25)
    tap_node(t)
    turns += 1
    end_wait = time.time() + 10
    while time.time() < end_wait and len(hand_tiles(ui())) % 3 == 2:
      time.sleep(0.2)
  else:
    raise RuntimeError('the battle did not come to the player six times')
  back()
  n = wait_for(lambda ns: [x for x in ns if x.text == '離開'], 10)
  if n:
    tap_node(n[-1])
  time.sleep(1.0)


def store_rules():
  """The rules explained (from the settings): its page of ready hands and their waits, a little up so that its last
  row clears the buttons."""
  go('設定')
  # (Further down in English, or with a large font: scrolled to.)
  scroll_to('settings_guide')
  tap_id('settings_guide')
  wait_for(lambda ns: find(ns, rid='guide_next'), 10)
  for _ in range(4):
    tap_id('guide_next')
    time.sleep(0.5)
  time.sleep(0.8)
  if not nudge('單吊：等一張湊成對。', 822):
    log('the page of waits did not come where it should')
  shot('8-rules')
  back()


def main():
  log('device', adb('get-serialno').strip())
  if STORE:
    store()
    log('done')
    return
  sh(f'am force-stop {PKG}')
  sh(f'pm clear {PKG}')
  # The app's language (pm clear forgets it): APP_LOCALE's, or the system's.
  sh(f"cmd locale set-app-locales {PKG} --locales '{LOCALE}'")
  # pm clear kills the app on its own time: a start right after can lose that race and leave the splash screen up
  # with no process behind it. So wait, and start again until the app shows its navigation.
  time.sleep(2)
  for _ in range(3):
    sh(f'am start -W -n {PKG}/io.github.kennyfs.mahjong.MainActivity')
    if wait_for(lambda ns: find(ns, rid='nav_PLAY') or find(ns, text='練習') or find(ns, rid='welcome_skip'), 20):
      break
    log('the app did not show; starting it again')
    sh(f'am force-stop {PKG}')
    time.sleep(2)
  else:
    raise RuntimeError('the app did not start')
  # The first start's welcome card: shown once, then put away. It comes a moment after the navigation on a slow
  # machine, so it is waited for (it was missed, and then covered every step after).
  if wait_for(lambda ns: find(ns, rid='welcome_skip'), 8):
    shot('welcome')
    tap_id('welcome_skip')
    time.sleep(0.6)
  steps = [practice, practice_result, analyze, records, settings, manual, guide, tour, battle, battle_table]
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
