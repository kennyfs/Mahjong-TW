// A decision of a review explained by rules, without a language model (解說; the app's Explain.kt): which lesson it
// is, the signs on the table that call for it, and a rule of thumb, with figures from self-play (docs/explain.md).
// What it compares: the action played against the best, when it lost; when the best was played and tile efficiency
// would have lost, tile efficiency's choice against the best ("well played").
import { actionText } from './actions';
import { t, ta } from './i18n.svelte';
import { gradeFrom } from './review';
import { specValues } from './rules';
import { parseTiles, tileName, type Tile } from './tiles';
import type { Action, Analysis, Figures, PlayerPos, View } from './types';

// From 200,000 hands of the shipped network playing itself (simple2, see; docs/explain.md section 3). THREAT: the
// share of opponents truly ready by "discards bucket,melds,two or more 摸切 at the end" (discards: up to 2, 3-5, 6-8,
// 9-11, 12 and more; melds up to 4). SAFETY: a tile's chance of dealing in to a ready opponent by "what it is to it,
// rank, copies seen on the table besides the seat's own (up to 3)".
const THREAT: Record<string, number> = {"0,0,0":0.0005,"0,1,0":0.0034,"1,0,0":0.0128,"1,1,0":0.0376,"2,1,0":0.1398,"2,0,0":0.0734,"2,2,0":0.2623,"1,1,1":0.1163,"1,2,0":0.108,"2,3,0":0.4781,"1,3,0":0.2893,"2,1,1":0.3282,"1,2,1":0.2392,"0,2,0":0.0177,"2,2,1":0.4655,"3,2,0":0.3898,"3,1,0":0.2485,"3,0,0":0.1568,"3,3,0":0.5912,"3,1,1":0.5641,"2,0,1":0.2429,"3,2,1":0.6731,"3,3,1":0.7967,"1,0,1":0.0614,"4,2,0":0.3665,"4,2,1":0.84,"4,1,0":0.2462,"2,4,0":0.8232,"3,4,0":0.8774,"4,3,1":0.9003,"4,4,0":0.7736,"4,3,0":0.5427,"4,1,1":0.7823,"4,0,0":0.1694,"3,0,1":0.4842,"4,0,1":0.7323,"2,3,1":0.6618,"1,3,1":0.455,"3,4,1":0.9411,"4,4,1":0.9678,"0,0,1":0.002,"1,4,0":0.6906,"2,4,1":0.8854};
const SAFETY: Record<string, number> = {"x,37,0":0.1221,"x,456,0":0.1326,"h,456,1":0.0587,"x,456,1":0.0897,"x,28,0":0.1137,"s,19,0":0.0445,"s,37,0":0.0775,"x,19,2":0.0289,"x,19,0":0.0912,"x,z,0":0.0344,"x,z,1":0.0053,"g,28,1":0.0083,"x,37,1":0.0744,"g,456,1":0.0101,"g,z,2":0.0,"x,19,1":0.0525,"g,28,2":0.0055,"x,28,1":0.0676,"g,z,1":0.0028,"g,19,1":0.0087,"s,456,0":0.0573,"h,456,0":0.1003,"s,19,3":0.0017,"s,37,2":0.0127,"g,456,2":0.006,"s,19,1":0.0085,"x,z,2":0.0001,"x,37,2":0.0395,"h,456,2":0.0277,"g,37,1":0.0055,"x,28,2":0.0342,"g,37,2":0.0041,"x,z,3":0.0,"s,37,1":0.0319,"x,456,2":0.0527,"g,28,3":0.0035,"s,28,0":0.0604,"g,z,3":0.0,"x,37,3":0.0268,"g,37,3":0.0023,"s,28,1":0.022,"g,19,2":0.0062,"s,28,2":0.0058,"g,19,3":0.0041,"x,19,3":0.0339,"x,28,3":0.0312,"s,456,1":0.024,"s,37,3":0.0045,"h,456,3":0.0256,"g,456,3":0.0044,"s,456,3":0.0041,"x,456,3":0.0482,"s,456,2":0.0105,"s,19,2":0.0034,"s,28,3":0.0022};

