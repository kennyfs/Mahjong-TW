// Whether a call's or a win's moment (CallFx.svelte, started by Table.svelte) is on the screen: a hand's result waits
// for it to end before it shows (Play, Battle, Replay, Branch), so that the two never cover each other.
export const fxOn = $state({ on: false });
