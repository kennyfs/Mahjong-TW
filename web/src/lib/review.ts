// A reviewed hand: how its decisions are graded, what is worth stopping at, and the words and figures the screens
// show for it (docs/analysis.md).
//
// A decision loses the best action's expected result less the played one's, in points (台). Losses under EQUAL are
// the network's own noise and count as nothing; the grades start at 0.2, 0.5 and 1 point for a typical payment of 4
// (the review's `unit` scales them to the hand's rules). The level of the settings says from which grade on a
// decision counts as a mistake.
import { actionText } from './actions';
import * as engine from './engine';
import { t } from './i18n.svelte';
import { records, type HandRecord, type ReviewSummary } from './records.svelte';
import type { Level } from './settings.svelte';
import { tileName, type Tile } from './tiles';
import type { Action, Analysis, Figures, Review, ReviewDecision } from './types';

export const EQUAL = 0.05;
/** 0: fine; 1: questionable; 2: a mistake; 3: a blunder. */
export type Grade = 0 | 1 | 2 | 3;
const STEPS = [0.2, 0.5, 1];
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

/** The marks of a decision's discards by tile. `figures`: every tile's cost and danger; else only the best and the played. */
export function tileMarks(a: Analysis, played: number, unit: number | undefined, figures: boolean): Map<Tile, TileMark> {
  const out = new Map<Tile, TileMark>();
  if (a.best === undefined) return out;
  a.actions.forEach((f, i) => {
    if (f.action.type !== 'discard' || !f.action.tile) return;
    const c = cost(a, i);
    const mine = i === played;
    const best = i === a.best;
    if (!figures && !mine && !best) return;
    const g = gradeOf(c, unit);
    const tag = best ? t('grade_best') : figures ? (c < EQUAL ? '≈' : tagCost(c)) : t('review_you');
    out.set(f.action.tile, {
      tag, tone: best ? 'best' : mine && g ? (`g${g}` as TileMark['tone']) : 'plain', played: mine,
      danger: figures ? dangerLevel(f.danger) : 0,
    });
  });
  return out;
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

/** Tile efficiency in words: "胡牌", "聽牌・進張 6", "1 向聽・進張 12". */
export function efficiencyText(f: Figures): string {
  if (f.shanten < 0) return t('analysis_win') + (f.win_tai >= 0 ? '・' + t('analysis_win_tai', f.win_tai) : '');
  return (f.shanten === 0 ? t('analysis_ready') : t('analysis_shanten', f.shanten)) + '・' + t('analysis_ukeire', f.ukeire);
}
