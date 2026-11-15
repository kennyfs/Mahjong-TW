// Tiles by their engine names: "1m"-"9m" (萬), "1p"-"9p" (筒), "1s"-"9s" (條), "1z"-"7z" (東南西北白發中), "1f"-"8f"
// (春夏秋冬梅蘭菊竹). TILES strings list them grouped by suit ("123m55z"). A port of the app's Tiles.kt.

import { has, t as tr, ta } from './i18n.svelte';

export type Tile = string;

const SUIT_ORDER = 'mpszf';

/** "123m55z" -> ["1m", "2m", "3m", "5z", "5z"]. */
export function parseTiles(s: string | null | undefined): Tile[] {
  const out: Tile[] = [];
  if (!s) return out;
  let digits: string[] = [];
  for (const c of s) {
    if (c >= '0' && c <= '9') digits.push(c);
    else if (SUIT_ORDER.includes(c)) {
      for (const d of digits) out.push(d + c);
      digits = [];
    }
  }
  return out;
}

/** The tiles as a TILES string, sorted: ["5z", "1m", "3m"] -> "13m5z". */
export function tilesString(tiles: Tile[]): string {
  let out = '';
  for (const suit of SUIT_ORDER) {
    const ds = tiles.filter((t) => t[1] === suit).map((t) => t[0]).sort();
    if (ds.length) out += ds.join('') + suit;
  }
  return out;
}

/** Order of kinds: characters, dots, bamboo, honors, flowers. */
export function tileIndex(t: Tile): number {
  const n = Number(t[0]);
  switch (t[1]) {
    case 'm': return n - 1;
    case 'p': return 8 + n;
    case 's': return 17 + n;
    case 'z': return 26 + n;
    default: return 33 + n;
  }
}

export const compareTiles = (a: Tile, b: Tile) => tileIndex(a) - tileIndex(b);
export const sortTiles = (ts: Tile[]) => [...ts].sort(compareTiles);

/** All 34 kinds in order, and the 8 flowers. */
export const KINDS: Tile[] = [
  ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => `${n}m`),
  ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => `${n}p`),
  ...[1, 2, 3, 4, 5, 6, 7, 8, 9].map((n) => `${n}s`),
  ...[1, 2, 3, 4, 5, 6, 7].map((n) => `${n}z`),
];
export const FLOWERS: Tile[] = [1, 2, 3, 4, 5, 6, 7, 8].map((n) => `${n}f`);

export const isFlower = (t: Tile) => t[1] === 'f';
export const isHonor = (t: Tile) => t[1] === 'z';

/** A suited tile's name from its numeral (or numerals) and suit (0 萬, 1 筒, 2 條): 「五萬」, "5 Characters". */
export const suitedName = (numerals: string, suit: number) => tr('tile_name', numerals, ta('tile_suits')[suit]);
/** A numeral's word: 五, "5". */
export const numeral = (n: number) => ta('tile_numerals')[n - 1];

/** "五萬", "三筒", "七條", "東", "白", "春" (in the page's language). */
export function tileName(t: Tile): string {
  const n = Number(t[0]);
  const suit = 'mps'.indexOf(t[1]);
  if (suit >= 0) return suitedName(numeral(n), suit);
  return ta(t[1] === 'z' ? 'tile_honors' : 'tile_flowers')[n - 1];
}

/** Tiles in few words: ["3m", "6m", "2p", "5z"] -> "三六萬二筒白", "3/6 Characters, 2 Dots, White": a suit's numerals
 *  together before its name. */
export function tilesShort(tiles: Tile[]): string {
  const groups: string[] = [];
  for (let i = 0; i < tiles.length;) {
    const x = tiles[i];
    const suit = 'mps'.indexOf(x[1]);
    if (suit < 0) { groups.push(tileName(x)); i++; continue; }
    let j = i;
    while (j < tiles.length && tiles[j][1] === x[1]) j++;
    groups.push(suitedName(tiles.slice(i, j).map((y) => numeral(Number(y[0]))).join(tr('tiles_num_sep')), suit));
    i = j;
  }
  return groups.join(tr('tiles_sep'));
}

/** A tai item's name by its parameter, in the page's language (the engine names it in Chinese: `fallback`). */
export const taiName = (param: string, fallback: string) => (has('tai_' + param) ? tr('tai_' + param) : fallback);

/** A wind (0-3) by name: 東, "East". */
export const windName = (w: number) => ta('tile_honors')[w] ?? '?';
/** A wind's mark on a seat's plate: 東, "E". */
export const windMark = (w: number) => ta('wind_marks')[w] ?? '?';

export function countOf(tiles: Tile[], t: Tile): number {
  let n = 0;
  for (const x of tiles) if (x === t) n++;
  return n;
}

/** `tiles` without one copy of each of `remove`. */
export function without(tiles: Tile[], remove: Tile[]): Tile[] {
  const out = [...tiles];
  for (const r of remove) {
    const i = out.indexOf(r);
    if (i >= 0) out.splice(i, 1);
  }
  return out;
}

/**
 * Where a hand's tiles that its waits use (the engine's "wait_tiles", "ready_tiles": 聽的相關牌組) are: indices into
 * `tiles` (sorted) and then the drawn tile, the tile at `skip` (the one to be discarded) left out. Of a kind it has
 * more of than the group, the hand's own before the drawn one, and those next to the group's neighbouring kind.
 */
export function groupIndices(tiles: Tile[], drawn: Tile | null, group: Tile[], skip: number | null = null): Set<number> {
  const out = new Set<number>();
  const need = new Map<Tile, number>();
  for (const g of group) need.set(g, (need.get(g) ?? 0) + 1);
  const all = drawn ? [...tiles, drawn] : tiles;
  for (const [x, c] of need) {
    const at = all.map((y, i) => (y === x && i !== skip ? i : -1)).filter((i) => i >= 0 && i < tiles.length);
    const n = Number(x[0]);
    const next = (d: number) => !isHonor(x) && need.has(`${n + d}${x[1]}`);
    const mine = next(1) && !next(-1) ? at.slice(Math.max(0, at.length - c)) : at.slice(0, c);
    mine.forEach((i) => out.add(i));
    if (mine.length < c && drawn === x && skip !== tiles.length) out.add(tiles.length);
  }
  return out;
}
