// Records persistence, import/export round-trip, validation, and storage-full eviction tests.
// Run with Node from the repo root: node web/tests/records.test.mjs
import assert from 'node:assert/strict';
import { resolve } from 'node:path';

const WEB = resolve('repo/web');
globalThis.document = { baseURI: 'http://localhost/', documentElement: { lang: '' }, title: '', hidden: false, addEventListener() {} };
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { host: 'localhost:5173', protocol: 'http:', hash: '', search: '' };
Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-TW'], language: 'zh-TW' }, configurable: true });

let quota = Infinity;
const store = new Map();
globalThis.localStorage = {
  getItem: (k) => store.get(k) ?? null,
  setItem: (k, v) => {
    if (String(v).length > quota) throw new DOMException('QuotaExceededError');
    store.set(k, String(v));
  },
  removeItem: (k) => store.delete(k),
};

const mkHand = (id, time, fav = false) => ({
  id, time, kind: 'practice',
  game: { rules: 'simple1,none', setup: { dealer: 0, east: -1, round: 0, streak: 0 }, wall: '1m'.repeat(30), actions: [] },
  me: 0, names: ['A', 'B', 'C', 'D'], bots: [null, 'greedy', 'greedy', 'greedy'],
  points: [0, 0, 0, 0], winners: [], loser: -1, selfDraw: false, exhausted: true, tai: 0, fav,
});

const { createServer } = await import(`${WEB}/node_modules/vite/dist/node/index.js`);
const server = await createServer({ root: WEB, logLevel: 'error', server: { middlewareMode: true, hmr: false }, appType: 'custom' });
try {
  const { parseRecords, records } = await server.ssrLoadModule('/src/lib/records.svelte.ts');

  // 1. parseRecords rejects malformed hands missing winners / bots / game.actions
  assert.equal(parseRecords('not json'), null);
  assert.equal(parseRecords(JSON.stringify({ app: 'other', hands: [] })), null);
  const parsed = parseRecords(JSON.stringify({
    app: 'mahjong-tw',
    hands: [
      mkHand('ok', '2026-11-10T10:00:00Z'),
      { id: 'bad1', time: '2026-11-10T10:00:00Z', game: { rules: 'simple1,none' }, me: 0, points: [0], names: ['A'] },
      { id: 'bad2', time: '2026-11-10T10:00:00Z', game: { rules: 'simple1,none', actions: [] }, me: 0, points: [0], names: ['A'] },
    ],
    battles: [
      { id: 'b1', time: '2026-11-10T10:00:00Z', kind: 'table', via: 'online', rules: 'simple1,none', names: ['A'], me: 0, totals: [0], hands: 1 },
      { id: 'b-bad', time: '2026-11-10T10:00:00Z', names: ['A'] },
    ],
  }));
  assert.deepEqual(parsed.hands.map((h) => h.id), ['ok']);
  assert.deepEqual(parsed.battles.map((b) => b.id), ['b1']);

  // 2. Round-trip exportText -> parseRecords -> import
  records.clear();
  const h1 = mkHand('h1', '2026-11-10T10:00:00Z', true);
  const h2 = mkHand('h2', '2026-11-11T10:00:00Z', false);
  const r1 = records.import({ hands: [h2, h1], battles: [] });
  assert.deepEqual(r1, { added: 2, had: 0, dropped: 0, room: false });
  assert.deepEqual(records.hands.map((h) => h.id), ['h1', 'h2']);
  const exported = parseRecords(records.exportText());
  assert.equal(exported.hands.length, 2);
  const rDup = records.import(exported);
  assert.deepEqual(rDup, { added: 0, had: 2, dropped: 0, room: false });

  // 3. Storage-full eviction during import() flushes synchronously, reports room=true, keeps favs + newest,
  //    and loops until the serialized list fits within quota.
  const oneSize = JSON.stringify([mkHand('x0', '2026-11-12T00:00:00Z')]).length;
  quota = Math.floor(oneSize * 2.4); // fits at most 2 hands
  const many = Array.from({ length: 8 }, (_, i) => mkHand(`m${i}`, `2026-11-12T00:00:0${i}Z`, false));
  const rFull = records.import({ hands: many, battles: [] });
  assert.equal(rFull.room, true, 'import() must flush synchronously and report room=true when quota exceeded');
  assert.ok(rFull.dropped > 0, 'dropped count must include storage-full evictions');
  assert.ok(records.hands.some((h) => h.id === 'h1'), 'favorite hand must be kept');
  assert.ok(records.hands.some((h) => h.id === 'm7'), 'newest hand must be kept');
  assert.ok(store.get('mahjong.records').length <= quota, 'persisted records must actually fit in storage');

  console.log('records.test.mjs: all passed');
} finally {
  await server.close();
}
