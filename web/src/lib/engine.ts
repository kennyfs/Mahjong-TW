// The engine (cpp/command/guiengine.h, as WebAssembly in a worker): one JSON request, one JSON answer, run one at a
// time in the order they are made. A port of the app's Engine.kt.
//
// The network (engine-files/actor.onnx) is fetched once, when a request first names it, and written into the engine's
// file system as MODEL: the analysis' figures, a hand's review, the tables of draws and claims, and the bots
// "net:model=MODEL".
import files from 'virtual:engine-files';
import { keepOnly, storedBytes } from './stored';
import type { Action, Analysis, GameRecord, Pending, Position, Review, RulesInfo, Setup, View, WhatIf } from './types';

export class EngineError extends Error {}

/** Where the network is in the engine's file system. */
export const MODEL = '/models/actor.onnx';

const url = (u: string) => new URL(u, document.baseURI).href;
const ENGINE = { js: url(files.js), wasm: url(files.wasm), wasmSize: files.wasmSize };
const MODEL_URL = url(files.model);
/** Which network this build has: its file's name, which carries its hash. */
export const MODEL_ID = files.model.slice(files.model.lastIndexOf('/') + 1).replace(/\.onnx$/, '');

let worker: Worker | null = null;
let next = 0;
const waiting = new Map<number, { resolve: (s: string) => void; reject: (e: Error) => void }>();

function start(): Worker {
  if (worker) return worker;
  // The worker is bundled elsewhere than the page: its name tells it where the engine's files are.
  worker = new Worker(new URL('./engine-worker.ts', import.meta.url), { type: 'module', name: JSON.stringify(ENGINE) });
  void keepOnly([ENGINE.wasm, MODEL_URL]);
  worker.onmessage = (e: MessageEvent) => {
    const w = waiting.get(e.data.id);
    if (!w) return;
    waiting.delete(e.data.id);
    if (e.data.error !== undefined) w.reject(new EngineError(e.data.error));
    else w.resolve(e.data.line);
  };
  worker.onerror = (e) => {
    for (const w of waiting.values()) w.reject(new EngineError(e.message || 'The engine failed to start'));
    waiting.clear();
  };
  return worker;
}

function post(msg: Record<string, unknown>, transfer: Transferable[] = []): Promise<string> {
  const w = start();
  const id = next++;
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject });
    w.postMessage({ id, ...msg }, transfer);
  });
}

/** One request line to one answer line, errors as {"error": ...}: for tests. */
export function raw(line: string): Promise<string> {
  return post({ line });
}

let model: Promise<void> | null = null;
/** Fetches the network into the engine, once; a failure is tried again at the next call. */
export function loadModel(): Promise<void> {
  model ??= storedBytes(MODEL_URL, files.modelSize).then((b) => post({ file: { path: MODEL, data: b } }, [b])).then(
    () => {},
    (e) => {
      model = null;
      throw new EngineError(String((e as Error)?.message ?? e));
    },
  );
  return model;
}

export async function request(req: Record<string, unknown>): Promise<any> {
  const line = JSON.stringify(req);
  // A request that names the network: it is fetched first.
  if (line.includes(MODEL)) await loadModel();
  const a = JSON.parse(await raw(line));
  if (a.error !== undefined) throw new EngineError(a.error);
  return a;
}

export async function newHand(rules: string, setup: Setup, seed?: string): Promise<GameRecord> {
  return (await request({ cmd: 'new', rules, setup, seed })).game;
}

export async function view(game: GameRecord, seat: number, since = 0, reveal = false): Promise<View> {
  return request({ cmd: 'view', game, seat, since, reveal });
}

export async function act(game: GameRecord, seat: number, action: Action): Promise<GameRecord> {
  return (await request({ cmd: 'act', game, seat, action })).game;
}

export async function bot(game: GameRecord, seat: number, player: string, seed?: string): Promise<Action> {
  return (await request({ cmd: 'bot', game, seat, player, seed })).action;
}

export async function advance(game: GameRecord, players: (string | null)[], seed?: string, steps?: number): Promise<GameRecord> {
  return (await request({ cmd: 'advance', game, players, seed, steps })).game;
}

export async function pending(game: GameRecord): Promise<Pending> {
  return request({ cmd: 'pending', game });
}

export async function nextHand(game: GameRecord, startDealer: number, seed?: string): Promise<{ game: GameRecord; gameOver: boolean }> {
  const a = await request({ cmd: 'next', game, start_dealer: startDealer, seed });
  return { game: a.game, gameOver: a.game_over };
}

/** A decision's figures: the network's (`net`), or tile efficiency alone. */
export async function analyzeGame(game: GameRecord, seat: number, net = true): Promise<Analysis> {
  return request({ cmd: 'analyze', game, seat, model: net ? MODEL : undefined });
}

export async function analyzePosition(rules: string, position: Position, seed?: string, net = true): Promise<Analysis> {
  return request({ cmd: 'analyze', rules, position, seed, model: net ? MODEL : undefined });
}

/** Every decision of `seat` in a finished hand, with the network's figures and what the seat could not see. */
export async function review(game: GameRecord, seat: number): Promise<Review> {
  return request({ cmd: 'review', game, seat, model: MODEL });
}

/** What `seat` would do after each tile it may draw, and on each discard of the others it could claim. */
export async function whatIfGame(game: GameRecord, seat: number): Promise<WhatIf> {
  return request({ cmd: 'whatif', game, seat, model: MODEL, seed: 'whatif' });
}

export async function whatIfPosition(rules: string, position: Position): Promise<WhatIf> {
  return request({ cmd: 'whatif', rules, position, model: MODEL, seed: 'whatif' });
}

export async function rulesInfo(rules?: string): Promise<RulesInfo> {
  return request({ cmd: 'rules', rules });
}
