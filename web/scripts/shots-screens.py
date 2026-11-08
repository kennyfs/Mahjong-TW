"""Screenshots of the screens other than the table: a practice hand is played to its end (the player's turns by the
greedy bot), then the records, the hand's review and a decision's analysis, the analysis screen with a position, the
settings and the rules, at the given sizes (shots.py's names).

  python scripts/shots-screens.py URL OUT_DIR [SIZES]   e.g. http://localhost:5180/ $WORK/shots/web phone,fhd
"""
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).parent))
from shots import NEW, PLAY, SEEDED, SIZES  # noqa: E402

url, out = sys.argv[1], Path(sys.argv[2])
only = sys.argv[3].split(',') if len(sys.argv) > 3 else ['phone', 'phone-land', 'fhd']
out.mkdir(parents=True, exist_ok=True)


def shot(page, name):
    page.wait_for_timeout(500)
    page.screenshot(path=str(out / f'{name}.png'))
    print('shot', name, flush=True)


with sync_playwright() as p:
    browser = p.firefox.launch()
    for name in only:
        w, h, scale, dark, mobile = SIZES[name]
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
        page.evaluate(PLAY, [999, True])
        page.wait_for_timeout(1500)
        # The review of the hand, a few steps in, and a decision's analysis.
        page.evaluate("() => { const m = window.mahjong; m.shared.reviewId = m.practice.lastRecord; m.nav.open('review'); }")
        page.wait_for_selector('.controls')
        for _ in range(14):
            page.click('.controls .btn.filled[aria-label]')
            page.wait_for_timeout(60)
        shot(page, f'{name}-review')
        page.click('.controls .analyze')
        page.wait_for_selector('.list .source', timeout=20000)
        shot(page, f'{name}-decision')
        page.evaluate("() => window.mahjong.nav.go('RECORDS')")
        page.wait_for_timeout(300)
        shot(page, f'{name}-records')
        page.evaluate("() => { const m = window.mahjong; m.editor.random(); m.nav.go('ANALYZE'); }")
        page.wait_for_timeout(300)
        page.click('.run .btn.filled')
        page.wait_for_selector('.list .source', timeout=20000)
        shot(page, f'{name}-analyze')
        page.evaluate("() => window.mahjong.nav.go('SETTINGS')")
        shot(page, f'{name}-settings')
        if name != 'fhd':
            page.evaluate("() => window.mahjong.nav.open('rules')")
            page.wait_for_timeout(300)
        page.click('.section-toggle')
        page.wait_for_timeout(300)
        shot(page, f'{name}-rules')
        (out / f'{name}-screens-console.txt').write_text('\n'.join(logs))
        ctx.close()
    browser.close()
