// The engine (cpp/command/guiengine.h, as WebAssembly in a worker): one JSON request, one JSON answer, run one at a
// time in the order they are made. A port of the app's Engine.kt.
import files from 'virtual:engine-files';
import { keepOnly } from './stored';
import type { Action, Analysis, GameRecord, Pending, Position, RulesInfo, Setup, View } from './types';

export class EngineError extends Error {}

const url = (u: string) => new URL(u, document.baseURI).href;
const ENGINE = { js: url(files.js), wasm: url(files.wasm), wasmSize: files.wasmSize };

let worker: Worker | null = null;
let next = 0;
const waiting = new Map<number, { resolve: (s: string) => void; reject: (e: Error) => void }>();

function start(): Worker {
  if (worker) return worker;
  // The worker is bundled elsewhere than the page: its name tells it where the engine's files are.
  worker = new Worker(new URL('./engine-worker.ts', import.meta.url), { type: 'module', name: JSON.stringify(ENGINE) });
  void keepOnly([ENGINE.wasm]);
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

/** One request line to one answer line, errors as {"error": ...}: for tests. */
export function raw(line: string): Promise<string> {
  const w = start();
  const id = next++;
  return new Promise((resolve, reject) => {
    waiting.set(id, { resolve, reject });
    w.postMessage({ id, line });
  });
}

export async function request(req: Record<string, unknown>): Promise<any> {
  const a = JSON.parse(await raw(JSON.stringify(req)));
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

export async function analyzeGame(game: GameRecord, seat: number): Promise<Analysis> {
  return request({ cmd: 'analyze', game, seat });
}

export async function analyzePosition(rules: string, position: Position, seed?: string): Promise<Analysis> {
  return request({ cmd: 'analyze', rules, position, seed });
}

export async function rulesInfo(rules?: string): Promise<RulesInfo> {
  return request({ cmd: 'rules', rules });
}
