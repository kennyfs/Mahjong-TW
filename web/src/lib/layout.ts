// The table's sizes for the room it has: how wide each kind of tile is, how the four rivers (discards) are laid out
// around the center, and how much room the players' panels take. Wide (a phone held sideways, a computer) or tall
// (a phone upright). The same arithmetic as the app's TableLayout.kt, so both look alike. Sizes in px (the app's dp).
//
// Tiles stay large: the player's own are the ones tapped (40 px or more on a phone, never fewer than 30), and an
// upright phone wraps the hand after HAND_ROW tiles rather than shrinking them. The others' tiles and the rivers
// stay large enough to read (22 px and up on a phone). The rivers take what room is left, as large as it allows: on
// a wide table nothing lies between them (the round and the wall are in the table's top-left corner, the screen's
// buttons in its top-right one); on a tall one a slim box with the round and the wall.
import { TILE_RATIO } from '../components/Tile.svelte';

/** Tiles a row of the player's hand on a tall table (the drawn tile apart at the end of the last row). */
export const HAND_ROW = 8;
/** The gap before the drawn tile, in tile widths. */
export const DRAWN_GAP = 0.3;
/** The gap between the rows of a wrapped hand, in tile heights (room for a lifted tile, too). */
export const ROW_GAP = 0.2;
/** How far a chosen tile is lifted, in tile heights. */
export const LIFT = 0.2;
/** A flower's width, for the melds' tiles'. */
export const FLOWER_SCALE = 0.82;
/** Lines of the others' melds and flowers on a tall table (their tiles get smaller when there are more). */
export const OPP_LINES = 2;

/** The gaps between melds (Melds.svelte): between lines, and between melds in a line. */
export const meldGaps = (w: number) => ({ row: Math.max(3, w * 0.16), col: Math.max(3, w * 0.14) });

/** How many lines melds of these tile counts and these flowers take in `width` px, with tiles `w` px wide. */
export function meldLines(counts: number[], flowers: number, width: number, w: number): number {
  const gap = meldGaps(w).col;
  const items = [...counts.map((c) => c * w), ...Array<number>(flowers).fill(w * FLOWER_SCALE)];
  let lines = items.length ? 1 : 0;
  let x = 0;
  for (const it of items) {
    if (x > 0 && x + gap + it > width) {
      lines++;
      x = it;
    } else x += (x > 0 ? gap : 0) + it;
  }
  return lines;
}

/** The largest tile width from `max` down to `min` at which these melds and flowers fit in `lines` lines. */
export function fitMelds(counts: number[], flowers: number, width: number, lines: number, max: number, min = 16): number {
  for (let w = max; w > min; w -= 0.5) if (meldLines(counts, flowers, width, w) <= lines) return w;
  return min;
}

/** The height of the player's melds and flowers over a tall table's hand, `width` px wide: in one line at the
 *  largest tile width from `max` down to `min` that fits, or in more lines at `min` (a narrow phone with many). */
export function myMeldsHeight(counts: number[], flowers: number, width: number, max: number, min: number): number {
  const w = fitMelds(counts, flowers, width, 1, max, min);
  const lines = meldLines(counts, flowers, width, w);
  return lines ? lines * H(w) + (lines - 1) * meldGaps(w).row : 0;
}

// Another player's tiles when their hand is shown (a finished hand, a review showing everyone's): the melds and
// flowers as always, then the concealed tiles on a rack (a darker tray behind them, RACK_PAD px around), so that
// the hand is told from the melds at a glance.
export const RACK_PAD = 3;
/** The gap between the melds and the rack: under them, and beside them in one line. */
export const RACK_GAP = 5;
export const RACK_GAP_ROW = 10;

/** Lines that `n` tiles of `w` px take in `width` px. */
export const runLines = (n: number, width: number, w: number) => (n > 0 ? Math.ceil(n / Math.max(1, Math.floor(width / w))) : 0);

/** The height of a seat's shown tiles in a column `width` px wide: its melds and flowers in lines, the rack under them. */
export function shownHeight(counts: number[], flowers: number, concealed: number, width: number, w: number): number {
  const a = meldLines(counts, flowers, width, w);
  const b = runLines(concealed, width - 2 * RACK_PAD, w);
  return a * H(w) + Math.max(0, a - 1) * meldGaps(w).row + (a && b ? RACK_GAP : 0) + (b ? b * H(w) + 2 * RACK_PAD : 0);
}

/** The largest tile width from `max` down to `min` at which a seat's shown tiles fit in `width` x `height` px. */
export function fitShown(counts: number[], flowers: number, concealed: number, width: number, height: number, max: number, min = 16): number {
  for (let w = max; w > min; w -= 0.5) if (shownHeight(counts, flowers, concealed, width, w) <= height) return w;
  return min;
}

