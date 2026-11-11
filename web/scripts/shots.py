"""Screenshots of the page in headless Firefox (playwright): a phone held sideways and upright at the screenshot
standard (a Pixel 10: 2424 x 1080 px at 420 dpi, so 923 x 411 CSS px at 2.625), and computers' windows.

  python scripts/shots.py URL OUT_DIR [ONLY]   e.g. http://localhost:5180/ $WORK/shots/web phone-land,fhd

The page is opened with ?test, which exposes window.mahjong = {practice, engine, settings, nav, shared}: a game is
dealt from a fixed seed and played (the player's turns by the greedy bot) to the moments shown.
"""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright


# Name: (width, height, scale, dark, mobile).
SIZES = {
    'phone-land': (923, 411, 2.625, False, True),
    'phone': (411, 840, 2.625, False, True),
    'phone-land-dark': (923, 411, 2.625, True, True),
    'small-land': (740, 360, 2, False, True),
    'tablet-land': (1180, 800, 2, False, True),
    'fhd': (1920, 1080, 1, False, False),
    'laptop': (1366, 768, 1, False, False),
}

SEEDED = """(() => {
  let s = 12345;
  Math.random = () => { s = (s * 1103515245 + 12345) % 2147483648; return s / 2147483648; };
})();"""

# Plays the player's turns with the greedy bot until `until` (a number of the player's decisions, or the hand's end).
PLAY = """async ([decisions, fast]) => {
  const { practice, engine, settings } = window.mahjong;
  settings.speed = fast ? 'fast' : 'normal';
  let made = 0;
  for (let i = 0; i < 4000; i++) {
    if (practice.stage !== 'playing') return 'over';
    const v = practice.view;
    if (v && v.my_turn && !practice.busy) {
      if (made >= decisions) return 'turn';
      const a = await engine.bot(practice.game, 0, 'greedy');
      await practice.act(a);
      made++;
    }
    await new Promise((r) => setTimeout(r, 30));
  }
  return 'timeout';
}"""

NEW = """async (seed) => { const { practice, settings } = window.mahjong; settings.speed = 'fast'; await practice.newGame(seed); }"""



if __name__ == '__main__':
    url, out = sys.argv[1], Path(sys.argv[2])
    only = sys.argv[3].split(',') if len(sys.argv) > 3 else None
    out.mkdir(parents=True, exist_ok=True)

    def shot(page, name):
        page.wait_for_timeout(450)
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
            page.wait_for_selector('.table', timeout=20000)
            page.evaluate(NEW, 'shots-1')
            page.evaluate(PLAY, [0, True])
            shot(page, f'{name}-1-start')
            page.evaluate(PLAY, [9, True])
            shot(page, f'{name}-2-mid')
            r = page.evaluate(PLAY, [999, True])
            # The hand's end: the table as it ended for a moment (END_PAUSE), then the result.
            shot(page, f'{name}-3-end')
            page.wait_for_timeout(2400)
            shot(page, f'{name}-4-result')
            (out / f'{name}-console.txt').write_text('\n'.join(logs))
            ctx.close()
        browser.close()
