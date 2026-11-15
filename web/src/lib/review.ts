// A reviewed hand: how its decisions are graded, what is worth stopping at, and the words and figures the screens
// show for it (docs/analysis.md).
//
// A decision loses the best action's expected result less the played one's, in points (台). Losses under EQUAL are
// the network's own noise and count as nothing; the grades start at 0.1, 0.25 and 0.5 point for a typical payment of 4
// (the review's `unit` scales them to the hand's rules). The level of the settings says from which grade on a
// decision counts as a mistake.
import { actionText } from './actions';
import * as engine from './engine';
import { t } from './i18n.svelte';
import { records, type HandRecord, type ReviewSummary } from './records.svelte';
import { settings, type Level } from './settings.svelte';
import { KINDS, tileName, type Tile } from './tiles';
import type { Action, Analysis, Figures, Opponent, Review, ReviewDecision } from './types';

export const EQUAL = 0.05;
/** 0: fine; 1: questionable; 2: a mistake; 3: a blunder. */
export type Grade = 0 | 1 | 2 | 3;
// The grades' steps for a typical payment of 4: halved on 2026-11-12 (python/thresholds.py: greedy loses more than 0.1
// in 0.8 decisions a hand, more than 0.5 in 0.1; two networks' losses of the action played differ by more than 0.05
// in 5% of decisions, so 0.1 is clear of the networks' own noise).
const STEPS = [0.1, 0.25, 0.5];
const scale = (unit?: number) => (unit && unit > 0 ? unit / 4 : 1);

export function gradeOf(loss: number, unit?: number): Grade {
  const x = loss / scale(unit);
  return x >= STEPS[2] ? 3 : x >= STEPS[1] ? 2 : x >= STEPS[0] ? 1 : 0;
}
/** The smallest loss of a grade under these rules. */
export const gradeFrom = (g: Grade, unit?: number) => (g ? STEPS[g - 1] * scale(unit) : 0);
export const LEVEL_GRADE: Record<Level, Grade> = { beginner: 3, mid: 2, expert: 1 };
export const GRADE_NAME = ['grade_fine', 'grade_questionable', 'grade_mistake', 'grade_blunder'];
export const levelWord = (level: Level) => t(GRADE_NAME[LEVEL_GRADE[level]]);
/** The level's grade and worse, for a count of them all: "失誤以上" (a beginner's is the worst alone). */
export const levelOrWorse = (level: Level) => (LEVEL_GRADE[level] < 3 ? t('grade_or_worse', levelWord(level)) : levelWord(level));
/** Mistakes at a level in words, each grade by its own name from the worst down: "大失誤 1・疑問手 2" (`grades`: how
 *  many were questionable, mistakes, blunders); "沒有失誤" when there are none. */
export function mistakesText(grades: number[], level: Level): string {
  const out: string[] = [];
  for (let g = 3; g >= LEVEL_GRADE[level]; g--) if (grades[g - 1] > 0) out.push(t('review_mistakes', t(GRADE_NAME[g]), grades[g - 1]));
  return out.length ? out.join('・') : t('review_count_none', levelWord(level));
}

/** "+0.31", "−1.20": points with two decimals (one from 10 up). */
export function pts(x: number, signed = true): string {
  const a = Math.abs(x);
  const s = a >= 9.95 ? a.toFixed(1) : a.toFixed(2);
  if (!signed) return (x < 0 ? '−' : '') + s;
  return (x < -0.005 ? '−' : x > 0.005 ? '+' : '±') + s;
}
/** A loss on a tile's tag: "−0.3", "−12". */
export const tagCost = (x: number) => '−' + (x >= 9.95 ? x.toFixed(0) : x.toFixed(1));
/** "3.1%", "18%". */
export function pct(p: number): string {
  const x = p * 100;
  return (x < 9.95 ? x.toFixed(1) : x.toFixed(0)) + '%';
}

/** How dangerous a discard is now: 0 under 1%, then up to 5%, up to 15%, and beyond. */
export const dangerLevel = (p: number | undefined): 0 | 1 | 2 | 3 => (p === undefined || p < 0.01 ? 0 : p < 0.05 ? 1 : p < 0.15 ? 2 : 3);
/** How likely an opponent is ready, for its color: 0 under 10%, then up to 30%, up to 60%, and beyond. */
export const readyLevel = (p: number): 0 | 1 | 2 | 3 => (p < 0.1 ? 0 : p < 0.3 ? 1 : p < 0.6 ? 2 : 3);