/** A rank's class: z (an honor), 19, 28, 37, 456. */
export function rankClass(k: number): string {
  if (k >= 27) return 'z';
  const r = k % 9;
  return r === 0 || r === 8 ? '19' : r === 1 || r === 7 ? '28' : r === 2 || r === 6 ? '37' : '456';
}

/** A tile to an opponent that discarded `disc` (kinds): g its own discard (現物), s suji (筋), h half suji, x other. */
export function tileCat(k: number, disc: Set<number>): 'g' | 's' | 'h' | 'x' {
  if (disc.has(k)) return 'g';
  if (k >= 27) return 'x';
  const r = k % 9;
  const lo = r >= 3 && disc.has(k - 3), hi = r <= 5 && disc.has(k + 3);
  if (r <= 2) return hi ? 's' : 'x';
  if (r >= 6) return lo ? 's' : 'x';
  return lo && hi ? 's' : lo || hi ? 'h' : 'x';
}

/** The share ready of opponents with these signs. */
export function threatOf(discards: number, melds: number, tsumogiriTail: number): number | undefined {
  const nd = discards <= 2 ? 0 : discards <= 5 ? 1 : discards <= 8 ? 2 : discards <= 11 ? 3 : 4;
  return THREAT[`${nd},${Math.min(melds, 4)},${tsumogiriTail >= 2 ? 1 : 0}`];
}

export const kindOf = (t: Tile) => 'mpsz'.indexOf(t[1]) * 9 + Number(t[0]) - 1;
const counts = (tiles: Tile[]) => {
  const c = new Array(34).fill(0);
  for (const x of tiles) if (x[1] !== 'f') c[kindOf(x)]++;
  return c;
};

// The most complete sets, then partial ones (pairs, two of a chow), of one suit's counts (study.cpp's).
function bestBlocks(c: number[], i: number, sets: number, parts: number, best: [number, number]) {
  while (i < 9 && c[i] === 0) i++;
  if (i >= 9) {
    if (sets > best[0] || (sets === best[0] && parts > best[1])) { best[0] = sets; best[1] = parts; }
    return;
  }
  if (c[i] >= 3) { c[i] -= 3; bestBlocks(c, i, sets + 1, parts, best); c[i] += 3; }
  if (i <= 6 && c[i + 1] > 0 && c[i + 2] > 0) { c[i]--; c[i + 1]--; c[i + 2]--; bestBlocks(c, i, sets + 1, parts, best); c[i]++; c[i + 1]++; c[i + 2]++; }
  if (c[i] >= 2) { c[i] -= 2; bestBlocks(c, i, sets, parts + 1, best); c[i] += 2; }
  if (i <= 7 && c[i + 1] > 0) { c[i]--; c[i + 1]--; bestBlocks(c, i, sets, parts + 1, best); c[i]++; c[i + 1]++; }
  if (i <= 6 && c[i + 2] > 0) { c[i]--; c[i + 2]--; bestBlocks(c, i, sets, parts + 1, best); c[i]++; c[i + 2]++; }
  c[i]--; bestBlocks(c, i, sets, parts, best); c[i]++;
}
function blocks(h: number[]): [number, number] {
  let sets = 0, parts = 0;
  for (let s = 0; s < 3; s++) {
    const best: [number, number] = [-1, -1];
    bestBlocks(h.slice(s * 9, s * 9 + 9), 0, 0, 0, best);
    sets += best[0]; parts += best[1];
  }
  for (let k = 27; k < 34; k++) { if (h[k] >= 3) sets++; else if (h[k] === 2) parts++; }
  return [sets, parts];
}

/** What a discard of kind k takes from the hand: zpair, zlone (an honor pair, a lone honor), set (breaks a set),
 *  pair, penchan, kanchan, ryanmen (breaks a partial set), lone (an isolated number tile), extra. */
