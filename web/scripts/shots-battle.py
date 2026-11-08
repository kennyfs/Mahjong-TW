"""Battles end to end, with their screenshots: two people in headless Firefox (a phone, and a computer) play a table
game of one hand, then a same-tiles contest of two hands, on the battle server run on this machine (reached at the
page's /ws through Vite's proxy, BATTLE_SERVER). One of them reloads the page in the middle of the contest: the seat
must be taken back.

  python scripts/shots-battle.py URL OUT_DIR    e.g. http://127.0.0.1:5180/ $WORK/shots/web

Run the server with a short pace for it (docs/battle.md):
  CLAIM_WINDOW_MS=400 BOT_TURN_MS=150 PORT=8091 MAHJONG_BINARY=cpp/build/mahjong server/build/install/mahjong-server/bin/mahjong-server

The people's decisions here are the script's (a win when there is one, a pass on others' discards, else the discard
with the best tile efficiency), made through the page's battle.act() on what the room showed.
"""
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

from shots import SIZES

DECIDE = """async () => {
  const { battle, engine } = window.mahjong;
  const tb = battle.table;
  if (!tb || !tb.view.my_turn || tb.view.result) return 'wait';
  const legal = tb.view.legal;
  const win = legal.find((a) => a.type === 'tsumo' || a.type === 'ron');
  if (win) { battle.act(win); return 'win'; }
  const discards = legal.filter((a) => a.type === 'discard');
  if (!discards.length) { battle.act(legal.find((a) => a.type === 'pass') ?? legal[0]); return 'pass'; }
  const an = await engine.analyzePosition(tb.view.rules, tb.view.position);
  const key = (f) => [f.shanten, -f.ukeire];
  let best = null;
  for (const f of an.actions) {
    if (f.action.type !== 'discard') continue;
    if (!best || key(f)[0] < key(best)[0] || (key(f)[0] === key(best)[0] && key(f)[1] < key(best)[1])) best = f;
  }
  const a = best ? discards.find((d) => d.tile === best.action.tile) ?? discards[0] : discards[0];
  if (battle.table !== tb) return 'moved';
  battle.act(a);
  return 'discard';
}"""

STATE = """() => {
  const { battle, records } = window.mahjong;
  const r = battle.room;
  return {
    phase: r ? r.phase : null, hand: r ? r.hand : -1, pin: r ? r.pin : null, over: battle.over,
    result: !!(battle.table && battle.table.view.result), turn: !!(battle.table && battle.table.view.my_turn),
    discards: battle.table ? battle.table.view.legal.filter((a) => a.type === 'discard').length : 0,
    events: battle.table ? battle.table.view.events.length : 0, count: battle.table ? battle.table.view.event_count : 0,
    hands: records.hands.filter((h) => h.kind !== 'practice').length, battles: records.battles.length,
    reconnecting: battle.reconnecting,
  };
}"""


