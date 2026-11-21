// Practice and Claims tests: forcedDiscard, double-tap protection, stale epoch guards, and error recovery.
// Run with Node from the repo root: node web/tests/practice.test.mjs
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

let scenario = {
  declared: 'baoting',
  legal: [{ type: 'discard', tile: '1m' }],
  actDelayMs: 0,
  nextDelayMs: 0,
  failNext: false,
  failViewWall: null,
};
let actCalls = [];
let nextCalls = 0;

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
      if (scenario.failViewWall && req.game.wall === scenario.failViewWall) {
        reply({ error: 'corrupt saved game' });
        return;
      }
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
    } else if (req.cmd === 'next') {
      nextCalls++;
      if (scenario.failNext) reply({ error: 'next failed' }, scenario.nextDelayMs);
      else reply({ game: { rules: req.game.rules, setup: req.game.setup, wall: '2m'.repeat(30), actions: [] }, game_over: false }, scenario.nextDelayMs);
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
  const { KEYS, save } = await server.ssrLoadModule('/src/lib/store.ts');

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

  // 2. Declared ready hand auto-discards.
  settings.speed = 'fast';
  actCalls = [];
  scenario = { ...scenario, declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 0 };
  await practice.newGame('forced-1');
  await new Promise((r) => setTimeout(r, 850));
  assert.equal(actCalls.length, 1, 'declared ready hand should auto-discard once');

  // 3. Declared hand with win option waits for player.
  actCalls = [];
  scenario = { ...scenario, declared: 'baoting', legal: [{ type: 'discard', tile: '1m' }, { type: 'tsumo', tile: '1m' }] };
  await practice.newGame('forced-2');
  await new Promise((r) => setTimeout(r, 850));
  assert.equal(actCalls.length, 0, 'declared hand with tsumo option must wait for player');

  // 4. Double-tap act() only sends one engine.act request.
  actCalls = [];
  scenario = { ...scenario, declared: null, legal: [{ type: 'discard', tile: '1m' }], actDelayMs: 50 };
  await practice.newGame('dt-1');
  await new Promise((r) => setTimeout(r, 20));
  await Promise.all([
    practice.act({ type: 'discard', tile: '1m' }),
    practice.act({ type: 'discard', tile: '1m' }),
  ]);
  assert.equal(actCalls.length, 1, 'double-tap act() must send only one engine request');
  await new Promise((r) => setTimeout(r, 40));
  assert.equal(practice.stage, 'result');

  // 5. Double-tap nextHand() only calls engine.nextHand once, and catches errors cleanly.
  nextCalls = 0;
  scenario = { ...scenario, nextDelayMs: 50, failNext: true };
  practice.error = null;
  await Promise.all([practice.nextHand(), practice.nextHand()]);
  assert.equal(nextCalls, 1, 'double-tap nextHand() must only call engine.nextHand once');
  assert.ok(practice.error && practice.error.includes('next failed'), 'nextHand() error must be surfaced');

  // Subsequent successful nextHand() works after error cleared acting flag.
  nextCalls = 0;
  scenario = { ...scenario, nextDelayMs: 20, failNext: false };
  await practice.nextHand();
  assert.equal(nextCalls, 1);
  assert.equal(practice.stage, 'playing');

  // 6. newGame() during in-flight act() discards stale act() result.
  scenario = { ...scenario, declared: null, actDelayMs: 80 };
  await practice.newGame('race-1');
  await new Promise((r) => setTimeout(r, 20));
  const staleAct = practice.act({ type: 'discard', tile: '1m' });
  await practice.newGame('race-2');
  await staleAct;
  assert.equal(practice.meta?.id, 'race-2');
  assert.equal(practice.game?.actions.length, 0, 'stale act must not overwrite new game');
  assert.equal(practice.stage, 'playing');

  // 7. resume() recovers from a saved game that fails engine.view by starting a new game.
  scenario = { ...scenario, failViewWall: 'BADWALL', actDelayMs: 0 };
  save(KEYS.practice, {
    game: { rules: settings.spec, setup: { dealer: 0, east: -1, round: 0, streak: 0 }, wall: 'BADWALL', actions: [] },
    meta: { id: 'corrupt-1', length: 'game', rules: settings.spec, bot: settings.bot, startDealer: 0, startRound: 0, hands: 0, totals: [0, 0, 0, 0], over: false },
  });
  await practice.resume();
  assert.notEqual(practice.meta?.id, 'corrupt-1', 'corrupt saved game must fall back to a new game');
  assert.equal(practice.stage, 'playing');

  console.log('practice.test.mjs: all passed');
} finally {
  await server.close();
}
