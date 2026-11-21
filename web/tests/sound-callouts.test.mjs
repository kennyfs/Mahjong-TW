// Callouts and sound tests: dealArm/dealComplete/dealReset, calloutsDone/calloutsSkipped, and heardFor.
// Run via: ./agy.sh node repo/web/tests/sound-callouts.test.mjs
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
  const callouts = await server.ssrLoadModule('/src/lib/callouts.ts');
  const sound = await server.ssrLoadModule('/src/lib/sound.ts');

  // 1. heardFor parity with Android SoundsTest
  assert.equal(sound.heardFor(650, 0, 1200), 650);
  assert.equal(sound.heardFor(2901, 0, 1100), 1100);
  assert.equal(sound.heardFor(212, 900, 1000), 100);
  assert.equal(sound.heardFor(212, 500, 500), 0);
  assert.equal(sound.heardFor(212, 0, 0), 0);

  // 2. dealArm waits until matching dealComplete or dealReset
  callouts.dealArm('h/0');
  let done = false;
  const p = callouts.dealReady().then(() => { done = true; });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(done, false);
  callouts.dealComplete('other/0');
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(done, false, 'mismatched dealComplete must not unblock');
  callouts.dealComplete('h/0');
  await p;
  assert.equal(done, true);

  // 3. calloutShown + calloutsDone + calloutsSkipped
  assert.equal(await callouts.calloutsDone(), false);
  callouts.calloutShown(500);
  let waited = null;
  const wp = callouts.calloutsDone().then((w) => { waited = w; });
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(waited, null);
  callouts.calloutsSkipped();
  await wp;
  assert.equal(waited, true);
  assert.equal(await callouts.calloutsDone(), false);

  console.log('sound-callouts.test.mjs: all passed');
} finally {
  await server.close();
}