/** Tiles in few words: ["3m", "6m", "2p", "5z"] -> "三六萬二筒白". */
export function tilesShort(tiles: Tile[]): string {
  let out = '';
  for (let i = 0; i < tiles.length; i++) {
    const x = tiles[i];
    const name = tileName(x);
    const sameSuitNext = i + 1 < tiles.length && tiles[i + 1][1] === x[1] && 'mps'.includes(x[1]);
    out += sameSuitNext ? name[0] : name;
  }
  return out;
}

const cost = (a: Analysis, i: number) => (a.actions[a.best ?? 0].q ?? 0) - (a.actions[i].q ?? 0);
export const costOf = cost;
const isWin = (x: Action) => x.type === 'ron' || x.type === 'tsumo';
const isCall = (x: Action) => ['pon', 'chi', 'kan', 'ankan', 'kakan'].includes(x.type);

/**
 * A best action that is not the obvious one, when it is clearly better than the obvious one: a win passed up, a
 * discard that gives up a step towards ready (to fold, or for a slower hand worth more), a call left alone. `alt`: the obvious
 * action's index, `margin`: how much the best is worth over it.
 */
export interface Notable { kind: 'decline_win' | 'fold' | 'slow' | 'no_call'; alt: number; margin: number }

export function notableOf(a: Analysis, unit?: number): Notable | null {
  if (a.best === undefined) return null;
  const best = a.actions[a.best];
  const least = gradeFrom(1, unit);
  const top = (pick: (f: Figures, i: number) => boolean) => {
    let at = -1;
    a.actions.forEach((f, i) => { if (pick(f, i) && (at < 0 || (f.q ?? 0) > (a.actions[at].q ?? 0))) at = i; });
    return at;
  };
  const out = (kind: Notable['kind'], alt: number): Notable | null =>
    alt >= 0 && cost(a, alt) >= least ? { kind, alt, margin: cost(a, alt) } : null;
  if (!isWin(best.action)) {
    const win = top((f) => isWin(f.action));
    if (win >= 0) return out('decline_win', win);
  }
  if (best.action.type === 'discard') {
    const fewest = Math.min(...a.actions.filter((f) => f.action.type === 'discard').map((f) => f.shanten));
    if (best.shanten > fewest) {
      // To fold when most of the gain is in what the others win; else a slower hand that is worth more.
      const alt = top((f) => f.action.type === 'discard' && f.shanten === fewest);
      const b = best.parts, p = alt >= 0 ? a.actions[alt].parts : undefined;
      const defence = b && p ? b[1] - p[1] + (b[2] - p[2]) : 0;
      return out(defence >= 0.5 * (alt >= 0 ? cost(a, alt) : 0) ? 'fold' : 'slow', alt);
    }
  }
  if (best.action.type === 'pass') return out('no_call', top((f) => isCall(f.action) && f.shanten < best.shanten));
  return null;
}

export function notableText(a: Analysis, n: Notable): string {
  const alt = a.actions[n.alt].action;
  const what = n.kind === 'decline_win' ? t('note_alt_win') : n.kind === 'no_call' ? t('note_alt_call', actionText(alt))
    : t('note_alt_efficient', alt.tile ? tileName(alt.tile) : '');
  return `${t('note_' + n.kind)}（${t('note_margin', what, pts(n.margin, false))}）`;
}

/** Why `i` is worse than the best, from the parts of the two values: what it wins less, deals in more, is drawn on more. */
export function reasonOf(a: Analysis, i: number): string {
  if (a.best === undefined || i === a.best) return '';
  const b = a.actions[a.best].parts, p = a.actions[i].parts;
  if (!b || !p) return '';
  const total = cost(a, i);
  const least = Math.max(EQUAL, total * 0.2);
  const names: [string, string][] = [['reason_win_less', 'reason_win_more'], ['reason_deal_more', 'reason_deal_less'], ['reason_tsumo_more', 'reason_tsumo_less']];
  const worse: string[] = [], better: string[] = [];
  for (let k = 0; k < 3; k++) {
    const d = b[k] - p[k];
    if (d >= least) worse.push(t(names[k][0], pts(d, false)));
    else if (-d >= least) better.push(t(names[k][1], pts(-d, false)));
  }
  if (!worse.length) return '';
  return worse.join('、') + (better.length ? t('reason_but') + better.join('、') : '');
}

