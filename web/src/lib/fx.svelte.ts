// Whether a call's or a win's moment (CallFx.svelte, started by Table.svelte) is on the screen: a hand's result waits
// for it to end before it shows (Play, Battle, Replay, Branch), so that the two never cover each other, and the hold of
// the table as it ended (ui.svelte.ts END_AFTER_FX) counts from its end.
export const fxOn = $state({ on: false });

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