/** The same in one line (the player opposite on a wide table): melds, flowers and the rack side by side in `width` px. */
export function fitShownRow(counts: number[], flowers: number, concealed: number, width: number, max: number, min = 14): number {
  const items = counts.length + flowers;
  for (let w = max; w > min; w -= 0.5) {
    const melds = counts.reduce((a, c) => a + c * w, 0) + flowers * w * FLOWER_SCALE + Math.max(0, items - 1) * meldGaps(w).col;
    if (melds + (items && concealed ? RACK_GAP_ROW : 0) + (concealed ? concealed * w + 2 * RACK_PAD : 0) <= width) return w;
  }
  return min;
}

export interface TableSizes {
  wide: boolean;
  /** The player's own concealed tiles. */
  hand: number;
  /** The player's hand: tiles a row (0: one row). */
  handRow: number;
  /** The player's melds and flowers. */
  meld: number;
  /** Every river's tiles. */
  river: number;
  /** Others' melds and flowers. */
  opp: number;
  /** Others' concealed tiles, as backs (0: a count instead: a tall table, and a low wide one). */
  back: number;
  /** Tiles a row in the top and bottom rivers, and rows; tiles a row in the side rivers, and rows. */
  acrossCols: number;
  acrossRows: number;
  sideCols: number;
  sideRows: number;
  /** The side players' panels' width (wide); the others' strip's height; the player's area's height. */
  sideW: number;
  topH: number;
  meH: number;
  /** A tall table's box in the middle (round, wall, streak): its width (0: a wide table, which has none). */
  boxW: number;
  /** The room between the two rivers across: a tall table's box's height, a wide table's gap. */
  mid: number;
  /** The table's width (wide: no wider than its parts need, so that it keeps together on a large window). */
  tableW: number;
  /** The words' size on the table, px. */
  font: number;
  /** The line over the player's hand that holds their melds and flowers (a tall table's, their name plate too). */
  meldLine: number;
}

const H = (w: number) => w * TILE_RATIO;
/** A river's room at full size, in tiles: across (8 a row) and at the sides. A hand seldom has more discards a
 *  player; a longer river's tiles get smaller (River.svelte). */
const ACROSS = 16;
const SIDE = 15;
/** The gap between the two rivers across a wide table, in tile heights. */
const MID_ROWS = 0.4;
/** A tall table's box between them (a line of the round and the wall, another of the streak), in tile heights. */
const BOX_ROWS = 1;
/** The rivers' tiles on a tall table: no smaller than this while the hand can give room. */
const MIN_RIVER = 26;

/** The rivers' tiles on a tall table whose others' strip has grown to show everyone's tiles: no smaller than this. */
export const SHOWN_RIVER = 20;
/** The rivers' tiles where the shown hands do not fit over rivers of SHOWN_RIVER even at 16 px (a phone's review,
 *  short for its buttons under the table): smaller rivers rather than hands cut off. */
export const SHOWN_RIVER_MIN = 17;

/** The room kept between that strip and the rivers under it, so that a shown hand is not taken for a river. */
export const SHOWN_GAP = 14;

/** The height left for the others' strip on a tall table of height `h` when the rivers' tiles are `river` px wide. */
export const tallTopRoom = (s: TableSizes, h: number, river: number) => h - s.meH - 4 - ((2 * s.acrossRows + BOX_ROWS) * H(river) + 14) - SHOWN_GAP;

/** A wide table lower than this (a phone held sideways) says the others' concealed tiles as a count on their name
 *  plates, as a tall one does: rows of backs took the side players' room from their melds. */
const LOW = 500;

/** Sizes for a table of w x h px; `maxHand` caps the hand's tiles (a computer's big window). `top`: a tall table's
 *  others' strip is this high when that is more than it would be (everyone's tiles shown): the rivers give the room. */
