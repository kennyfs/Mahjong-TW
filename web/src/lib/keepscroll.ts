// Where a list was scrolled to, kept while its screen is away: back from a hand's review to the records, or from a
// page of help to the settings, the list is where it was (else it is hard to tell which hand was just opened). By key,
// as the app's SaveableStateHolder keeps each screen's own (MainActivity).
const kept = new Map<string, number>();
const opened = new Map<string, number>();

/** `opens`: the elements that open another screen (a selector), of which the one tapped is put back where it was, found
 *  again by `mark` (the records' hand just opened): what is above it may have changed meanwhile (the statistics grow
 *  once a hand is reviewed), which would move the rows for the same scroll. Once: a later return keeps the scroll. */
export type Keep = string | { key: string; opens: string; mark: string };

/** A Svelte action on the scrolling element: `use:keepScroll={'records:' + tab}`. A new key has its own place. */
export function keepScroll(node: HTMLElement, keep: Keep) {
  let o = typeof keep === 'string' ? { key: keep, opens: '', mark: '' } : keep;
  const save = () => kept.set(o.key, node.scrollTop);
  const top = (el: Element) => el.getBoundingClientRect().top - node.getBoundingClientRect().top;
  const tap = (e: Event) => {
    const el = o.opens ? (e.target as Element).closest?.(o.opens) : null;
    if (el && node.contains(el)) opened.set(o.key, top(el));
  };
  const place = (last: boolean) => {
    const y = opened.get(o.key);
    const el = y === undefined || !o.mark ? null : node.querySelector(o.mark);
    if (el) node.scrollTop += top(el) - y!;
    if (last) opened.delete(o.key);
  };
  // After the content is laid out; once more a frame later (a list that fills in a moment later).
  const restore = (last: boolean) => {
    node.scrollTop = kept.get(o.key) ?? 0;
    place(last);
  };
  node.addEventListener('scroll', save, { passive: true });
  node.addEventListener('click', tap, true);
  requestAnimationFrame(() => { restore(false); requestAnimationFrame(() => restore(true)); });
  return {
    update(next: Keep) {
      const n = typeof next === 'string' ? { key: next, opens: '', mark: '' } : next;
      if (n.key === o.key) { o = n; return; }
      o = n;
      restore(true);
    },
    destroy() { node.removeEventListener('scroll', save); node.removeEventListener('click', tap, true); },
  };
}

/** A Svelte action on a box of fixed height that scrolls: the class `more` while there is more of it below, for a fade
 *  at its foot (its words change with the step, so it watches them too). */
export function moreBelow(node: HTMLElement) {
  const check = () => node.classList.toggle('more', node.scrollHeight - node.scrollTop - node.clientHeight > 2);
  const mo = new MutationObserver(check);
  mo.observe(node, { childList: true, subtree: true, characterData: true });
  const ro = new ResizeObserver(check);
  ro.observe(node);
  node.addEventListener('scroll', check, { passive: true });
  check();
  return { destroy() { mo.disconnect(); ro.disconnect(); node.removeEventListener('scroll', check); } };
}
