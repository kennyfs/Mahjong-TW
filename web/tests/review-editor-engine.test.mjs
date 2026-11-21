// Review, Seats, PositionEditor, Rules, Engine worker recovery, and i18n tests.
// Run with Node from the repo root: node web/tests/review-editor-engine.test.mjs
import assert from 'node:assert/strict';
import { resolve } from 'node:path';

const WEB = resolve('repo/web');
globalThis.document = { baseURI: 'http://localhost/', documentElement: { lang: '' }, title: '', hidden: false, addEventListener() {} };
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { host: 'localhost:5173', protocol: 'http:', hash: '', search: '' };
Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-TW'], language: 'zh-TW' }, configurable: true });
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };

let workerCount = 0;
let mode = 'crash-first';
class RecoverableFakeWorker {
  onmessage = null;
  onerror = null;
  terminated = false;
  constructor() { this.idx = ++workerCount; }
  postMessage(msg) {
    if (mode === 'crash-first' && this.idx === 1) {
      setTimeout(() => this.onerror?.({ message: 'worker crashed' }), 0);
      return;
    }
    if (mode === 'bad-json') {
      setTimeout(() => this.onmessage?.({ data: { id: msg.id, line: 'not-json' } }), 0);
      return;
    }
    setTimeout(() => this.onmessage?.({ data: { id: msg.id, line: JSON.stringify({ spec: 'simple1,none', values: {}, tai_presets: [], flower_presets: [], items: [] }) } }), 0);
  }
  terminate() { this.terminated = true; }
}
globalThis.Worker = RecoverableFakeWorker;

const { createServer } = await import(`${WEB}/node_modules/vite/dist/node/index.js`);
const server = await createServer({ root: WEB, logLevel: 'error', server: { middlewareMode: true, hmr: false }, appType: 'custom' });
try {
  const review = await server.ssrLoadModule('/src/lib/review.ts');
  const seats = await server.ssrLoadModule('/src/lib/seats.ts');
  const { editor } = await server.ssrLoadModule('/src/lib/position-editor.svelte.ts');
  const rules = await server.ssrLoadModule('/src/lib/rules.ts');
  const engine = await server.ssrLoadModule('/src/lib/engine.ts');

  // 1. review.ts edge cases matching Android Review.kt
  assert.equal(review.reasonOf({ source: 'network', best: 0, actions: [{ action: { type: 'pass' }, shanten: 1, ukeire: 0, waits: [], win_tai: 0, parts: [0, 0, 0] }] }, -1), '');
  assert.equal(review.opponentNote({ seat: 1, ready: [], danger: [], danger_tai: [] }), null);
  const figItems = review.figureItems({ action: { type: 'discard', tile: '1m' }, shanten: 1, ukeire: 4, waits: [], win_tai: 0, q: 0, danger: 0.05, danger_each: [] }, null, ['A', 'B', 'C']);
  const dangerRow = figItems.find(([k]) => k === '當下危險');
  assert.equal(dangerRow?.[1], '5.0%', 'empty danger_each must not render undefined NaN%');
  const figCells = review.figureCells({ action: { type: 'discard', tile: '1m' }, shanten: 1, ukeire: 4, waits: [], win_tai: 0, q: 0, danger: 0.05, danger_each: [] }, null, ['A', 'B', 'C'], null);
  assert.equal(figCells.find((c) => c.key === 'who')?.value, '');

  // 2. seats.ts meldTiles parity with Tiles.kt
  assert.deepEqual(seats.meldTiles({ type: 'pon', tile: '5z' }), ['5z', '5z', '5z']);
  assert.deepEqual(seats.meldTiles({ type: 'chi', tile: '2m' }), ['2m', '3m', '4m']);
  assert.deepEqual(seats.meldTiles({ type: 'kan', tile: '1p' }), ['1p', '1p', '1p', '1p']);
  assert.deepEqual(seats.meldTiles({ type: 'ankan', tile: null }), [null, null, null, null]);

  // 3. PositionEditor.load on rob_kong and missing drawn
  editor.load({
    setup: { dealer: 0, east: 0, round: 0, streak: 0 },
    me: 0, hand: '123456789m123456p', drawn: null, after_call: false,
    claim: { seat: 1, tile: '5z', rob_kong: true },
    players: [
      { melds: [], flowers: '', discards: [] },
      { melds: [{ type: 'kakan', tile: '5z', tiles: ['5z', '5z', '5z', '5z'] }], flowers: '', discards: [{ tile: '1s', drawn: false, called: false }] },
      { melds: [], flowers: '', discards: [] },
      { melds: [], flowers: '', discards: [] },
    ],
    wall_left: 40,
  }, 'simple1,none');
  assert.deepEqual(editor.discards[1], ['1s'], 'rob_kong must not drop an unrelated discard');

  editor.load({
    setup: { dealer: 0, east: 0, round: 0, streak: 0 },
    me: 0, hand: '123m', drawn: '9s', after_call: false, claim: null,
    players: Array.from({ length: 4 }, () => ({ melds: [], flowers: '', discards: [] })),
    wall_left: 40,
  }, 'simple1,none');
  assert.deepEqual(editor.hand, ['1m', '2m', '3m'], 'missing drawn must not splice last tile from hand');

  // 4. rules.parseChoice filters non-numeric/array overrides
  assert.deepEqual(rules.parseChoice({ tai: 'simple1', flowers: 'none', overrides: { base: 3, bad: 'x', nan: NaN } }).overrides, { base: 3 });
  assert.deepEqual(rules.parseChoice({ tai: 'simple1', flowers: 'none', overrides: [1, 2] }).overrides, {});

  // 5. engine.ts recovers by spawning a fresh worker after worker.onerror, and wraps bad JSON as EngineError
  await assert.rejects(() => engine.rulesInfo('simple1,none'), (e) => e instanceof engine.EngineError);
  const ok = await engine.rulesInfo('simple1,none');
  assert.equal(ok.spec, 'simple1,none');
  assert.equal(workerCount, 2, 'engine must respawn a fresh worker after onerror');

  mode = 'bad-json';
  await assert.rejects(() => engine.rulesInfo('simple1,none'), (e) => e instanceof engine.EngineError);

  console.log('review-editor-engine.test.mjs: all passed');
} finally {
  await server.close();
}
