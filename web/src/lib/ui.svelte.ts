// What the screens share about the window: its room, whether it is wide (a rail at the side) or tall (a bar under),
// and whether it is a computer's (`desk`: a mouse, and room for a side panel).
export const NAV_BAR = 80;
export const NAV_RAIL = 88;
/** A hand's end: how long the table shows how it ended (the hands, the words of the win) before the result, ms. */
export const END_PAUSE = 2200;
/** The same after a moment of the win (CallFx.svelte), counted from its end: the moment already showed the hand. */
export const END_AFTER_FX = 1200;

export const ui = $state({ w: 0, h: 0, wide: false, desk: false, showKeys: false });

export function measure() {
  const shell = document.querySelector('.shell');
  const pad = shell ? getComputedStyle(shell) : null;
  const px = (v: string | undefined) => parseFloat(v ?? '') || 0;
  const W = window.innerWidth - px(pad?.paddingLeft) - px(pad?.paddingRight);
  const H = window.innerHeight - px(pad?.paddingTop) - px(pad?.paddingBottom);
  ui.wide = W > H;
  ui.w = W;
  ui.h = H;
  // A computer's own pages: a mouse (or a touchpad) that hovers, and a window of 1024 x 600 or more.
  ui.desk = matchMedia('(hover: hover) and (pointer: fine)').matches && ui.wide && W >= 1024 && H >= 600;
}

/** "+3", "−1.5": no decimals for whole numbers. */
export function signed(x: number): string {
  const a = Math.abs(x);
  return (x > 0 ? '+' : x < 0 ? '−' : '±') + (Number.isInteger(a) ? a.toFixed(0) : a.toFixed(1));
}

/** A record's time (ISO 8601) here: "2026-11-06 13:00". */
export function localTime(stamp: string): string {
  const d = new Date(stamp);
  if (Number.isNaN(d.getTime())) return stamp.slice(0, 16).replace('T', ' ');
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
