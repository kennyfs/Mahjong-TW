// Tiles by their engine names: "1m"-"9m" (萬), "1p"-"9p" (筒), "1s"-"9s" (條), "1z"-"7z" (東南西北白發中), "1f"-"8f"
// (春夏秋冬梅蘭菊竹). TILES strings list them grouped by suit ("123m55z"). A port of the app's Tiles.kt.

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

const NUMERALS = '一二三四五六七八九';
const HONORS = '東南西北白發中';
const FLOWER_NAMES = '春夏秋冬梅蘭菊竹';

/** "五萬", "三筒", "七條", "東", "白", "春". */
export function tileName(t: Tile): string {
  const n = Number(t[0]);
  switch (t[1]) {
    case 'm': return NUMERALS[n - 1] + '萬';
    case 'p': return NUMERALS[n - 1] + '筒';
    case 's': return NUMERALS[n - 1] + '條';
    case 'z': return HONORS[n - 1];
    default: return FLOWER_NAMES[n - 1];
  }
}

/** A wind (0-3) as its character. */
export const windName = (w: number) => HONORS[w] ?? '?';

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
