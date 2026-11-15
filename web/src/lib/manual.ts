// Manual play (the analysis' 手動繼續, Branch.svelte): the player enters what happens at a real table, a step at a
// time; the engine fits the tiles the player cannot see to it (Game::setManual, docs/gui-engine.md).
import * as engine from './engine';
import { FLOWERS, KINDS, parseTiles, type Tile } from './tiles';
import type { Action, GameRecord, Meld, View } from './types';

/** How many of each tile the player has not seen: four of a kind, one of a flower, less what shows (the player's hand
 *  without a stand-in draw, every meld, the discards not called, the flowers). */
export function unseen(view: View, flowers: boolean): Map<Tile, number> {
  const left = new Map<Tile, number>(KINDS.map((k) => [k, 4]));
  if (flowers) for (const f of FLOWERS) left.set(f, 1);
  const take = (t: Tile | null | undefined, n = 1) => { if (t && left.has(t)) left.set(t, Math.max(0, left.get(t)! - n)); };
  const hand = parseTiles(view.position.hand);
  if (view.draw_needed && view.position.drawn) hand.splice(hand.indexOf(view.position.drawn), 1);
  hand.forEach((t) => take(t));
  for (const p of view.position.players) {
    for (const m of p.melds) meldTiles(m).forEach((t) => take(t));
    for (const d of p.discards) if (!d.called) take(d.tile);
    parseTiles(p.flowers).forEach((t) => take(t));
  }
  return left;
}

/** A meld's tiles as the table shows them (another's concealed kong: none). */
function meldTiles(m: Meld): Tile[] {
  if (m.tiles?.length) return m.tiles;
  if (!m.tile) return [];
  if (m.type === 'chi') return [0, 1, 2].map((d) => `${Number(m.tile![0]) + d}${m.tile![1]}`);
  return Array(m.type === 'pon' ? 3 : 4).fill(m.tile);
}

/** The chows a seat could make on `tile`: each by its lowest tile, with the two tiles it needs. */
export function chows(tile: Tile): { low: Tile; need: Tile[] }[] {
  if (tile[1] === 'z' || tile[1] === 'f') return [];
  const n = Number(tile[0]), s = tile[1];
  const out: { low: Tile; need: Tile[] }[] = [];
  for (let low = n - 2; low <= n; low++) {
    if (low < 1 || low + 2 > 9) continue;
    out.push({ low: `${low}${s}`, need: [low, low + 1, low + 2].filter((x) => x !== n).map((x) => `${x}${s}`) });
  }
  return out;
}

/** The view as the player sees it while their draw is still a stand-in: the hand without it, nothing to decide. */
export function withoutStandIn(v: View, me: number): View {
  if (!v.draw_needed || !v.position.drawn) return v;
  const hand = parseTiles(v.position.hand);
  hand.splice(hand.indexOf(v.position.drawn), 1);
  let cut = v.events.length;
  for (let i = v.events.length - 1; i >= 0; i--)
    if (v.events[i].seat === me && v.events[i].type === 'draw') { cut = i; break; }
  return { ...v, position: { ...v.position, hand: hand.join(''), drawn: null }, my_turn: false, legal: [],
    events: v.events.filter((_, i) => i !== cut), event_count: v.event_count - (cut < v.events.length ? 1 : 0) };
}

/** A step entered: draws first (each brought by the action before it is entered: `at` relative to the record cut at
 *  the step, -1 the last action there), then the actions in turn, each checked by the engine. */
export interface Entry { draws?: { seat: number; tile: Tile; after?: number }[]; actions: [number, Action][] }

/** The record with an entry made at its end (the passes of those still to answer a discard first, when `passFirst`). */
export async function enter(g: GameRecord, e: Entry): Promise<GameRecord> {
  const base = g.base!;
  const manual = { draws: [...(base.manual?.draws ?? [])] };
  for (const d of e.draws ?? []) manual.draws.push([g.actions.length + (d.after ?? -1), d.seat, d.tile]);
  let out: GameRecord = { ...g, base: { ...base, manual } as GameRecord['base'] };
  for (const [seat, a] of e.actions) out = await engine.act(out, seat, a);
  return out;
}

/** The passes of the seats still to answer the tile on the table, but `except`. */
export async function passes(g: GameRecord, except: number[] = []): Promise<[number, Action][]> {
  const p = await engine.pending(g);
  if (p.phase !== 'react' && p.phase !== 'rob_kong') return [];
  return p.pending.filter((s) => !except.includes(s)).map((s) => [s, { type: 'pass' }]);
}

/** A record cut at `k` actions, with the draws entered for what is kept. */
export function cut(g: GameRecord, k: number): GameRecord {
  const base = g.base;
  const draws = base?.manual?.draws.filter(([at]) => at < k);
  return { ...g, actions: g.actions.slice(0, k), ...(base?.manual ? { base: { ...base, manual: { draws: draws! } } as GameRecord['base'] } : {}) };
}
