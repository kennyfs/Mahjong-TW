import { svelte } from '@sveltejs/vite-plugin-svelte';
import { statSync } from 'node:fs';
import { defineConfig, type Plugin } from 'vite';

// (No @types/node here: the one variable read; node:fs's one function in src/engine-files.d.ts.)
declare const process: { env: Record<string, string | undefined> };
const battle = process.env.BATTLE_SERVER ?? 'ws://127.0.0.1:8091';

/**
 * `virtual:engine-files`: the engine (engine-files/engine.js and engine.wasm, from build-engine.sh) as assets, so
 * that the build puts them in /assets under names with their hashes, kept for good by the server and always of one
 * build, and the WebAssembly's size, to check a download is whole (src/lib/stored.ts). A network, later, joins them
 * here as Thirteen's does.
 */
function engineFiles(): Plugin {
  const id = 'virtual:engine-files';
  const files = { js: 'engine.js', wasm: 'engine.wasm' };
  return {
    name: 'engine-files',
    resolveId: (s) => (s === id ? '\0' + id : undefined),
    load(s) {
      if (s !== '\0' + id) return;
      const size = (f: string) => {
        try { return statSync(new URL(`./engine-files/${f}`, import.meta.url)).size; } catch {
          this.error(`engine-files/${f} is missing: build the engine first (build-engine.sh)`);
        }
      };
      const names = Object.entries(files);
      return names.map(([k, f]) => `import ${k} from '/engine-files/${f}?url';\n`).join('') +
        `export default { ${names.map(([k]) => k).join(', ')}, wasmSize: ${size(files.wasm)} };\n`;
    },
  };
}

// A static site: relative paths, so it works from any directory (or a file server's subpath).
export default defineConfig({
  base: './',
  plugins: [svelte(), engineFiles()],
  worker: { format: 'es' },
  build: { target: 'es2022', chunkSizeWarningLimit: 1000 },
  // For testing battles: `vite dev` and `vite preview` pass /ws on to a battle server on this machine (BATTLE_SERVER,
  // by default port 8091), so the page and its server share one address, as behind Caddy (server/Caddyfile).
  server: { proxy: { '/ws': { target: battle, ws: true } } },
  preview: { proxy: { '/ws': { target: battle, ws: true } } },
});
