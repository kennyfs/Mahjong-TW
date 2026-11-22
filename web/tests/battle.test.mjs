// Battle tests: IPv6 serverUrl, stale/duplicate incremental TableView filtering, reconnect cancellation, and
// concurrent outcome recording deduplication + post-leave name snapshot.
// Run with Node: node web/tests/battle.test.mjs (or npm test in web/).
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const WEB = fileURLToPath(new URL('..', import.meta.url));
globalThis.document = { baseURI: 'http://localhost/', documentElement: { lang: '' }, title: '', hidden: false, addEventListener() {} };
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { host: 'localhost:5173', protocol: 'https:', hash: '', search: '' };
Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-TW'], language: 'zh-TW' }, configurable: true });
globalThis.sessionStorage = { getItem: () => null, setItem() {}, removeItem() {} };
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };

const sockets = [];
class FakeWebSocket {
  static OPEN = 1;
  readyState = 1;
  sent = [];
  onopen = null;
  onmessage = null;
  onclose = null;
  constructor(url) {
    this.url = url;
    sockets.push(this);
    setTimeout(() => this.onopen?.(), 0);
  }
  send(data) { this.sent.push(JSON.parse(data)); }
  close() { this.readyState = 3; }
  emit(msg) { this.onmessage?.({ data: JSON.stringify(msg) }); }
  drop(code = 1006) { this.readyState = 3; this.onclose?.({ code, reason: '' }); }
}
globalThis.WebSocket = FakeWebSocket;

class FakeWorker {
  onmessage = null;
  onerror = null;
  postMessage(msg) {
    const req = JSON.parse(msg.line);
    if (req.cmd === 'view') {
      setTimeout(() => this.onmessage?.({
        data: {
          id: msg.id,
          line: JSON.stringify({
            rules: req.game.rules,
            position: { setup: req.game.setup, me: 0, hand: '', drawn: null, after_call: false, claim: null, players: [], wall_left: 0 },
            phase: 'over', current: 0, seat_winds: [0, 1, 2, 3], my_turn: false, legal: [], events: [], event_count: 0,
            result: { exhausted: true, winners: [], loser: -1, win_tile: null, self_draw: false, flower_win: false, dealer_tai: 0, points: [0, 0, 0, 0], base_delta: [0, 0, 0, 0], tai_delta: [0, 0, 0, 0], next_dealer: 0, next_streak: 0 },
          }),
        },
      }), 40);
    }
  }
  terminate() {}
}
globalThis.Worker = FakeWorker;

const { createServer } = await import(`${WEB}/node_modules/vite/dist/node/index.js`);
const server = await createServer({ root: WEB, logLevel: 'error', server: { middlewareMode: true, hmr: false }, appType: 'custom' });
try {
  const { battle, serverUrl } = await server.ssrLoadModule('/src/lib/battle.svelte.ts');
  const { records } = await server.ssrLoadModule('/src/lib/records.svelte.ts');

  // 1. serverUrl IPv6 + TLS rules
  assert.equal(serverUrl('mahjong.1li.tw'), 'wss://mahjong.1li.tw/ws');
  assert.equal(serverUrl('127.0.0.1:9'), 'ws://127.0.0.1:9/ws');
  assert.equal(serverUrl('[::1]:8080'), 'ws://[::1]:8080/ws');
  assert.equal(serverUrl('ws://[::1]:8080'), 'ws://[::1]:8080/ws');
  assert.equal(serverUrl('[2001:db8::1]:8443'), 'wss://[2001:db8::1]:8443/ws');
  assert.equal(serverUrl('ws://example.com/ws'), null); // https page refuses non-local ws://
  assert.equal(serverUrl('ws://[::1'), null);

  // 2. Incremental TableViews at the same step (the computer players' moves between the player's decisions: the
  // server moves `step` only when it asks a person) all add their events.
  battle.server = '127.0.0.1:9';
  battle.create('TABLE');
  await new Promise((r) => setTimeout(r, 10));
  const ws = sockets[sockets.length - 1];
  ws.emit({ t: 'welcome', version: 1 });
  ws.emit({ t: 'joined', room: 'r1', you: 10, token: 'tok1' });
  ws.emit({
    t: 'state',
    room: {
      pin: '123456', rules: 'simple1,none', phase: 'PLAYING', hand: 0, host: 10,
      settings: { mode: 'TABLE', length: 'HAND', hands: 1, rules: { tai: 'simple1', flowers: 'none', overrides: {} }, timeLimit: 15, bot: 'greedy' },
      players: [
        { id: 10, name: 'Alice', bot: false, connected: true, seat: 0, ready: false, done: false, total: 0, left: false },
        { id: 11, name: 'Bob', bot: false, connected: true, seat: 1, ready: false, done: false, total: 0, left: false },
        { id: 12, name: '1', bot: true, connected: true, seat: 2, ready: false, done: false, total: 0, left: false },
        { id: 13, name: '2', bot: true, connected: true, seat: 3, ready: false, done: false, total: 0, left: false },
      ],
    },
  });
  const baseView = {
    rules: 'simple1,none', position: { setup: { dealer: 0, east: 0, round: 0, streak: 0 }, me: 0, hand: '', drawn: null, after_call: false, claim: null, players: [], wall_left: 40 },
    phase: 'self', current: 0, seat_winds: [0, 1, 2, 3], my_turn: false, legal: [], event_count: 1, result: null,
  };
  ws.emit({ t: 'table', hand: 0, seat: 0, step: 1, seats: [10, 11, 12, 13], full: true, view: { ...baseView, events: [{ seat: 0, type: 'discard', tile: '1m' }] } });
  ws.emit({ t: 'table', hand: 0, seat: 0, step: 1, seats: [10, 11, 12, 13], full: false, view: { ...baseView, event_count: 2, events: [{ seat: 1, type: 'discard', tile: '2m' }] } });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(battle.table?.view.events.length, 2, 'a computer player\'s move at the same step must be shown');

  // 3. Concurrent result + history for same hand does not duplicate record, and leaving mid-record keeps real names.
  records.clear();
  const outcome = {
    index: 0,
    players: [],
    tables: [{ seats: [10, 11, 12, 13], record: { rules: 'simple1,none', setup: { dealer: 0, east: 0, round: 0, streak: 0 }, wall: '', actions: [] }, points: [0, 0, 0, 0], timeouts: [0, 0, 0, 0] }],
  };
  ws.emit({ t: 'result', outcome });
  ws.emit({ t: 'history', outcomes: [outcome] });
  // Leave immediately while engine.view is still in flight (40ms delay):
  battle.leave();
  await new Promise((r) => setTimeout(r, 80));
  assert.equal(records.hands.length, 1, 'concurrent result + history must record the hand only once');
  assert.equal(records.hands[0].names[0], 'Alice', 'leaving room while recording must preserve player names');
  assert.equal(records.hands[0].names[1], 'Bob', 'leaving room while recording must preserve opponent names');

  console.log('battle.test.mjs: all passed');
} finally {
  await server.close();
}
