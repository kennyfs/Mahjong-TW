// Practice and Claims tests: forcedDiscard on a declared ready hand (and not when another option exists).
// Run via: ./agy.sh node repo/web/tests/practice.test.mjs
import assert from 'node:assert/strict';
import { resolve } from 'node:path';

const WEB = resolve('repo/web');
globalThis.document = { baseURI: 'http://localhost/', documentElement: { lang: '' }, title: '', hidden: false, addEventListener() {} };
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { host: 'localhost:5173', protocol: 'http:', hash: '', search: '' };
Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-TW'], language: 'zh-TW' }, configurable: true });
globalThis.matchMedia = () => ({ matches: true });
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };

let scenario = { declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 0 };
let actCalls = [];

class FakeWorker {
  onmessage = null;
  onerror = null;
  postMessage(msg) {
    const req = JSON.parse(msg.line);
    const reply = (obj, delay = 0) => setTimeout(() => this.onmessage?.({ data: { id: msg.id, line: JSON.stringify(obj) } }), delay);
    if (req.cmd === 'new') {
      reply({ game: { rules: req.rules, setup: req.setup, wall: '1m'.repeat(30), actions: [] } });
    } else if (req.cmd === 'pending') {
      const n = req.game.actions.length;
      reply({ phase: n === 0 ? 'self' : 'over', current: 0, pending: n === 0 ? [0] : [], actions: n, event_count: n });
    } else if (req.cmd === 'view') {
      const n = req.game.actions.length;
      reply({
        rules: req.game.rules,
        position: {
          setup: req.game.setup, me: 0, hand: '123m', drawn: '1m', after_call: false, claim: null,
          players: [
            { melds: [], flowers: '', discards: [], ...(scenario.declared ? { declared: scenario.declared } : {}) },
            { melds: [], flowers: '', discards: [] },
            { melds: [], flowers: '', discards: [] },
            { melds: [], flowers: '', discards: [] },
          ],
          wall_left: 40,
        },
        phase: n === 0 ? 'self' : 'over', current: 0, seat_winds: [0, 1, 2, 3], my_turn: n === 0,
        legal: n === 0 ? scenario.legal : [],
        events: [], event_count: n,
        result: n > 0 ? { exhausted: true, winners: [], loser: -1, win_tile: null, self_draw: false, flower_win: false, dealer_tai: 0, points: [0, 0, 0, 0], base_delta: [0, 0, 0, 0], tai_delta: [0, 0, 0, 0], next_dealer: 0, next_streak: 0 } : null,
      });
    } else if (req.cmd === 'act') {
      actCalls.push(req.action);
      reply({ game: { ...req.game, actions: [...req.game.actions, [req.seat, req.action.type, req.action.tile]] } }, scenario.actDelayMs);
    }
  }
  terminate() {}
}
globalThis.Worker = FakeWorker;

const { createServer } = await import(`${WEB}/node_modules/vite/dist/node/index.js`);
const server = await createServer({ root: WEB, logLevel: 'error', server: { middlewareMode: true, hmr: false }, appType: 'custom' });
try {
  const { forcedDiscardAction } = await server.ssrLoadModule('/src/lib/claims.ts');
  const { settings } = await server.ssrLoadModule('/src/lib/settings.svelte.ts');
  const { practice } = await server.ssrLoadModule('/src/lib/practice.svelte.ts');

  // 1. Unit-test forcedDiscardAction
  const mkView = (over = {}) => ({
    rules: 'simple1,none',
    position: {
      setup: { dealer: 0, east: 0, round: 0, streak: 0 }, me: 0, hand: '123m', drawn: '1m', after_call: false, claim: null,
      players: [{ melds: [], flowers: '', discards: [], declared: 'baoting' }, { melds: [], flowers: '', discards: [] }, { melds: [], flowers: '', discards: [] }, { melds: [], flowers: '', discards: [] }],
      wall_left: 40,
    },
    phase: 'self', current: 0, seat_winds: [0, 1, 2, 3], my_turn: true, legal: [{ type: 'discard', tile: '1m' }], events: [], event_count: 0, result: null,
    ...over,
  });
  assert.deepEqual(forcedDiscardAction(mkView(), 0), { type: 'discard', tile: '1m' });
  assert.equal(forcedDiscardAction(mkView({ legal: [{ type: 'discard', tile: '1m' }, { type: 'tsumo', tile: '1m' }] }), 0), null);
  assert.equal(forcedDiscardAction(mkView({ phase: 'react' }), 0), null);

  // 2. End-to-end: Practice automatically discards for a declared ready hand with only one discard legal.
  settings.speed = 'fast'; // bot_turn = 675ms
  actCalls = [];
  scenario = { declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 0 };
  await practice.newGame('forced-1');
  await new Promise((r) => setTimeout(r, 850));
  assert.equal(actCalls.length, 1, 'declared ready hand should auto-discard once');
  assert.deepEqual(actCalls[0], { type: 'discard', tile: '1m' });

  // 3. When a win is also legal, Practice must wait for the player.
  actCalls = [];
  scenario = { declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }, { type: 'tsumo', tile: '1m' }], actDelayMs: 0 };
  await practice.newGame('forced-2');
  await new Promise((r) => setTimeout(r, 850));
  assert.equal(actCalls.length, 0, 'declared hand with tsumo option must wait for player');

  // 4. Starting a new game cancels the pending forcedDiscard timer.
  actCalls = [];
  scenario = { declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 0 };
  await practice.newGame('forced-3');
  await new Promise((r) => setTimeout(r, 100));
  scenario = { declared: null, legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 0 };
  await practice.newGame('forced-4');
  await new Promise((r) => setTimeout(r, 850));
  assert.equal(actCalls.length, 0, 'previous game forcedDiscard timer must be cancelled on newGame');

  console.log('practice.test.mjs: all passed');
} finally {
  await server.close();
}
