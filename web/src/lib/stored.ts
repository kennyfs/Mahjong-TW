// The engine's large files (its WebAssembly; a network later) kept in Cache Storage, under their content-hashed
// URLs, and read from there before the network: a browser that keeps only a memory cache (Librewolf, Firefox without
// its disk cache), which drops entries this large, then still downloads them once and not on every load. Used by the
// page (the network) and the engine's worker (the WebAssembly). Where Cache Storage is missing or fails (a private
// window, storage blocked, no room), a plain fetch. Not in `npm run dev`, whose URLs carry no hash.

const CACHE = 'mahjong-engine';

async function open(): Promise<Cache | null> {
  if (import.meta.env.DEV || typeof caches === 'undefined') return null;
  try { return await caches.open(CACHE); } catch { return null; }
}

/**
 * The file at `url` (a hashed name: its content never changes), `size` bytes: from Cache Storage if there, else
 * fetched, and kept there when the whole of it came. `progress`: told the bytes fetched so far, as they come.
 */
export async function storedBytes(url: string, size: number, progress?: (done: number) => void): Promise<ArrayBuffer> {
  const cache = await open();
  if (cache) {
    try {
      const hit = await cache.match(url);
      if (hit) {
        const b = await hit.arrayBuffer();
        if (b.byteLength === size) return b;
        await cache.delete(url);
      }
    } catch { /* read it from the network */ }
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: ${r.status}`);
  const b = progress && r.body ? await read(r.body, progress) : await r.arrayBuffer();
  // A Response copies the bytes it is made from: `b` is the caller's still.
  if (cache && b.byteLength === size) cache.put(url, new Response(b)).catch(() => {});
  return b;
}

/** A body read in its chunks, `progress` told after each. */
async function read(body: ReadableStream<Uint8Array>, progress: (done: number) => void): Promise<ArrayBuffer> {
  const parts: Uint8Array[] = [];
  let done = 0;
  const reader = body.getReader();
  for (;;) {
    const { value, done: end } = await reader.read();
    if (end) break;
    parts.push(value);
    done += value.byteLength;
    progress(done);
  }
  const out = new Uint8Array(done);
  let at = 0;
  for (const p of parts) { out.set(p, at); at += p.byteLength; }
  return out.buffer;
}

/** Removes what Cache Storage keeps but `urls` (the files of this build): an older build's engine and network. */
export async function keepOnly(urls: string[]): Promise<void> {
  const cache = await open();
  if (!cache) return;
  try {
    for (const k of await cache.keys()) if (!urls.includes(k.url)) await cache.delete(k);
  } catch { /* left for another time */ }
}
