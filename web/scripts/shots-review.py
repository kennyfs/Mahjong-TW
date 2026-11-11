"""Screenshots of the review and the analysis screens with the network, in headless Firefox (playwright): the sizes of
shots.py. A hand is dealt from a fixed seed and played to its end (the player's turns by the greedy bot, so that there
are mistakes to show), then reviewed: the decisions worth a look, a tile's line, everyone's tiles, the list with its
rows each way, the last move, the table at the end and the result; a step is taken to the analysis screen; then the
records, the settings and the rules: their presets, every parameter, a parameter's explanation (a dialog).

  python scripts/shots-review.py URL OUT_DIR [ONLY] [RULES] [SEED]   e.g. http://127.0.0.1:5273/ $WORK/shots phone,phone-land
"""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

SIZES = {
    'phone-land': (923, 411, 2.625, False, True),
    'phone': (411, 840, 2.625, False, True),
    'phone-dark': (411, 840, 2.625, True, True),
    'laptop': (1366, 768, 1, False, False),
    'fhd': (1920, 1080, 1, False, False),
}

SEEDED = """(() => {
  let s = 12345;
  Math.random = () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; };
})();"""

# A hand of practice under `rules`, the player's turns by the greedy bot, to its end.
PLAY = """async ([seed, tai, flowers]) => {
  const { practice, engine, settings } = window.mahjong;
  settings.speed = 'fast';
  settings.length = 'hand';
  settings.level = 'expert';
  settings.rules = { tai, flowers, overrides: {} };
  await practice.newGame(seed);
  for (let i = 0; i < 6000; i++) {
    if (practice.stage !== 'playing') return practice.lastRecord;
    const v = practice.view;
    if (v && v.my_turn && !practice.busy) await practice.act(await engine.bot(practice.game, 0, 'greedy'));
    await new Promise((r) => setTimeout(r, 20));
  }
  return null;
}"""

# The step of the first decision with an option that is not a discard (null: none).
OPTION = """async (id) => {
  const { records, engine } = window.mahjong;
  const rec = records.get(id);
  const r = await engine.review(rec.game, rec.me);
  const d = r.decisions.find((x) => x.analysis.actions.some((f) => f.action.type !== 'discard'));
  return d ? d.step : null;
}"""

REVIEW = """(id) => { const { shared, nav } = window.mahjong; shared.reviewId = id; nav.open('review'); }"""
STEP = """(k) => { window.mahjong.shared.reviewStep = k; }"""
ROWS = """(s) => { window.mahjong.settings.listRows = s; }"""


if __name__ == '__main__':
    url, out = sys.argv[1], Path(sys.argv[2])
    only = sys.argv[3].split(',') if len(sys.argv) > 3 and sys.argv[3] else None
    tai, flowers = (sys.argv[4] if len(sys.argv) > 4 else 'simple2,see').split(',')
    seed = sys.argv[5] if len(sys.argv) > 5 else 'r2'
    out.mkdir(parents=True, exist_ok=True)

    def shot(page, name):
        page.wait_for_timeout(500)
        page.screenshot(path=str(out / f'{name}.png'))
        print('shot', name, flush=True)

    with sync_playwright() as p:
        browser = p.firefox.launch()
        for name, (w, h, scale, dark, mobile) in SIZES.items():
            if only and name not in only:
                continue
            ctx = browser.new_context(viewport={'width': w, 'height': h}, device_scale_factor=scale,
                                      color_scheme='dark' if dark else 'light', locale='zh-TW', has_touch=mobile)
            ctx.add_init_script(SEEDED)
            page = ctx.new_page()
            logs = []
            page.on('console', lambda m: logs.append(f'{m.type}: {m.text}'))
            page.on('pageerror', lambda e: logs.append(f'pageerror: {e}'))
            page.goto(url + ('&' if '?' in url else '?') + 'test')
            page.wait_for_selector('.table', timeout=30000)
            rec = page.evaluate(PLAY, [seed, tai, flowers])
            print(name, 'record', rec, flush=True)
            page.wait_for_timeout(1500)
            shot(page, f'{name}-0-result')
            page.evaluate(REVIEW, rec)
            page.wait_for_selector('.graph svg', timeout=30000)
            shot(page, f'{name}-1-review-start')
            nxt = page.get_by_label('下一個該看的決定').first
            panel = page.get_by_label('每個選擇')

            def show_list(on):
                # A phone shows the list in place of the table; a large window has it beside the table always.
                if panel.count() and (panel.first.get_attribute('aria-pressed') == 'true') != on:
                    panel.first.click()

            if nxt.is_enabled():
                nxt.click()
            shot(page, f'{name}-2-key1')
            # A tile's line: the drawn tile, or the last of the hand.
            slots = page.locator('.me .slot')
            if slots.count():
                slots.nth(slots.count() - 1).click()
                shot(page, f'{name}-2-picked')
            eye = page.get_by_label('顯示所有人的牌').first
            eye.click()
            shot(page, f'{name}-2-truth')
            eye.click()
            if nxt.is_enabled():
                nxt.click()
                shot(page, f'{name}-2-key2')
            # The list of the decision's actions, its rows each way.
            show_list(True)
            for rows in ('numbers', 'bars', 'both'):
                page.evaluate(ROWS, rows)
                shot(page, f'{name}-3-list-{rows}')
            show_list(False)
            # A decision with options that are not discards (a call, a win), if the hand has one.
            step = page.evaluate(OPTION, rec)
            if step is not None:
                page.evaluate(STEP, step)
                shot(page, f'{name}-2-option')
            # The end: the last move, the table at the end with everyone's tiles, the result.
            end = page.get_by_label('最後一手').first
            end.click()
            shot(page, f'{name}-4-last')
            end.click()
            page.wait_for_timeout(1800)
            shot(page, f'{name}-4-end')
            page.get_by_text('看結果').first.click()
            shot(page, f'{name}-4-result')
            page.get_by_text('各家手牌').first.click()
            # The step in the analysis screen, with the tables.
            page.get_by_label('上一個決定').first.click()
            show_list(True)
            page.get_by_text('帶到分析頁').first.click()
            page.wait_for_selector('.results', timeout=30000)
            shot(page, f'{name}-5-analysis')
            page.get_by_text('算出摸牌表與鳴牌表').first.click()
            page.wait_for_selector('.tables', timeout=30000)
            page.locator('.tables').scroll_into_view_if_needed()
            shot(page, f'{name}-5-analysis-tables')
            page.evaluate("() => window.mahjong.nav.go('RECORDS')")
            shot(page, f'{name}-6-records')
            page.evaluate("() => window.mahjong.nav.go('SETTINGS')")
            page.get_by_text('失誤門檻').first.scroll_into_view_if_needed()
            shot(page, f'{name}-7-settings')
            # The rules: every parameter, one's explanation open.
            if not page.get_by_text('所有參數').count():
                page.evaluate("() => window.mahjong.nav.open('rules')")
            page.get_by_text('所有參數').first.click()
            shot(page, f'{name}-8-rules')
            info = page.get_by_label('說明：一炮多響').first
            info.scroll_into_view_if_needed()
            shot(page, f'{name}-8-rules-all')
            info.click()
            shot(page, f'{name}-8-rules-help')
            page.keyboard.press('Escape')
            (out / f'{name}-console.txt').write_text('\n'.join(logs))
            if logs:
                print('\n'.join(logs[-12:]), flush=True)
            ctx.close()
        browser.close()
