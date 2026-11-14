/**
 * Words that shorten before they are cut: an attachment for a row (`{@attach shorten}`). The row's elements with
 * `data-full` and `data-short` show their full words where the row has room for all of them; where it has not, the
 * first such element (in the page's order) shows its short words, then the next, till the row fits (an inline element
 * that wraps does not fit either). A short form that still does not fit is cut or wraps as the element's style says.
 * Such an element's words are this attachment's: leave it empty in the markup. An element marked `data-keep` (a page's
 * title) is words not to cut: while it is cut, the others shorten. The app's Shorten (ui/Shorten.kt).
 *
 * The row is checked again when its size changes (the window) and when anything in it changes (its words, the
 * rules' chip, a battle's clock).
 */
export function shorten(row: HTMLElement) {
  // Words too wide for an element or anything round them in the row (an inline element has no width of its own, but
  // a box for each line it takes).
  const over = (e: HTMLElement) => {
    if (e.getClientRects().length > 1) return true;
    for (let a: HTMLElement | null = e; a && a !== row.parentElement; a = a.parentElement) {
      if (a.clientWidth > 0 && a.scrollWidth > a.clientWidth + 0.5) return true;
    }
    return false;
  };
  const put = (e: HTMLElement, text: string, title?: string) => {
    if (e.textContent !== text) e.textContent = text;
    if (title) e.title = title;
    else e.removeAttribute('title');
  };
  const check = () => {
    const items = [...row.querySelectorAll<HTMLElement>('[data-short]')];
    if (!items.length) return;
    const kept = [...items, ...row.querySelectorAll<HTMLElement>('[data-keep]')];
    for (const e of items) put(e, e.dataset.full ?? '');
    for (const e of items) {
      if (!kept.some(over)) break;
      if (e.dataset.short !== e.dataset.full) put(e, e.dataset.short ?? '', e.dataset.full);
    }
    // Its own changes are not changes to answer.
    mo.takeRecords();
  };
  const ro = new ResizeObserver(check);
  const mo = new MutationObserver(check);
  ro.observe(row);
  mo.observe(row, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ['data-full', 'data-short'] });
  check();
  return () => {
    ro.disconnect();
    mo.disconnect();
  };
}
