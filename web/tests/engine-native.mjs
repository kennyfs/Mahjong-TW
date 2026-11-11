// Checks the WebAssembly engine against the native one: the same requests, the same answers (the network's figures
// to 0.002: the two builds of ONNX Runtime round differently).
// node tests/engine-native.mjs ENGINE_JS NATIVE_MAHJONG, or npm test -- NATIVE_MAHJONG (the built engine).
// The network is actor.onnx beside ENGINE_JS; the native engine needs ONNX Runtime for the requests that use it.
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const [enginePath, native] = process.argv.slice(2);
if (!enginePath || !native) {
  console.log('usage: node tests/engine-native.mjs ENGINE_JS NATIVE_MAHJONG');
  process.exit(2);
}
const mod = await (await import(pathToFileURL(enginePath).href)).default();
// The network: in the WebAssembly engine's file system under the name the native engine reads it by.
const model = resolve(join(dirname(enginePath), 'actor.onnx'));
mod.FS.mkdirTree(dirname(model));
mod.FS.writeFile(model, readFileSync(model));
function wasm(req) {
  const p = mod.stringToNewUTF8(JSON.stringify(req));
  const out = mod._engine_handle(p);
  mod._free(p);
  const s = mod.UTF8ToString(out);
  mod._engine_free(out);
  return JSON.parse(s);
}
function nativeAll(reqs) {
  const lines = reqs.map((r) => JSON.stringify(r)).join('\n') + '\n';
  return spawnSync(native, ['gui'], { input: lines, encoding: 'utf8' }).stdout.trim().split('\n').map((l) => JSON.parse(l));
}

// A hand dealt and played to its end by four greedy bots, then looked at, analyzed and scored.
const setup = { dealer: 0, east: -1, round: 0, streak: 0 };
const dealt = wasm({ cmd: 'new', rules: 'standard,see', setup, seed: 'test' }).game;
const played = wasm({ cmd: 'advance', game: dealt, players: ['greedy', 'greedy', 'greedy', 'greedy'], seed: 's' }).game;
const mid = { ...played, actions: played.actions.slice(0, Math.floor(played.actions.length / 2)) };
const position = {
  setup, me: 0, hand: '123456789m234s5m7z', drawn: '5m', after_call: false, claim: null,
  players: [{ melds: [{ type: 'ankan', tile: '9p' }], flowers: '', discards: [] }, { melds: [], flowers: '', discards: [] },
    { melds: [], flowers: '', discards: [] }, { melds: [], flowers: '', discards: [] }],
  wall_left: 40,
};
const reqs = [
  { cmd: 'rules', rules: 'masters,seat,base=2' },
  { cmd: 'new', rules: 'standard,see', setup, seed: 'test' },
  { cmd: 'advance', game: dealt, players: ['greedy', 'greedy', 'greedy', 'greedy'], seed: 's' },
  { cmd: 'advance', game: dealt, players: [null, 'greedy', 'greedy', 'random'], seed: 's', steps: 1 },
  { cmd: 'pending', game: mid },
  { cmd: 'view', game: mid, seat: 1, since: 0, reveal: false },
  { cmd: 'view', game: played, seat: 0, since: 0, reveal: true },
  { cmd: 'analyze', game: mid, seat: mid.actions.length ? mid.actions[mid.actions.length - 1][0] : 0 },
  { cmd: 'analyze', rules: 'simple1,none', position, seed: 'p' },
  { cmd: 'next', game: played, start_dealer: 0, seed: 'n' },
  { cmd: 'tai', rules: 'standard,none', hand: '123m456m789m123p55p', melds: [{ type: 'pon', tile: '7z' }], win: '5p', tsumo: true },
  { cmd: 'act', game: dealt, seat: 0, action: { type: 'discard', tile: 'xx' } },
  // With the network: a decision, a position, a whole hand's review, the tables of draws and claims, and bots.
  { cmd: 'model', model },
  { cmd: 'analyze', game: mid, seat: mid.actions.length ? mid.actions[mid.actions.length - 1][0] : 0, model },
  { cmd: 'analyze', rules: 'simple2,see', position, seed: 'p', model },
  { cmd: 'review', game: played, seat: 2, model },
  { cmd: 'whatif', rules: 'simple2,see', position, seed: 'p', model },
  { cmd: 'advance', game: dealt, players: [`net:model=${model}`, 'greedy', `net:model=${model},mode=policy`, 'greedy'], seed: 's' },
];
const nat = nativeAll(reqs);
let bad = 0;
let network = false;
function same(a, b, path) {
  if (typeof a === 'number' && typeof b === 'number') {
    if (Math.abs(a - b) > (network ? 2e-3 : 1e-6)) { bad++; console.log('differs', path, a, b); }
  } else if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) { bad++; console.log('differs', path, 'length'); return; }
    a.forEach((x, i) => same(x, b[i], `${path}[${i}]`));
  } else if (a && typeof a === 'object') {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b ?? {})])) same(a[k], b?.[k], `${path}.${k}`);
  } else if (a !== b) { bad++; console.log('differs', path, a, b); }
}
const t0 = performance.now();
reqs.forEach((r, i) => {
  const t = performance.now();
  network = 'model' in r;
  const w = wasm(r);
  console.log(r.cmd, `${(performance.now() - t).toFixed(0)} ms`, w.error ? `error: ${w.error}` : '');
  same(w, nat[i], r.cmd);
});
console.log(`${played.actions.length} decisions in the played hand`);
console.log(bad ? `${bad} differences` : 'all the same', `${(performance.now() - t0).toFixed(0)} ms in all`);
process.exit(bad ? 1 : 0);