/** What a decision was about, for the statistics: a win to take or not, a call, a discard that deals in, a tile. */
export type Kind = 'win' | 'call' | 'danger' | 'tile';
export const KINDS_OF_LOSS: Kind[] = ['tile', 'danger', 'call', 'win'];
export function kindOf(d: ReviewDecision): Kind {
  const a = d.analysis;
  const acts = a.actions.map((f) => f.action);
  if (acts.some(isWin)) return 'win';
  const played = d.played >= 0 ? acts[d.played] : null;
  const best = a.best !== undefined ? acts[a.best] : null;
  if (acts.some((x) => x.type === 'pass') || (played && isCall(played)) || (best && isCall(best))) return 'call';
  if (a.best !== undefined && d.played >= 0) {
    const b = a.actions[a.best].parts, p = a.actions[d.played].parts;
    if (b && p && b[1] - p[1] >= 0.5 * (d.loss ?? 0)) return 'danger';
  }
  return 'tile';
}

export interface Key { step: number; loss: number; grade: Grade; notable: Notable | null; found: boolean }
/** A review's decisions as the graph and the steps see them. `found`: a notable best action that was played. */
export function keysOf(r: Review): Key[] {
  return r.decisions.map((d) => {
    const loss = Math.max(0, d.loss ?? 0);
    const notable = notableOf(d.analysis, r.unit);
    return { step: d.step, loss, grade: gradeOf(loss, r.unit), notable, found: !!notable && loss < EQUAL };
  });
}
/** Whether the review stops here at this level: a mistake of the level, or a best action that is not the obvious one. */
export const isKey = (k: Key, level: Level) => k.grade >= LEVEL_GRADE[level] || !!k.notable;

export function summarize(r: Review): ReviewSummary {
  const grades = [0, 0, 0];
  const kinds: Record<Kind, number> = { tile: 0, danger: 0, call: 0, win: 0 };
  for (const d of r.decisions) {
    const loss = Math.max(0, d.loss ?? 0);
    const g = gradeOf(loss, r.unit);
    if (g) grades[g - 1]++;
    if (loss >= EQUAL) kinds[kindOf(d)] += loss;
  }
  const r2 = (x: number) => Math.round(x * 100) / 100;
  return {
    m: engine.MODEL_ID, first: r2(r.first_value ?? 0), loss: r2(r.loss ?? 0), luck: r2(r.luck ?? 0), unit: r2(r.unit ?? 4),
    n: r.decisions.length, grades, kinds: KINDS_OF_LOSS.map((k) => r2(kinds[k])),
  };
}
/** Mistakes of a summary at a level: the decisions of its grade and worse. */
export const mistakesOf = (s: ReviewSummary, level: Level) => s.grades.slice(LEVEL_GRADE[level] - 1).reduce((a, b) => a + b, 0);

const cache = new Map<string, Promise<Review>>();
/** A record's review by the network, kept while the page is open. */
export function reviewOf(rec: HandRecord): Promise<Review> {
  let r = cache.get(rec.id);
  if (!r) {
    r = engine.review(rec.game, rec.me);
    cache.set(rec.id, r);
    r.catch(() => cache.delete(rec.id));
    if (cache.size > 40) cache.delete(cache.keys().next().value!);
  }
  return r;
}

/** Reviews a record and keeps the summary with it, unless this network's is there. Failures are left for a later try. */
export async function keepSummary(rec: HandRecord): Promise<void> {
  if (rec.rev?.m === engine.MODEL_ID) return;
  try {
    const r = await reviewOf(rec);
    if (r.first_value !== undefined) records.setReview(rec.id, summarize(r));
  } catch { /* no network: no summary */ }
}

/** What a tile of the hand shows at a decision: a tag over it, its tone, whether it is the tile played, its danger. */
export interface TileMark { tag: string; tone: 'best' | 'plain' | 'g1' | 'g2' | 'g3'; played: boolean; danger: 0 | 1 | 2 | 3 }

/** The marks of a decision's discards by tile: what each costs against the best, and its danger. */
export function tileMarks(a: Analysis, played: number, unit: number | undefined): Map<Tile, TileMark> {
  const out = new Map<Tile, TileMark>();
  if (a.best === undefined) return out;
  a.actions.forEach((f, i) => {
    if (f.action.type !== 'discard' || !f.action.tile) return;
    const c = cost(a, i);
    const mine = i === played;
    const best = i === a.best;
    const g = gradeOf(c, unit);
    out.set(f.action.tile, {
      tag: best ? t('grade_best') : c < EQUAL ? '≈' : tagCost(c),
      tone: best ? 'best' : mine && g ? (`g${g}` as TileMark['tone']) : 'plain',
      played: mine, danger: dangerLevel(f.danger),
    });
  });
  return out;
}