export function roleOf(c: number[], k: number): string {
  if (k >= 27) return c[k] === 1 ? 'zlone' : c[k] === 2 ? 'zpair' : 'zset';
  const b0 = blocks(c);
  c[k]--;
  const b1 = blocks(c);
  c[k]++;
  if (b1[0] < b0[0]) return 'set';
  if (c[k] >= 2) return 'pair';
  const r = k % 9;
  const l1 = r >= 1 && c[k - 1] > 0, r1 = r <= 7 && c[k + 1] > 0, l2 = r >= 2 && c[k - 2] > 0, r2 = r <= 6 && c[k + 2] > 0;
  if (b1[1] < b0[1]) {
    if (l1 || r1) {
      const edge = (r === 0 && r1) || (r === 8 && l1) || (r === 1 && l1 && !r1) || (r === 7 && r1 && !l1);
      return edge ? 'penchan' : 'ryanmen';
    }
    return 'kanchan';
  }
  return l1 || r1 || l2 || r2 ? 'extra' : 'lone';
}

const WEAK = ['shape', 'wait-other', 'other', 'call-other'];
const isWin = (a: Action) => a.type === 'ron' || a.type === 'tsumo';
const isCall = (a: Action) => a.type === 'chi' || a.type === 'pon' || a.type === 'kan';

/** Tile efficiency's choice (python greedy, study.cpp): a win; a kong whenever it may; a call only when it lowers the
 *  shanten; else the discard with the fewest steps from ready and the most improving tiles, honors then terminals first
 *  on a tie. */
export function greedyOf(a: Analysis): number {
  const xs = a.actions;
  const win = xs.findIndex((f) => isWin(f.action));
  if (win >= 0) return win;
  const pass = xs.findIndex((f) => f.action.type === 'pass');
  if (pass >= 0) {
    let at = pass;
    xs.forEach((f, i) => { if (isCall(f.action) && f.shanten < xs[at].shanten) at = i; });
    return at;
  }
  const kong = xs.findIndex((f) => f.action.type === 'ankan' || f.action.type === 'kakan');
  if (kong >= 0) return kong;
  const order = (f: Figures) => {
    const k = f.action.tile ? kindOf(f.action.tile) : 0;
    return k >= 27 ? 0 : k % 9 === 0 || k % 9 === 8 ? 1 : 2;
  };
  let at = -1;
  xs.forEach((f, i) => {
    if (f.action.type !== 'discard') return;
    if (at < 0) { at = i; return; }
    const b = xs[at];
    if (f.shanten < b.shanten || (f.shanten === b.shanten && (f.ukeire > b.ukeire || (f.ukeire === b.ukeire && order(f) < order(b))))) at = i;
  });
  return at;
}

export interface Explanation {
  /** The lesson, for tests and the record (e.g. 'defend-step'). */
  key: string;
  /** The best was played: what is explained is tile efficiency's choice, `alt`. */
  found: boolean;
  title: string;
  /** "You: … · Better: …". */
  choice: string;
  /** Structured choice labels for chip display. */
  you: string;
  alt: string;
  /** The opponent's signs, when the lesson is about defense. */
  signs?: string;
  text: string[];
  rule?: string;
}

/** "約 4 成", "about 40%"; "約 3%"; "不到 1%". */
export function chance(p: number): string {
  if (p >= 0.195) return t('explain_tenths', Math.round(p * 10));
  if (p >= 0.01) return t('explain_about', Math.round(p * 100));
  return t('explain_under_one');
}

/** Exact percent for deal-in rates: "約 12%", "約 9%", "不到 1%". */
export function percent(p: number): string {
  if (p >= 0.01) return t('explain_about', Math.round(p * 100));
  return t('explain_under_one');
}
/** 已經聽牌, 一向聽, 兩向聽, …; ready, 1 away, …. */
export const steps = (sh: number) => ta('explain_steps')[Math.max(0, Math.min(sh, 7))];
const SHAPE: Record<string, string> = {
  kanchan: 'explain_shape_kanchan', penchan: 'explain_shape_penchan', ryanmen: 'explain_shape_ryanmen',
  lone: 'explain_shape_lone', zlone: 'explain_shape_lone', extra: 'explain_shape_extra',
};
const waitsLeft = (f: Figures) => f.waits.reduce((s, w) => s + w.left, 0);
const waitTai = (f: Figures) => f.waits.reduce((s, w) => Math.max(s, w.ron_tai, w.tsumo_tai), 0);

interface Opp { rel: number; ready: number; p: PlayerPos; disc: Set<number>; passed: Set<number>; tail: number; justCalled: boolean }

