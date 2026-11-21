// Coach tests: digest seatWind when setup.east is -1 or omitted, providers custom model override & network body errors,
// and followUp rejecting blank replies.
// Run via: ./agy.sh node repo/web/tests/coach.test.mjs
import assert from 'node:assert/strict';
import { resolve } from 'node:path';

const WEB = resolve('repo/web');
globalThis.document = { baseURI: 'http://localhost/', documentElement: { lang: '' }, title: '', hidden: false, addEventListener() {} };
globalThis.window = { addEventListener() {}, removeEventListener() {} };
globalThis.location = { host: 'localhost:5173', protocol: 'http:', hash: '', search: '' };
Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-TW'], language: 'zh-TW' }, configurable: true });
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };

const { createServer } = await import(`${WEB}/node_modules/vite/dist/node/index.js`);
const server = await createServer({ root: WEB, logLevel: 'error', server: { middlewareMode: true, hmr: false }, appType: 'custom' });
try {
  const digest = await server.ssrLoadModule('/src/lib/coach/digest.ts');
  const providers = await server.ssrLoadModule('/src/lib/coach/providers.ts');
  const { coach } = await server.ssrLoadModule('/src/lib/coach/coach.svelte.ts');
  const { coachKeys } = await server.ssrLoadModule('/src/lib/coach/keys.svelte.ts');

  // 1. Digest seatWind falls back to dealer when setup.east is omitted or -1.
  assert.equal(digest.seatWind(0, { dealer: 1 }), 3); // North
  assert.equal(digest.seatWind(1, { dealer: 1, east: -1 }), 0); // East
  assert.equal(digest.seatWind(3, { dealer: 0, east: 2 }), 1); // South

  const hd = digest.handDigest(
    new digest.DigestWords(true),
    {
      id: 'h1', time: '2026-11-18T00:00:00Z', kind: 'practice',
      game: { rules: 'simple1,none', setup: { dealer: 1 }, wall: '', actions: [] },
      me: 0, names: ['你', '電腦 1', '電腦 2', '電腦 3'], bots: [null, 'net', 'net', 'net'],
      points: [0, 0, 0, 0], winners: [], loser: -1, selfDraw: false, exhausted: true, tai: 0,
    },
    { seat: 0, decisions: [], result: 0, first_value: 0 },
    new Map(),
  );
  assert.ok(hd.title.startsWith('北家｜'), `expected 北家 in title, got: ${hd.title}`);

  // 2. Custom provider respects explicit `model` argument (e.g. follow-up after user changed custom.model)
  //    and wraps mid-body r.text() failure as CoachError('NETWORK').
  let sentBody = null;
  let sentHeaders = null;
  let sentUrl = null;
  globalThis.fetch = async (url, init) => {
    sentUrl = url;
    sentHeaders = init.headers;
    sentBody = JSON.parse(init.body);
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ choices: [{ message: { content: 'answer' }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 2 } }),
    };
  };
  const reply = await providers.ask('custom', 'original-model', 'medium', 'secret-key', { url: 'https://api.example.test/v1/', model: 'newer-model' }, 'sys', [{ user: true, text: 'hi' }], null);
  assert.equal(sentUrl, 'https://api.example.test/v1/chat/completions');
  assert.equal(sentBody.model, 'original-model', 'custom ask() must use the explicit model argument when given');
  assert.equal(sentHeaders.Authorization, 'Bearer secret-key');
  assert.equal(reply.text, 'answer');

  // Mid-body stream failure becomes CoachError('NETWORK')
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => { throw new TypeError('network reset mid-body'); },
  });
  await assert.rejects(
    () => providers.ask('openai', 'gpt-6.1-sol', 'medium', 'k', { url: '', model: '' }, 'sys', [{ user: true, text: 'hi' }], null),
    (e) => e instanceof providers.CoachError && e.kind === 'NETWORK',
  );

  // 3. followUp rejects blank answers with BAD_ANSWER instead of appending empty turn.
  coachKeys.setCustom({ url: 'https://api.example.test/v1', model: 'm1' });
  globalThis.fetch = async () => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ choices: [{ message: { content: '   ' }, finish_reason: 'stop' }] }),
  });
  const session = {
    id: 's1', time: '2026-11-18T00:00:00Z', provider: 'custom', model: 'm1', lang: 'zh', rules: '簡單1',
    hands: ['h1'], system: 'sys', turns: [{ user: true, text: 'digest' }, { user: false, text: '{}' }],
    input: 10, output: 10, cost: null, asked: 'm1', effort: 'medium',
  };
  await coach.followUp(session, 'why?');
  assert.equal(coach.phase.kind, 'failed');
  assert.equal(coach.phase.error, 'BAD_ANSWER');

  console.log('coach.test.mjs: all passed');
} finally {
  await server.close();
}