/** A name plate's few words (level 0 to 3: how likely the player is ready, for its color); `short`: the same in fewer,
 *  where the plate has no room (lib/shorten.ts); `opp`: the opponent's figures, shown when the words are tapped
 *  (SeatBadge). */
export interface SeatNote { text: string; level: number; short?: string; opp?: Opponent }

/** Ready at least this likely: the plate also names the tile the opponent most likely waits on. */
const SAY_WAIT = 0.3;

/** A name plate's note at a decision: how likely the opponent is ready (from 10%), and when that is likely the tile
 *  it most likely waits on (the discard most likely to deal in to it, from 3%): 「聽 40%・可能聽五筒」, 「聽 40%」 where
 *  that has no room. A tap on it shows the rest (SeatBadge: one away, the tiles likeliest to deal in to it). */
export function opponentNote(o: Opponent): SeatNote | null {
  const r = o.ready[0];
  if (r < 0.1) return null;
  const level = readyLevel(r);
  const short = t('opp_ready', pct(r));
  const k = dangerTiles(o)[0];
  if (r >= SAY_WAIT && k) return { text: t('opp_ready_wait', pct(r), tilesShort([k.tile])), level, short, opp: o };
  return { text: short, level, opp: o };
}

/** The discards most likely to deal in to an opponent (from 3%, the five likeliest). */
export const dangerTiles = (o: { danger: number[]; danger_tai: number[] }) =>
  o.danger.map((p, k) => ({ p, tile: KINDS[k], tai: o.danger_tai[k] })).filter((x) => x.p >= 0.03).sort((a, b) => b.p - a.p).slice(0, 5);

/** The notes of a decision's opponents, by seat. */
export function opponentNotes(a: Analysis | null): (SeatNote | null)[] | null {
  if (!a?.opponents) return null;
  const out: (SeatNote | null)[] = [null, null, null, null];
  for (const o of a.opponents) out[o.seat] = opponentNote(o);
  return out;
}

/** What a choice's button shows at a decision (a branch's, a practice hand's with its hints): what the choice costs
 *  against the best, in its grade's tone; whether it was the one made there. */
export interface ChoiceMark { tag: string; tone: TileMark['tone']; played: boolean }

/** The marks of a decision's choices that are not discards, by the action as the engine lists it as legal. */
export function choiceMarks(a: Analysis, played: number, unit: number | undefined): (x: Action) => ChoiceMark | null {
  return (x) => {
    if (a.best === undefined) return null;
    let i = a.actions.findIndex((f) => f.action.type === x.type && (f.action.tile ?? null) === (x.tile ?? null));
    if (i < 0) i = a.actions.findIndex((f) => f.action.type === x.type);
    if (i < 0) return null;
    const c = cost(a, i);
    const g = gradeOf(c, unit);
    return {
      tag: i === a.best ? t('grade_best') : c < EQUAL ? '≈' : tagCost(c),
      tone: i === a.best ? 'best' : g ? (`g${g}` as TileMark['tone']) : 'plain',
      played: i === played,
    };
  };
}

/** An action's figures in words, for a line: [label, value]. */
export function figureItems(f: Figures, c: number | null, names: string[] | null): [string, string][] {
  const out: [string, string][] = [];
  if (f.q === undefined) return out;
  out.push([t('fig_value'), pts(f.q)]);
  if (c !== null && c >= EQUAL) out.push([t('fig_cost'), pts(-c)]);
  out.push([t('fig_win'), pct(f.win ?? 0) + (f.win && f.win >= 0.005 ? `（${t('fig_tsumo')} ${pct(f.tsumo ?? 0)}・${t('fig_tai', (f.tai ?? 0).toFixed(1))}）` : '')]);
  out.push([t('fig_deal_in'), pct(f.deal_in ?? 0)]);
  out.push([t('fig_others_tsumo'), pct(f.others_tsumo ?? 0)]);
  if (f.danger !== undefined) {
    let who = '';
    if (names && f.danger >= 0.01 && f.danger_each) {
      const top = f.danger_each.indexOf(Math.max(...f.danger_each));
      who = `（${names[top]} ${pct(f.danger_each[top])}・${t('fig_tai', (f.danger_tai?.[top] ?? 0).toFixed(1))}）`;
    }
    out.push([t('fig_danger'), pct(f.danger) + who]);
  }
  return out;
}

