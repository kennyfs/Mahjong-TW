// Actions in words ("打出 五萬", "吃 三四五萬"), and which of an analysis' actions are the best by its figures.
import { t } from './i18n.svelte';
import { tileName, type Tile } from './tiles';
import type { Action, ActionFigures } from './types';

const WORD: Record<string, string> = {
  discard: 'act_discard', tsumo: 'act_tsumo', ron: 'act_ron', pon: 'act_pon', kan: 'act_kan', ankan: 'act_ankan',
  kakan: 'act_kakan', chi: 'act_chi', pass: 'act_pass', declare: 'act_declare',
};

/** The tiles an action shows: a chow's three, a discard's or a kong's one. */
export function actionTiles(a: Action): Tile[] {
  if (a.type === 'chi' && a.tiles?.length) return a.tiles;
  if (a.tile && a.type !== 'pass') return [a.tile];
  return [];
}

export function actionText(a: Action): string {
  const word = t(WORD[a.type] ?? a.type);
  const tiles = actionTiles(a);
  return tiles.length ? `${word} ${tiles.map(tileName).join('')}` : word;
}

/** Lower is better: a win first (more tai first), then fewer steps to ready (shanten), then more tiles that help. */
export function rank(f: ActionFigures): number[] {
  return f.shanten < 0 ? [-1, -f.win_tai] : [f.shanten, -f.ukeire];
}

export function bestOf(list: ActionFigures[]): Set<number> {
  const keys = list.map(rank);
  let best: number[] | null = null;
  for (const k of keys) if (!best || k[0] < best[0] || (k[0] === best[0] && k[1] < best[1])) best = k;
  const out = new Set<number>();
  keys.forEach((k, i) => { if (best && k[0] === best[0] && k[1] === best[1]) out.add(i); });
  return out;
}

/** A record's action (a chow by its lowest tile; a claim without its tile) as the full action it was: the chow's
 *  three tiles. */
export function fullAction(a: Action): Action {
  if (a.type === 'chi' && a.tile && !a.tiles) {
    const n = Number(a.tile[0]);
    return { ...a, tiles: [0, 1, 2].map((d) => `${n + d}${a.tile![1]}`) };
  }
  return a;
}

/** Whether an analysis' action is the one a record says was taken (claims are told apart by their kind only). */
export function isTaken(legal: Action, taken: Action): boolean {
  if (legal.type !== taken.type) return false;
  if (['discard', 'ankan', 'kakan', 'chi'].includes(legal.type)) return (legal.tile ?? null) === (taken.tile ?? null);
  return true;
}