export function tableSizes(w: number, h: number, maxHand = 64, top = 0): TableSizes {
  const wide = w >= h * 1.1;
  // A table no wider than this for its height: the side players stay near the middle on a large window.
  if (wide) w = Math.min(w, h * 2.3);
  if (wide) {
    // The hand: 17 tiles and the drawn one's gap across the width (the player's name plate is above it, at the
    // left), at most about a sixth of the height.
    const hand = Math.max(24, Math.min(maxHand, (w - 24) / (17 + DRAWN_GAP + 0.3), (h * 0.165) / TILE_RATIO));
    const meld = hand * 0.7;
    // The others' tiles: larger on a large window.
    const opp = Math.max(22, Math.min(hand * 0.66, h >= 900 ? 44 : 36));
    // The player's melds and flowers in a line over the hand, at its left (the user: over the hand and at the left,
    // whatever the screen); the hand under it.
    const meldLine = Math.max(30, H(meld));
    const meH = meldLine + 4 + H(hand) * (1 + LIFT) + 10;
    const topH = H(opp) + 12;
    const centerH = h - meH - topH - 6;
    const sideMin = Math.max(opp * 3.4 + 16, 128);
    // The rivers: the largest tile for which a layout fits, rivers across of 2 rows of 8 (3 rows of 7 when there is
    // room) and side rivers of 3 to 5 columns (ACROSS and SIDE tiles at full size), a gap of MID_ROWS between the two
    // across.
    let best: { river: number; acrossCols: number; acrossRows: number; sideCols: number } | null = null;
    for (let river = Math.min(hand * 0.9, h >= 900 ? 66 : 56); river >= 12 && !best; river -= 0.5) {
      for (const acrossRows of [3, 2]) {
        if ((2 * acrossRows + MID_ROWS) * H(river) + 14 > centerH) continue;
        const sideRows = Math.floor((centerH - 4) / H(river));
        const acrossCols = Math.max(7, Math.ceil(ACROSS / acrossRows));
        const sideCols = [3, 4, 5].find((c) => c * sideRows >= SIDE);
        if (!sideCols) continue;
        if ((acrossCols + 2 * sideCols) * river + 28 > w - 2 * sideMin) continue;
        best = { river, acrossCols, acrossRows, sideCols };
        break;
      }
    }
    const b = best ?? { river: 12, acrossCols: 8, acrossRows: 2, sideCols: 4 };
    const river = b.river;
    const riversW = (b.acrossCols + 2 * b.sideCols) * river + 28;
    // The side panels: room for a meld and some flowers a line, no more; the hand's row, then, says how wide the
    // table is.
    const handRowW = (17 + DRAWN_GAP + 0.3) * hand + 24;
    const tableW = Math.min(w, Math.max(handRowW, riversW + 2 * Math.max(sideMin, opp * 4.6 + 16)));
    const sideW = Math.max(sideMin, (tableW - riversW) / 2);
    const sideRows = Math.max(4, Math.floor((centerH - 4) / H(river)));
    return {
      wide, hand, handRow: 0, meld, river, opp, back: h < LOW ? 0 : Math.max(10, opp * 0.66), acrossCols: b.acrossCols, acrossRows: b.acrossRows,
      sideCols: b.sideCols, sideRows, sideW, topH, meH, boxW: 0, mid: MID_ROWS * H(river), tableW,
      font: Math.max(14, Math.min(18, opp * 0.46)), meldLine,
    };
  }
  // Tall (a phone upright): the others along the top, each in a column over their river (the previous at the left,
  // the one opposite in the middle, the next at the right) with OPP_LINES lines of melds and flowers; the rivers the
  // whole width; the player's melds and flowers (at the left) and name plate (at the right) in a line over the hand,
  // which wraps after HAND_ROW tiles (the drawn one apart at the end of the last row).
  // The rivers stay 26 px or more: on a short table the hand gets smaller (not below 30) to leave them the room.
  const tall = (hand: number, more = 0) => {
    const meld = Math.min(hand * 0.72, 40);
    const opp = Math.max(22, Math.min(hand * 0.6, 32));
    const meldLine = Math.max(30, H(meld));
    const meH = meldLine + 4 + H(hand) * (2 + ROW_GAP + LIFT) + 6;
    // The name plate, the column's padding (4 above and below) and the gap under the plate (4), then the lines.
    const own = 28 + 12 + OPP_LINES * H(opp) + (OPP_LINES - 1) * meldGaps(opp).row;
    const topH = Math.max(more, own);
    // A strip grown for shown hands keeps SHOWN_GAP clear of the rivers (they are centered in what is left).
    const centerH = h - meH - topH - 4;
    const riversH = centerH - (more > own ? SHOWN_GAP : 0);
    // The rivers: 3 columns at the sides, 5 across in 3 rows; as wide as the table allows, unless its height says less.
    let river = Math.min(hand * 0.9, (w - 24) / (acrossCols + 2 * sideCols));
    while (river > 16 && (2 * acrossRows + BOX_ROWS) * H(river) + 14 > riversH) river -= 0.5;
    return { hand, meld, opp, meH, topH, centerH: riversH, river, meldLine };
  };
  const sideCols = 3;
  const acrossCols = 5;
  const acrossRows = 3;
  let fit = tall(Math.max(30, Math.min(maxHand, (w - 10) / (HAND_ROW + DRAWN_GAP + 1))));
  while (fit.river < MIN_RIVER && fit.hand > 30) fit = tall(Math.max(30, fit.hand - 1));
  // A higher strip takes its room from the rivers alone: the hand stays as it was.
  if (top > fit.topH) fit = tall(fit.hand, top);
  const { hand, meld, opp, meH, topH, centerH, river, meldLine } = fit;
  const sideRows = Math.max(4, Math.floor((centerH - 4) / H(river)));
  const mid = Math.max(H(river) * BOX_ROWS, Math.min(H(river) * 2, centerH - 2 * acrossRows * H(river) - 14));
  return {
    wide, hand, handRow: HAND_ROW, meld, river, opp, back: 0, acrossCols, acrossRows, sideCols, sideRows, sideW: (w - 8) / 3,
    topH, meH, boxW: acrossCols * river, mid, tableW: w, font: 14, meldLine,
  };
}
