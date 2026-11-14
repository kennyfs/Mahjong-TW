// A decision's list beside the table follows the tile chosen in the hand (or under a computer's mouse): its row is
// kept within the middle of the list's height (BAND: from 20% to 80%), and the list goes back to its top when the mouse
// goes far from the hand (and is not over the list), so that the best choices are in sight again.

const BAND = [0.2, 0.8];

/** The nearest ancestor of `el` that scrolls vertically. */
function scroller(el: HTMLElement): HTMLElement | null {
  for (let p = el.parentElement; p; p = p.parentElement) {
    const o = getComputedStyle(p).overflowY;
    if ((o === 'auto' || o === 'scroll') && p.scrollHeight > p.clientHeight) return p;
  }
  return null;
}

/** Scrolls `row`'s list so that the row lies within the band (its top at the band's top if it is taller). */
export function keepInBand(row: HTMLElement) {
  const box = scroller(row);
  if (!box) return;
  const c = box.getBoundingClientRect();
  const r = row.getBoundingClientRect();
  const top = c.top + c.height * BAND[0];
  const bottom = c.top + c.height * BAND[1];
  let by = 0;
  if (r.top < top || r.height > bottom - top) by = r.top - top;
  else if (r.bottom > bottom) by = r.bottom - bottom;
  if (Math.abs(by) > 1) box.scrollBy({ top: by, behavior: 'smooth' });
}

/**
 * While it runs: a mouse more than `far` px from the hand (`hand()`), not over `panel`, scrolls the panel to its top,
 * once till the mouse comes back near the hand. Returns its end.
 */
export function topWhenAway(panel: HTMLElement, hand: () => HTMLElement | null | undefined, far = 96): () => void {
  let away = false;
  const move = (e: PointerEvent) => {
    if (e.pointerType !== 'mouse') return;
    const h = hand()?.getBoundingClientRect();
    if (!h) return;
    const dx = Math.max(h.left - e.clientX, 0, e.clientX - h.right);
    const dy = Math.max(h.top - e.clientY, 0, e.clientY - h.bottom);
    const near = Math.hypot(dx, dy) <= far;
    if (near) away = false;
    else if (!away && !panel.contains(e.target as Node)) {
      away = true;
      panel.scrollTo({ top: 0, behavior: 'smooth' });
    }
  };
  document.addEventListener('pointermove', move, { passive: true });
  return () => document.removeEventListener('pointermove', move);
}
