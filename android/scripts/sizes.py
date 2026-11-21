#!/usr/bin/env python3
"""The practice table at the sizes phones are set to, checked: device-tests.sh SIZES=1 runs it on its own AVD (a Galaxy
A53's screen: 1080 x 2400 at 450 dpi). For each case, sideways and upright: a screenshot into $OUT, and the table's
parts on the screen (every item of the rail or bar of screens, the player's tiles, each opponent's plate with its
tile count, no two plates over each other). Exits 1 if a case fails; the failures are printed.

The cases: the phone as it comes; a large font (1.3, and Android's largest, 2); a larger display size; both; and a
display size the phone also reports as its own stable density, as a Galaxy A53 did (2026-11-21: its table drawn at
about 600 dpi, 288 dp high sideways). The last one sets qemu.sf.lcd_density (root, a restart of the system's
processes) and puts it back after. The app's table keeps its own size (Theme.kt LocalTableDensity); these check that it
fits whatever the phone says.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shots as S  # noqa: E402
from shots import adb, sh, log, ui, find, tap_id, tap_node, wait_for, shot  # noqa: E402

PKG = S.PKG
BASE = int(os.environ.get('SIZES_DENSITY', '450'))
CASES = [  # (name, display density, font scale, stable density or None for the AVD's own)
  ('as-it-comes', BASE, 1.0, None),
  ('font-1.3', BASE, 1.3, None),
  ('font-2', BASE, 2.0, None),
  ('display-larger', round(BASE * 1.2), 1.0, None),
  ('display-larger-font-2', round(BASE * 1.2), 2.0, None),
  ('stable-follows', 600, 1.0, 600),
]


def system_restart(stable):
  """The system's processes again, with qemu.sf.lcd_density at `stable` (DisplayMetrics.DENSITY_DEVICE_STABLE is read
  once, by zygote)."""
  adb('root', check=False)
  time.sleep(3)
  sh('stop')
  sh(f'setprop qemu.sf.lcd_density {stable}')
  sh('start')
  time.sleep(5)
  adb('wait-for-device')
  deadline = time.time() + 240
  while time.time() < deadline:
    if sh('getprop sys.boot_completed').strip() == '1' and 'mCurrentFocus=Window' in sh('dumpsys window'):
      break
    time.sleep(3)
  time.sleep(15)
  sh('settings put global hide_error_dialogs 1')


def table(land):
  """Practice at the player's turn, sideways or upright (a claim offered first is passed). The app first: the
  launcher, upright only, would turn the display back."""
  sh(f'am start -S -W -n {PKG}/io.github.kennyfs.mahjong.MainActivity')
  time.sleep(2)
  if wait_for(lambda ns: find(ns, rid='welcome_skip'), 6):
    tap_id('welcome_skip')
  S.rotate(land)
  deadline = time.time() + 60
  while time.time() < deadline:
    ns = ui()
    p = find(ns, rid='claim_pass')
    if p:
      tap_node(p)
      time.sleep(0.6)
      continue
    if find(ns, rid='hand_drawn'):
      time.sleep(1.5)
      return ui()
    time.sleep(1)
  raise RuntimeError('no turn of the player')


def check(ns, w, h):
  bad = []
  inside = lambda b: b[0] >= 0 and b[1] >= 0 and b[2] <= w and b[3] <= h  # noqa: E731
  navs = [n for n in ns if n.rid.startswith('nav_')]
  if len(navs) != 5:
    bad.append(f'{len(navs)} items of the screens\' rail or bar')
  bad += [f'{n.rid} off the screen {n.box}' for n in navs if not inside(n.box)]
  tiles = [n for n in ns if n.rid.startswith('hand_')]
  if len(tiles) < 14:
    bad.append(f'{len(tiles)} of the player\'s tiles')
  bad += [f'{n.rid} off the screen {n.box}' for n in tiles if not inside(n.box)]
  names = [n for n in ns if re.fullmatch(r'電腦 \d', n.text)]
  counts = [n for n in ns if re.fullmatch(r'\d+ 張', n.text)]
  if len(names) != 3 or len(counts) != 3:
    bad.append(f'{len(names)} opponents\' names and {len(counts)} tile counts (3 each)')
  bad += [f'{n.text} off the screen {n.box}' for n in names + counts if not inside(n.box)]
  for i, a in enumerate(names):
    for b in names[i + 1:]:
      if a.box[0] < b.box[2] and b.box[0] < a.box[2] and a.box[1] < b.box[3] and b.box[1] < a.box[3]:
        bad.append(f'{a.text} over {b.text}')
  return bad


def main():
  sh('settings put global hide_error_dialogs 1')
  sh(f'pm clear {PKG}')
  sh(f'cmd locale set-app-locales {PKG} --locales zh-TW')
  failed = []
  stable_now = None
  for name, density, font, stable in CASES:
    if stable != stable_now:
      system_restart(stable or BASE)
      stable_now = stable
    sh(f'wm density {density}')
    sh(f'settings put system font_scale {font}')
    for land in (True, False):
      ns = table(land)
      w, h = S.screen_size()
      if land:
        w, h = max(w, h), min(w, h)
      else:
        w, h = min(w, h), max(w, h)
      tag = f'{name}-{"land" if land else "port"}'
      shot(tag)
      bad = check(ns, w, h)
      log(tag, 'ok' if not bad else 'FAILED: ' + '; '.join(bad))
      if bad:
        failed.append(tag)
  if stable_now is not None:
    system_restart(BASE)
  sh('wm density reset')
  sh('settings put system font_scale 1.0')
  if failed:
    log('failed:', ', '.join(failed))
    sys.exit(1)
  log('all sizes fit')


if __name__ == '__main__':
  main()