/**
 * The explanation of a reviewed decision of `view`'s seat, or null when there is nothing to say: the played action
 * lost less than a questionable decision and tile efficiency would not have lost more either. `view`: the seat's view
 * at the decision, its events from the start.
 */
export function explain(a: Analysis, played: number, view: View, unit?: number): Explanation | null {
  if (a.source !== 'network' || a.best === undefined || played < 0 || !a.actions[played]) return null;
  const q = (i: number) => a.actions[i].q ?? 0;
  const best = a.best, least = gradeFrom(1, unit), big = gradeFrom(2, unit);
  if (q(best) - q(played) >= least) return lesson(a, played, best, view, false, q(best) - q(played) >= big);
  // Well played: what was played against tile efficiency's choice, when that would have lost as much; said only where
  // there is a lesson in it.
  const g = greedyOf(a);
  if (g < 0 || g === played || q(played) - q(g) < least) return null;
  const e = lesson(a, g, played, view, true, false);
  return WEAK.includes(e.key) ? null : e;
}

function lesson(an: Analysis, p: number, b: number, view: View, found: boolean, big: boolean): Explanation {
  const pos = view.position, me = pos.me, xs = an.actions, ap = xs[p], ab = xs[b];
  const hand = parseTiles(pos.hand);
  if (view.phase === 'self' && pos.drawn && hand.length % 3 !== 2) hand.push(pos.drawn);
  const c = counts(hand);
  // What the seat sees: its hand, every discard not taken, the melds' tiles (another's concealed kong hidden).
  const vis = c.slice();
  for (const pl of pos.players) {
    for (const d of pl.discards) if (!d.called) vis[kindOf(d.tile)]++;
    for (const m of pl.melds) for (const x of m.tiles ?? []) vis[kindOf(x)]++;
  }
  const unseen = (k: number) => Math.max(0, 4 - vis[k]);
  const values = specValues(view.rules);
  const roundWind = pos.setup.round;
  const seatWind = (me - pos.setup.dealer + 4) % 4;
  const honorTai = (k: number) => {
    if (k >= 31) return (values.dragon ?? 0) > 0;
    if (k >= 27) {
      if ((values.any_wind ?? 0) > 0) return true;
      const wd = k - 27;
      return (wd === roundWind && (values.round_wind ?? 0) > 0) || (wd === seatWind && (values.seat_wind ?? 0) > 0);
    }
    return false;
  };
  const w = pos.wall_left;
  const name = (f: Figures) => (f.action.tile ? tileName(f.action.tile) : '');
  const kOf = (f: Figures) => (f.action.tile ? kindOf(f.action.tile) : -1);
  const youAct = actionText(found ? ab.action : ap.action);
  const altAct = actionText(found ? ap.action : ab.action);
  const out = (key: string, title: string, text: string[], rule?: string, signs?: string): Explanation => ({
    key, found, title: t(title), text, rule: rule ? t(rule) : undefined, signs,
    choice: t(found ? 'explain_choice_found' : 'explain_choice', youAct, altAct),
    you: youAct, alt: altAct,
  });
  const T = ap.action.type, B = ab.action.type;

  // Wins.
  if (T === 'ron' && B === 'pass') return out('win-wait', 'explain_t_win_wait', [t('explain_win_wait', w, waitsLeft(ab))], 'explain_rule_win_wait');
  if (isWin(ab.action) && !isWin(ap.action)) return out('win-take', 'explain_t_win_take', [t('explain_win_take')]);
  // Kongs.
  if ((T === 'ankan' || T === 'kakan') && B === 'discard' && ap.shanten > ab.shanten)
    return out('kong-breaks', 'explain_t_kong', [t('explain_kong_breaks', name(ap), steps(ab.shanten), steps(ap.shanten))], 'explain_rule_kong');
  // Calls.
  if (view.phase !== 'self') {
    const own = pos.players[me];
    const closed = own.melds.every((m) => m.type === 'ankan');
    const pass = xs.find((f) => f.action.type === 'pass');
    const sh = pass?.shanten ?? ab.shanten;
    const word = t(T === 'chi' ? 'act_chi' : T === 'pon' ? 'act_pon' : 'act_kan').toLowerCase();
    if (isCall(ap.action) && B === 'pass') {
      if (w <= 25) return out('call-late', 'explain_t_call_late', [t('explain_call_late', w, word)], 'explain_rule_call_late');
      if (closed && sh >= 2) {
        const lose = (values.menqing ?? 0) > 0 ? t('explain_lose_menqing', values.menqing, values.menqing + (values.zimo ?? 0) + (values.menqing_zimo ?? 0)) : t('explain_lose_tiles');
        // (A call that leaves the hand as far: no faster at all.)
        return out('call-closed', 'explain_t_call_closed', [t(ap.shanten >= sh ? 'explain_call_closed_same' : 'explain_call_closed', sh, word, lose)], 'explain_rule_call_closed');
      }
      // No faster: the network's chance of winning drops (about 5 points in its self-play); one step faster but not worth
      // it: its chance of dealing in rises (about 3) for no more wins (docs/explain.md section 4).
      return out('call-no', 'explain_t_call_no', [ap.shanten >= ab.shanten ? t('explain_call_no_same', word, steps(ap.shanten))
        : t('explain_call_no', word, steps(ap.shanten), steps(ab.shanten))]);
    }
    const claimed = pos.claim?.tile ?? ab.action.tile ?? null;
    if (T === 'pass' && B === 'pon' && claimed) {
      const k = kindOf(claimed);
      if (k >= 27) return out('pon-honor', 'explain_t_pon_honor', [t('explain_pon_honor', tileName(claimed), honorTai(k) ? t('explain_pon_tai') : '')], 'explain_rule_pon_honor');
      if (ab.shanten < sh) return out('call-yes', 'explain_t_call_yes', [t('explain_call_yes', actionText(ab.action), steps(ab.shanten))]);
      if (pass && ab.ukeire > pass.ukeire) return out('pon-same', 'explain_t_pon_same', [t('explain_pon_same', tileName(claimed), pass.ukeire, ab.ukeire)]);
      return out('pon-same', 'explain_t_pon_same', [t('explain_pon_same2', tileName(claimed))]);
    }
    if (T === 'pass' && isCall(ab.action) && ab.shanten < sh) return out('call-yes', 'explain_t_call_yes', [t('explain_call_yes', actionText(ab.action), steps(ab.shanten))]);
    return out('call-other', 'explain_t_other', [t('explain_other', actionText(ab.action), actionText(ap.action))]);
  }
  if (T !== 'discard' || B !== 'discard') return out('other', 'explain_t_other', [t('explain_other', actionText(ab.action), actionText(ap.action))]);

  // Discards: the opponent most likely ready, by the network, and what the seat can see of it.
  const opps: Opp[] = [1, 2, 3].map((r) => {
    const s = (me + r) % 4, pl = pos.players[s];
    const o = an.opponents?.find((x) => x.seat === s) ?? an.opponents?.[r - 1];
    let tail = 0;
    for (let i = pl.discards.length - 1; i >= 0; i--) {
      if (pl.discards[i].after_call || !pl.discards[i].drawn) break;
      tail++;
    }
    // The kinds discarded since its last discard: it let them go (過水), so it cannot win on them now.
    const passed = new Set<number>();
    for (let i = view.events.length - 1; i >= 0; i--) {
      const e = view.events[i];
      if (e.type !== 'discard') continue;
      if (e.seat === s) break;
      if (e.tile) passed.add(kindOf(e.tile));
    }
    return { rel: r - 1, ready: o?.ready[0] ?? 0, p: pl, disc: new Set(pl.discards.map((d) => kindOf(d.tile))), passed, tail,
      justCalled: pl.discards.length > 0 && !!pl.discards[pl.discards.length - 1].after_call };
  });
  const top = opps.reduce((x, y) => (y.ready > x.ready ? y : x));
  const signsOf = (o: Opp) => {
    const nd = o.p.discards.length, nm = o.p.melds.length;
    const ss = [nd === 1 ? t('explain_sign_discards_one') : t('explain_sign_discards', nd)];
    if (nm) ss.push(nm === 1 ? t('explain_sign_melds_one') : t('explain_sign_melds', nm));
    if (o.tail >= 2) ss.push(t('explain_sign_tsumogiri', o.tail));
    if (o.justCalled) ss.push(t('explain_sign_called'));
    if (o.p.declared) ss.push(t('explain_sign_declared'));
    const list = ss.join(t('explain_list_sep'));
    const rate = threatOf(o.p.discards.length, o.p.melds.length, o.tail);
    const seat = ta('explain_seats')[o.rel];
    if (rate === undefined || o.p.declared) return t('explain_signs', seat, list);
    // The network reads more than these signs (which tiles, in what order): said when it differs much.
    const more = o.ready >= rate + 0.25 ? t('explain_signs_more') : o.ready <= rate - 0.25 ? t('explain_signs_less') : '';
    return t('explain_signs_ready', seat, list, chance(rate)) + more;
  };
  const safety = (o: Opp, f: Figures): [string, number | undefined] => {
    const k = kOf(f), nm = name(f);
    if (o.passed.has(k) && !o.disc.has(k)) return [t('explain_tile_passed', nm), 0];
    const cat = tileCat(k, o.disc), rc = rankClass(k), seen = Math.min(3, vis[k] - c[k]);
    const rate = SAFETY[`${cat},${rc},${seen}`];
    if (cat === 'g') return [t('explain_tile_genbutsu', nm), rate];
    if (cat === 's') return [t('explain_tile_suji', nm), rate];
    if (cat === 'h') return [t('explain_tile_half', nm), rate];
    const seenText = seen === 0 ? t('explain_seen_none') : t('explain_seen', seen);
    return [k >= 27 ? t('explain_tile_honor', nm, seenText) : t('explain_tile_number', nm, t('explain_rank_' + rc), seenText), rate];
  };
  const dangerP = ap.danger ?? 0, dangerB = ab.danger ?? 0;
  if (dangerP - dangerB >= 0.01 && top.ready >= 0.3) {
    const [lp, rp] = safety(top, ap), [lb, rb] = safety(top, ab);
    const deal = rp !== undefined && rb !== undefined ? t('explain_deal', lp, percent(rp), lb, percent(rb)) : t('explain_deal_short', lp, lb);
    if (ab.shanten > ap.shanten)
      return out('defend-step', 'explain_t_defend', [deal, t('explain_defend_step', steps(ap.shanten), w, name(ab))], 'explain_rule_defend', signsOf(top));
    if (ab.shanten === 0 && ap.shanten === 0)
      return out('wait-safer', 'explain_t_wait', [deal, t('explain_wait_safer', name(ab))], 'explain_rule_safer', signsOf(top));
    return out('defend-same', 'explain_t_safer', [deal, t('explain_safer_same')], 'explain_rule_safer', signsOf(top));
  }
  if (dangerB - dangerP >= 0.01 && ap.shanten > ab.shanten)
    return out('push', 'explain_t_push', [t('explain_push', steps(ab.shanten), w, name(ap))], 'explain_rule_push', signsOf(top));

  const kp = kOf(ap), kb = kOf(ab), hp = roleOf(c, kp), hb = roleOf(c, kb);
  const isPair = (h: string) => h === 'zpair' || h === 'pair';
  const tai = (k: number) => (k >= 27 && honorTai(k) ? t('explain_honor_tai') : '');
  const joinedToRun = (k: number) => k < 27 && [-2, -1, 1, 2].some((d) => {
    const r = (k % 9) + d;
    return r >= 0 && r <= 8 && c[k + d] > 0;
  });
  // A pair that stands alone (no tile of a chow beside it) and can still be punged.
  const livePair = (k: number) => c[k] === 2 && unseen(k) >= 1 && !joinedToRun(k);
  if (isPair(hp) && unseen(kp) >= 1 && !isPair(hb) && hb !== 'set' && hb !== 'zset') {
    const shape = t(SHAPE[hb] ?? 'explain_shape_extra');
    const why = hb === 'kanchan' || hb === 'penchan' ? t('explain_pair_wait', name(ap), unseen(kp), shape)
      : hb === 'ryanmen' ? t('explain_pair_even') : t('explain_pair_lone', name(ab), name(ap), unseen(kp));
    return out('pair-keep', 'explain_t_pair', [t('explain_pair_keep', name(ap), name(ab), shape), why + tai(kp)], 'explain_rule_pair');
  }
  if (isPair(hp) && isPair(hb) && livePair(kp)) {
    const why = unseen(kb) === 0 ? t('explain_pair_dead', name(ab)) : joinedToRun(kb) ? t('explain_pair_joined', name(ab)) : null;
    if (why) return out('pair-which', 'explain_t_pair_which', [t('explain_pair_which', name(ab), name(ap), why)], 'explain_rule_pair_which');
  }
  if (hp === 'zlone' && hb === 'lone' && pos.players[me].discards.length <= 6 && top.ready < 0.2)
    return out('honor-later', 'explain_t_honor', [t('explain_honor_later', name(ab), name(ap), honorTai(kp) ? t('explain_honor_tai_short') : '')], 'explain_rule_honor');
  if (ab.shanten === 0 && ap.shanten === 0) {
    if (waitsLeft(ab) > waitsLeft(ap)) return out('wait-more', 'explain_t_wait', [t('explain_wait_more', name(ab), waitsLeft(ab), name(ap), waitsLeft(ap))]);
    if (waitTai(ab) > waitTai(ap)) return out('wait-tai', 'explain_t_wait', [t('explain_wait_tai', name(ab), waitTai(ab), waitTai(ap))]);
    if (dangerP - dangerB >= 0.01) return out('wait-safer', 'explain_t_wait', [t('explain_wait_safer', name(ab))]);
    if ((ab.win ?? 0) >= (ap.win ?? 0) + 0.03) return out('wait-easier', 'explain_t_wait', [t('explain_wait_easier', name(ab))]);
    return out('wait-other', 'explain_t_wait', [t('explain_wait_other', name(ab))]);
  }
  if (ap.shanten > ab.shanten) return out('eff-steps', 'explain_t_eff', [t('explain_eff_steps', name(ap), steps(ab.shanten), steps(ap.shanten), name(ab))], 'explain_rule_eff');
  if (ab.shanten > ap.shanten) return out('slow', 'explain_t_slow', [t('explain_slow', name(ap), steps(ap.shanten), name(ab), steps(ab.shanten))]);
  if (ab.ukeire >= ap.ukeire + 4) return out('eff-more', 'explain_t_eff', [t('explain_eff_more', name(ab), ab.ukeire, name(ap), ap.ukeire)], 'explain_rule_eff');
  return out('shape', 'explain_t_shape', [t(big ? 'explain_shape_big' : 'explain_shape', name(ab), name(ap), ab.ukeire, ap.ukeire)]);
}

