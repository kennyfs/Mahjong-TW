// The one function of node:fs that vite.config.ts uses (there is no @types/node here).
declare module 'node:fs' {
  export function statSync(path: string | URL): { size: number };
}

// vite.config.ts's engineFiles: the engine's URLs (hashed, in /assets) and the size of its WebAssembly.
declare module 'virtual:engine-files' {
  const files: { js: string; wasm: string; wasmSize: number };
  export default files;
}
