// The network's download (engine.ts, loadModel): it starts when a request first names the network, so while it goes
// on something waits for it (a computer player's turn, the analysis). App.svelte shows how far it has come.

/** `on` while the network is being fetched; `done` of `of` bytes (`of` 0: not known). */
export const netLoad = $state({ on: false, done: 0, of: 0 });