/**
 * An action's figures as cells of fixed columns (FIGURE_KEYS), so that a list's rows, and the figures of each tile
 * over the hand, line up whatever an action has: a cell an action has not is empty and keeps its room (Figures.svelte
 * makes each column as wide as its widest cell). `tone`: 1 (green) where the action is better than the best one by
 * more than NOTABLE (more wins, fewer deal-ins...), -1 (red) where it is worse by more: the figure alone takes the color
 * (`after`: words after it that keep the line's color, as the label does).
 */
export interface FigureCell { key: FigureKey; label: string; value: string; tone: -1 | 0 | 1; after?: string }
export const FIGURE_KEYS = ['q', 'cost', 'win', 'tsumo', 'deal', 'others', 'danger', 'who', 'eff'] as const;
export type FigureKey = (typeof FIGURE_KEYS)[number];
/** The gap to the best action worth a color, by figure: the 75th percentile of |best - k-th| for the 2nd to 6th action
 *  (python/thresholds.py gaps; network 211 of s2, 3,000 hands under simple1 and simple2, alike). */
const NOTABLE: Partial<Record<FigureKey, number>> = { win: 0.08, tsumo: 0.03, deal: 0.03, others: 0.03, danger: 0.03 };
/** Whether more is better. */
const MORE_IS_BETTER: Partial<Record<FigureKey, boolean>> = { win: true, tsumo: true, deal: false, others: false, danger: false };

function toneOf(key: FigureKey, x: number | undefined, b: number | undefined): -1 | 0 | 1 {
  const gap = NOTABLE[key];
  if (gap === undefined || x === undefined || b === undefined) return 0;
  const d = (x - b) * (MORE_IS_BETTER[key] ? 1 : -1);
  return d > gap ? 1 : d < -gap ? -1 : 0;
}

/** `c`: the action's cost (null: no 差 cell); `best`: the best action's figures, to color against. */
export function figureCells(f: Figures, c: number | null, names: string[] | null, best: Figures | null): FigureCell[] {
  const cell = (key: FigureKey, label: string, value: string, x?: number, b?: number, after = ''): FigureCell =>
    ({ key, label, value, tone: best && best !== f ? toneOf(key, x, b) : 0, after });
  const out: FigureCell[] = [];
  if (f.q !== undefined) {
    out.push(cell('q', t('fig_value'), pts(f.q)));
    out.push(cell('cost', c !== null && c >= EQUAL ? t('fig_cost') : '', c !== null && c >= EQUAL ? pts(-c) : ''));
    const win = f.win ?? 0;
    out.push(cell('win', t('fig_win'), pct(win), win, best?.win));
    // （自摸 3.7%・0.9 台）: only the chance is colored (it is what is compared), not the words or the brackets.
    const some = win >= 0.005;
    out.push(cell('tsumo', some ? `（${t('fig_tsumo')}` : '', some ? pct(f.tsumo ?? 0) : '', f.tsumo, best?.tsumo,
      some ? `・${t('fig_tai', (f.tai ?? 0).toFixed(1))}）` : ''));
    out.push(cell('deal', t('fig_deal_in'), pct(f.deal_in ?? 0), f.deal_in, best?.deal_in));
    out.push(cell('others', t('fig_others_tsumo'), pct(f.others_tsumo ?? 0), f.others_tsumo, best?.others_tsumo));
    out.push(cell('danger', f.danger !== undefined ? t('fig_danger') : '', f.danger !== undefined ? pct(f.danger) : '', f.danger, best?.danger));
    let who = '';
    if (names && f.danger !== undefined && f.danger >= 0.01 && f.danger_each) {
      const top = f.danger_each.indexOf(Math.max(...f.danger_each));
      who = `（${names[top]} ${pct(f.danger_each[top])}・${t('fig_tai', (f.danger_tai?.[top] ?? 0).toFixed(1))}）`;
    }
    out.push(cell('who', '', who));
  }
  out.push(cell('eff', '', efficiencyText(f)));
  return out;
}

/** Tile efficiency in words: "胡牌", "聽牌・進張 6", "1 向聽・進張 12". */
export function efficiencyText(f: Figures): string {
  if (f.shanten < 0) return t('analysis_win') + (f.win_tai >= 0 ? '・' + t('analysis_win_tai', f.win_tai) : '');
  return (f.shanten === 0 ? t('analysis_ready') : t('analysis_shanten', f.shanten)) + '・' + t('analysis_ukeire', f.ukeire);
}