/** The tips' tables (Tips.svelte): unconditional share ready by discards (3-5, 6-8, 9-11, 12+) and melds (0-4),
 *  matching docs/explain.md section 3 (monotonically increasing across discards and melds). */
export const TIPS_THREAT: (number | null)[][] = [
  [0.01, 0.04, 0.11, 0.29, null],
  [0.09, 0.16, 0.29, 0.50, 0.82],
  [0.22, 0.33, 0.47, 0.66, 0.90],
  [0.29, 0.42, 0.57, 0.74, 0.91],
];
/** How often the network turns to safety with an opponent who looks 60-80% ready: by its own hand (ready, 1, 2, 3+
 *  away) and the tiles left (30+, 20-29, 10-19, under 10). */
export const TIPS_FOLD = [[0.03, 0.04, 0.06, 0.19], [0.13, 0.19, 0.30, 0.63], [0.19, 0.34, 0.55, 0.54], [0.31, 0.44, 0.40, 0.37]];
/** A tile's chance of dealing in to a ready opponent, ordered monotonically from safest to riskiest:
 *  by rank (honor, 1/9, 2/8, 3/7, 4-6) × [genbutsu, suji, non-suji 2 seen, non-suji 1 seen, non-suji unseen]. */
export const TIPS_SAFETY: (number | null)[][] = [
  [0.001, null, 0.0, 0.005, 0.034],
  [0.007, 0.025, 0.029, 0.053, 0.091],
  [0.007, 0.040, 0.034, 0.068, 0.114],
  [0.005, 0.056, 0.040, 0.074, 0.122],
  [0.008, 0.042, 0.053, 0.090, 0.133],
];
export const TIPS_HALF = 0.082;