def main():
    url, out = sys.argv[1], Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    base = url + ('&' if '?' in url else '?') + 'test'

    with sync_playwright() as p:
        browser = p.firefox.launch()

        def person(size):
            w, h, scale, dark, mobile = SIZES[size]
            ctx = browser.new_context(viewport={'width': w, 'height': h}, device_scale_factor=scale, locale='zh-TW',
                                      has_touch=mobile, color_scheme='dark' if dark else 'light')
            page = ctx.new_page()
            logs = []
            page.on('console', lambda m: logs.append(f'{m.type}: {m.text}') if m.type in ('error', 'warning') else None)
            page.on('pageerror', lambda e: logs.append(f'pageerror: {e}'))
            page.logs = logs
            return page

        def shot(page, name, size=None):
            if size:
                w, h = SIZES[size][:2]
                page.set_viewport_size({'width': w, 'height': h})
            page.wait_for_timeout(500)
            page.screenshot(path=str(out / f'{name}.png'))
            print('shot', name, flush=True)

        def state(page):
            return page.evaluate(STATE)

        def play(pages, until, shots=None, timeout=600):
            """Both decide until `until(states)`; `shots`: {(page index, own decisions made): callback}."""
            made = [0] * len(pages)
            shots = dict(shots or {})
            end = time.time() + timeout
            while time.time() < end:
                states = [state(pg) for pg in pages]
                if until(states):
                    return states
                for i, pg in enumerate(pages):
                    s = states[i]
                    if s['turn'] and not s['result'] and s['discards']:
                        cb = shots.pop((i, made[i]), None)
                        if cb:
                            cb()
                    r = pg.evaluate(DECIDE)
                    if r in ('win', 'pass', 'discard'):
                        made[i] += 1
                time.sleep(0.12)
            raise SystemExit(f'timed out: {[state(pg) for pg in pages]}')

        a = person('phone')
        b = person('fhd')
        # The entry, and a table with two people.
        a.goto(base + '#/battle')
        a.wait_for_selector('.entry', timeout=20000)
        a.fill('.field input', '阿明')
        shot(a, 'battle-entry-phone')
        b.goto(base + '#/battle')
        b.wait_for_selector('.entry', timeout=20000)
        shot(b, 'battle-entry-fhd')
        a.click('button.tile >> nth=0')
        a.wait_for_selector('.pin-big', timeout=20000)
        pin = state(a)['pin']
        print('room', pin, flush=True)
        # The other joins by the room's link.
        b.goto(base + f'#/battle/{pin}')
        b.reload()
        b.wait_for_selector('.entry', timeout=20000)
        b.fill('.field input', '小華')
        b.click('form.pin-row button[type=submit]')
        b.wait_for_selector('.lobby', timeout=20000)
        # The host plays one hand.
        a.click('.setting [role=radio] >> text=一局')
        a.wait_for_timeout(400)
        shot(a, 'battle-lobby-phone')
        shot(b, 'battle-lobby-fhd')
        a.click('button.start')
        a.wait_for_selector('.table', timeout=20000)
        b.wait_for_selector('.table', timeout=20000)

        def mid_a():
            shot(a, 'battle-table-phone')
            shot(a, 'battle-table-phone-land', 'phone-land')
            a.set_viewport_size({'width': SIZES['phone'][0], 'height': SIZES['phone'][1]})

        def mid_b():
            shot(b, 'battle-table-fhd')
            shot(b, 'battle-table-laptop', 'laptop')
            b.set_viewport_size({'width': SIZES['fhd'][0], 'height': SIZES['fhd'][1]})

        states = play([a, b], lambda ss: all(s['result'] for s in ss), {(0, 4): mid_a, (1, 5): mid_b})
        print('table over', states, flush=True)
        a.wait_for_timeout(800)
        shot(a, 'battle-result-phone')
        shot(b, 'battle-result-fhd')
        a.click('.sheet button.filled')
        a.wait_for_selector('.summary', timeout=20000)
        shot(a, 'battle-over-phone')
        b.click('.sheet button.filled')
        b.wait_for_selector('.summary', timeout=20000)
        shot(b, 'battle-over-fhd')
        # A hand of it, reviewed (its decisions open to analysis).
        a.click('.hand-line >> nth=0')
        a.wait_for_selector('.controls', timeout=20000)
        shot(a, 'battle-review-phone')
        a.go_back()
        a.wait_for_selector('.summary', timeout=20000)
        a.click('.page header .btn.filled')
        b.click('.page header .btn.filled')
        a.wait_for_selector('.entry', timeout=20000)
        b.wait_for_selector('.entry', timeout=20000)

        # A contest of two hands; the computer's player reloads in the middle of the first.
        a.click('button.tile >> nth=1')
        a.wait_for_selector('.pin-big', timeout=20000)
        pin = state(a)['pin']
        b.fill('form.pin-row input', pin)
        b.click('form.pin-row button[type=submit]')
        b.wait_for_selector('.lobby', timeout=20000)
        a.click('.setting [role=radio] >> text="2"')
        a.wait_for_timeout(400)
        shot(a, 'contest-lobby-phone-land', 'phone-land')
        a.click('button.start')
        a.wait_for_selector('.table', timeout=20000)
        b.wait_for_selector('.table', timeout=20000)

        def reload_b():
            b.reload()
            b.wait_for_selector('.table', timeout=20000)
            b.wait_for_timeout(1500)
            s = state(b)
            print('after reload', s, flush=True)
            assert s['phase'] == 'PLAYING' and s['events'] == s['count'], s
            shot(b, 'contest-table-laptop', 'laptop')

        states = play([a, b], lambda ss: all(s['phase'] == 'REVIEW' for s in ss), {(1, 3): reload_b})
        print('contest hand 1 over', states, flush=True)
        a.wait_for_timeout(800)
        shot(a, 'contest-review-phone-land')
        shot(b, 'contest-review-laptop')
        a.click('.sheet button.filled')
        b.click('.sheet button.filled')
        states = play([a, b], lambda ss: all(s['phase'] == 'OVER' and s['result'] for s in ss))
        print('contest over', states, flush=True)
        a.click('.sheet button.filled')
        b.click('.sheet button.filled')
        a.wait_for_selector('.summary', timeout=20000)
        b.wait_for_selector('.summary', timeout=20000)
        shot(a, 'contest-over-phone', 'phone')
        shot(b, 'contest-over-laptop')
        final = [state(pg) for pg in (a, b)]
        print('records', [(s['hands'], s['battles']) for s in final], flush=True)
        assert all(s['hands'] == 3 and s['battles'] == 2 for s in final), final
        for name, pg in (('phone', a), ('computer', b)):
            if pg.logs:
                print(f'{name} logs:', *pg.logs[:20], sep='\n  ')
        browser.close()


if __name__ == '__main__':
    main()
