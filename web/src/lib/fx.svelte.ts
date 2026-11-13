// Whether a call's or a win's moment (CallFx.svelte, started by Table.svelte) is on the screen: a hand's result waits
// for it to end before it shows (Play, Battle, Replay, Branch), so that the two never cover each other; the table as it
// ended then waits for a tap (TapOn.svelte).

/** The system asks for less motion: the words alone, the tiles where they go at once. */
export const reduceMotion = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
/** Whether the moments and the tiles' moves (a discard's flight, the hand closing its gap) show: always, unless the
 *  system asks for less motion (then the words alone; the app's FxClock.enabled). */
export const movesShown = () => !reduceMotion;

export const fxOn = $state({ on: false });
/** The last hand whose deal was shown (Table.svelte): a table that comes back to it (another screen and back) does not
 *  deal it again. */
export const dealsShown = { last: '' };
/** A hand's result moment (a win, a draw over the whole screen) stops for the player's tap (Table.svelte): it was
 *  tapped on (or let go), so the result comes after it at once; else (words alone) the table waits for a tap
 *  (TapOn.svelte). Set again for each hand's end. */
export const resultTap = $state({ tapped: false, on: false });
// (`on`: a result's moment is up now: TapOn.svelte's words show from its start.)

let waiting: (() => void)[] = [];

/** Table.svelte: a moment came up or went. */
export function setFx(on: boolean) {
  fxOn.on = on;
  if (!on) for (const go of waiting.splice(0)) go();
}

/** Resolves when no moment is up: true when one was (it waited for its end), false when none was. A moment that the
 *  change just made starts first (the table's effects run before this looks). */
export async function fxDone(): Promise<boolean> {
  await new Promise((r) => setTimeout(r, 0));
  if (!fxOn.on) return false;
  await new Promise<void>((go) => waiting.push(go));
  return true;
}
