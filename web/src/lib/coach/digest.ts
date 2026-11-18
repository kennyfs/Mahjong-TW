// The digest the coach reads (docs/coach.md): what the network made of the player's hands, as text in the page's
// language. A port of the app's coach/Digest.kt, word for word: the page computes every figure; the model is to find
// habits across hands and explain them, not to add up numbers or judge tiles. Of each hand only the key decisions are
// written out (a questionable one or worse, or a best action that is not the obvious one); the rest are counted.
import { actionText } from '../actions';
import { has, t } from '../i18n.svelte';
import type { HandRecord } from '../records.svelte';
import { EQUAL, GRADE_NAME, gradeOf, KINDS_OF_LOSS, kindOf, notableOf, reasonOf, type Notable } from '../review';
import { FLOWER_PARAMS, PLAY_PARAMS } from '../rules';
import { meldTiles } from '../seats';
import { isFlower, parseTiles, sortTiles, tileName, tilesShort, windName, type Tile } from '../tiles';
import type { Analysis, Meld, Position, Review, ReviewDecision, RulesInfo } from '../types';

/** A hand's part of the digest. The hand's number in a digest is given when the digest is put together, so the lines
 *  here start after it. */
export interface HandDigest {
  id: string;
  /** "東家莊｜結果 自摸胡，+12｜開局期望 −0.81" */
  title: string;
  /** 0 won by a discard, 1 by self-draw, 2 dealt in, 3 another self-drew, 4 others won by a discard, 5 drawn hand. */
  outcome: number;
  points: number;
  /** Decisions with a choice; their total loss; per grade (questionable, mistake, blunder); the loss by kind. */
  n: number;
  loss: number;
  grades: number[];
  kinds: number[];
  /** Per kind of notable (NOTABLE): how often, then how often found (2 x 4). */
  notable: number[];
  /** The discard that dealt in, in short. */
  dealIn: string | null;
  keys: { step: number; loss: number; text: string }[];
  /** Played with people at the table (else computer players only). */
  people: boolean;
}

const NOTABLE: Notable['kind'][] = ['decline_win', 'fold', 'slow', 'no_call'];
/** At most this many key decisions in a digest: the costliest (a hundred hands have about 300). */
export const MAX_KEYS = 150;

/** The digest's words in one language. Tiles and actions come from the page's own words. */
export class DigestWords {
  constructor(public zh: boolean) {}
  pct(p: number) {
    const x = p * 100;
    return (x >= 9.5 ? x.toFixed(0) : x.toFixed(1)) + '%';
  }
  f2 = (x: number) => x.toFixed(2);
  s2 = (x: number) => (x >= 0 ? '+' : '') + x.toFixed(2);
  f1 = (x: number) => x.toFixed(1);
  get sep() { return this.zh ? '｜' : ' | '; }
  get list() { return this.zh ? '、' : ', '; }
  get semi() { return this.zh ? '；' : '; '; }
  turn = (n: number, wall: number) => (this.zh ? `第${n}巡 牌牆剩${wall}` : `turn ${n}, ${wall} left in the wall`);
  hand = (s: string) => (this.zh ? `手牌 ${s}` : `hand: ${s}`);
  drew = (x: string) => (this.zh ? `摸 ${x}` : `drew ${x}`);
  discarded = (who: string, x: string) => (this.zh ? `${who}打出 ${x}` : `${who} discarded ${x}`);
  get afterCall() { return this.zh ? '吃碰後' : 'after a call'; }
  melds = (s: string) => (this.zh ? `副露 ${s}` : `melds: ${s}`);
  get none() { return this.zh ? '無' : 'none'; }
  flowers = (n: number) => (this.zh ? `花 ${n}` : `${n} flowers`);
  oppReady = (s: string) => (this.zh ? `對手聽牌機率 ${s}` : `opponents ready: ${s}`);
  me = (within: string, items: string) =>
    (this.zh ? `自己（照網路的打法）1／2／4／8 摸內聽牌 ${within}` : `own hand (as the network plays it): ready within 1/2/4/8 draws ${within}`) +
    (!items ? '' : this.zh ? `；胡的話常有的台：${items}` : `; a win would likely include: ${items}`);
  graded = (g: string, loss: number) => (this.zh ? `${g}，失分 ${this.f2(loss)}` : `${g}, loss ${this.f2(loss)}`);
  get found() { return this.zh ? '（有做到）' : ' (found)'; }
  get played() { return this.zh ? '打了 ' : 'played '; }
  get best() { return this.zh ? '最佳 ' : 'best '; }
  get usual() { return this.zh ? '（常見選擇）' : '(the obvious choice) '; }
  get why() { return this.zh ? '差在 ' : 'why: '; }
  fig = (action: string, q: number, win: number, tai: number, deal: number) => this.zh
    ? `${action}：期望 ${this.s2(q)}、胡 ${this.pct(win)}（胡了平均 ${this.f1(tai)} 台）、放槍 ${this.pct(deal)}`
    : `${action}: expected ${this.s2(q)}, win ${this.pct(win)} (a win ${this.f1(tai)} tai on average), deal in ${this.pct(deal)}`;
  danger = (p: number) => (this.zh ? `、這張當下危險 ${this.pct(p)}` : `, this tile deals in now ${this.pct(p)}`);
  dangerWho = (who: string, p: number, tai: number) => (this.zh ? `（${who} ${this.pct(p)}、${this.f1(tai)} 台）` : ` (${who} ${this.pct(p)}, ${this.f1(tai)} tai)`);
  safety = (s: string) => (this.zh ? `〔${s}〕` : ` [${s}]`);
  discardedBy = (who: string) => (this.zh ? `${who}打過` : `${who} discarded it`);
  seen = (n: number) => (this.zh ? `場上已見${n}張` : `${n} seen on the table`);
  get live() { return this.zh ? '生張' : 'none seen on the table'; }
  get ready() { return this.zh ? '聽牌' : 'ready'; }
  get win() { return this.zh ? '胡' : 'a win'; }
  shanten = (n: number) => (this.zh ? `${n} 向聽` : `${n}-shanten`);
  ukeire = (n: number) => (this.zh ? ` 進張 ${n}` : `, ${n} useful tiles`);
  waits = (s: string) => (this.zh ? `（聽 ${s}）` : ` (waits: ${s})`);
  wait = (x: string, left: number) => (this.zh ? `${x}剩${left}` : `${x} ${left} left`);
  handTitle = (wind: string, dealer: boolean, result: string, pts: string, first: number) => this.zh
    ? `${wind}家${dealer ? '莊' : ''}｜結果 ${result}，${pts}｜開局期望 ${this.s2(first)}`
    : `${wind} seat${dealer ? ', dealer' : ''} | result: ${result}, ${pts} | expected at the start ${this.s2(first)}`;
  handLine = (h: number, people: boolean, title: string, n: number, loss: number, k: number) => this.zh
    ? `## H${h}｜${people ? '有真人' : '對電腦'}｜${title}｜決策 ${n} 次、失分合計 ${this.f2(loss)}、列出 ${k} 次`
    : `## H${h} | ${people ? 'with people' : 'vs computer'} | ${title} | ${n} decisions, total loss ${this.f2(loss)}, ${k} listed`;
  get outcomes() {
    return this.zh ? ['胡', '自摸胡', '放槍', '被自摸', '別人互胡', '流局']
      : ['won on a discard', 'won by self-draw', 'dealt in', 'another self-drew', 'others won', 'drawn hand'];
  }
  othersSelfDraw = (who: string) => (this.zh ? `${who}自摸` : `${who} self-drew`);
  dealtIn = (who: string) => (this.zh ? `放槍給${who}` : `dealt in to ${who}`);
  othersWon = (w: string, l: string) => (this.zh ? `${w}胡${l}` : `${w} won on ${l}'s discard`);
  dealInLine = (danger: number, ready: string, loss: number) => this.zh
    ? `這張危險 ${this.pct(danger)}、當時對手聽牌 ${ready}、失分 ${this.f2(loss)}`
    : `the tile's danger ${this.pct(danger)}, opponents ready ${ready}, loss ${this.f2(loss)}`;
}

const isNet = (a: Analysis) => a.source === 'network' && a.best !== undefined;
const rel = (me: number, seat: number) => t(['you', 'rel_next', 'rel_opposite', 'rel_prev'][(seat - me + 4) % 4]);
const handText = (s: string) => tilesShort(sortTiles(parseTiles(s).filter((x) => !isFlower(x))));
const signed0 = (x: number) => (x >= 0 ? '+' : '') + x.toFixed(0);
/** A tai item's name, or null for a parameter that is not one. */
const taiWord = (p: string) => (has('tai_' + p) ? t('tai_' + p) : null);

/** "碰 白", "吃 三四五萬". */
function meldText(m: Meld): string {
  const word = t('act_' + m.type);
  const ts = sortTiles(meldTiles(m).filter((x): x is Tile => x !== null));
  if (!ts.length) return word;
  return `${word} ` + (m.type === 'chi' ? tilesShort(ts) : tileName(ts[0]));
}

/** Copies of a kind on the table: the discards not called, and the melds' tiles. */
const seenOnTable = (pos: Position, x: Tile) => pos.players.reduce((n, p) =>
  n + p.discards.filter((d) => d.tile === x && !d.called).length + p.melds.reduce((k, m) => k + meldTiles(m).filter((y) => y === x).length, 0), 0);

/** A decision's chance to win under the network's play (the policy's mean). */
function policyWin(a: Analysis): number {
  const withPolicy = a.actions.filter((f) => f.policy !== undefined);
  if (!withPolicy.length) return a.actions[a.best ?? 0]?.win ?? 0;
  return withPolicy.reduce((s, f) => s + (f.policy ?? 0) * (f.win ?? 0), 0);
}

function fig(w: DigestWords, a: Analysis, i: number, pos: Position): string {
  const f = a.actions[i];
  let out = w.fig(actionText(f.action), f.q ?? 0, f.win ?? 0, f.tai ?? 0, f.deal_in ?? 0);
  const tile = f.action.tile;
  if (f.action.type === 'discard' && tile && f.danger !== undefined) {
    out += w.danger(f.danger);
    const each = f.danger_each;
    if (f.danger >= 0.01 && each && each.length === 3) {
      const o = [0, 1, 2].reduce((b, k) => (each[k] > each[b] ? k : b), 0);
      out += w.dangerWho(rel(pos.me, (pos.me + o + 1) % 4), each[o], f.danger_tai?.[o] ?? 0);
    }
    const why: string[] = [];
    for (let r = 1; r <= 3; r++) {
      const seat = (pos.me + r) % 4;
      if (pos.players[seat]?.discards.some((d) => d.tile === tile)) why.push(w.discardedBy(rel(pos.me, seat)));
    }
    const seen = seenOnTable(pos, tile);
    why.push(seen > 0 ? w.seen(seen) : w.live);
    out += w.safety(why.join(w.list));
  }
  out += w.list;
  out += f.shanten < 0 ? w.win : f.shanten === 0 ? w.ready : w.shanten(f.shanten);
  if (f.shanten >= 0) out += w.ukeire(f.ukeire);
  if (f.shanten === 0 && f.waits.length) out += w.waits(f.waits.map((x) => w.wait(tileName(x.tile), x.left)).join(w.list));
  return out;
}

/** The steps whose positions hand() needs: the key decisions'. */
export function keySteps(r: Review): number[] {
  return r.decisions.filter((d) => isNet(d.analysis) && (gradeOf(Math.max(0, d.loss ?? 0), r.unit) > 0 || notableOf(d.analysis, r.unit)))
    .map((d) => d.step);
}

function keyText(w: DigestWords, d: ReviewDecision, pos: Position, g: number, loss: number, note: Notable | null): string {
  const a = d.analysis;
  const me = pos.me;
  const mine = pos.players[me];
  const ctx = pos.claim && pos.claim.tile ? w.discarded(rel(me, pos.claim.seat), tileName(pos.claim.tile))
    : pos.drawn ? w.drew(tileName(pos.drawn)) : w.afterCall;
  const melds = mine?.melds.map(meldText).join(w.list) || w.none;
  const flowers = parseTiles(mine?.flowers).length;
  const opp = a.opponents ? a.opponents.map((o) => rel(me, o.seat) + ' ' + w.pct(o.ready[0] ?? 0)).join(w.list) : '?';
  const head = [w.turn((mine?.discards.length ?? 0) + 1, Math.max(0, pos.wall_left)), w.hand(handText(pos.hand)), ctx, w.melds(melds),
    flowers > 0 ? w.flowers(flowers) : null, w.oppReady(opp)].filter((x) => x !== null).join(w.sep);
  const lines = [head];
  if (a.me) {
    // How soon the hand gets ready (left out once it is), and the tai a win would likely bring: each item's chance
    // given a win, the likelier ones. Four separate outputs, which can dip a little: shown as never going down.
    const m = a.me;
    let top = 0;
    const within = m.ready_within.length === 4 && m.ready_within[0] < 0.995 ? m.ready_within.map((x) => w.pct((top = Math.max(top, x)))).join('/') : null;
    const pw = policyWin(a);
    const items = pw >= 0.02 ? Object.entries(m.tai_items).flatMap(([k, p]) => {
      const name = taiWord(k);
      return name ? [[name, Math.min(1, p / pw)] as const] : [];
    }).filter((x) => x[1] >= 0.15).sort((x, y) => y[1] - x[1]).slice(0, 5).map(([n, p]) => `${n} ${w.pct(p)}`).join(w.list) : '';
    if (within !== null) lines.push('  ' + w.me(within, items));
    else if (items) lines.push('  ' + w.me('—', items));
  }
  const tag: string[] = [];
  if (g > 0) tag.push(w.graded(t(GRADE_NAME[g]), loss));
  if (note) tag.push(t('note_' + note.kind) + (loss < EQUAL ? w.found : ''));
  lines.push('  ' + tag.join(w.semi));
  const bi = a.best ?? 0;
  if (d.played >= 0 && d.played < a.actions.length) lines.push('  ' + w.played + fig(w, a, d.played, pos));
  if (bi !== d.played) {
    lines.push('  ' + w.best + fig(w, a, bi, pos));
    const why = reasonOf(a, d.played);
    if (why) lines.push('  ' + w.why + why);
  }
  if (note && note.alt !== bi && note.alt !== d.played && note.alt >= 0 && note.alt < a.actions.length) lines.push('  ' + w.usual + fig(w, a, note.alt, pos));
  return lines.join('\n');
}

/** A hand's part: `positions` holds the player's position at each key decision's step. */
export function handDigest(w: DigestWords, rec: HandRecord, r: Review, positions: Map<number, Position>): HandDigest {
  const me = rec.me;
  const grades = [0, 0, 0];
  const kinds = KINDS_OF_LOSS.map(() => 0);
  const notable = Array<number>(8).fill(0);
  const keys: HandDigest['keys'] = [];
  for (const d of r.decisions) {
    const a = d.analysis;
    if (!isNet(a)) continue;
    const loss = Math.max(0, d.loss ?? 0);
    const g = gradeOf(loss, r.unit);
    if (g > 0) grades[g - 1]++;
    if (loss >= EQUAL) kinds[KINDS_OF_LOSS.indexOf(kindOf(d))] += loss;
    const note = notableOf(a, r.unit);
    if (note) {
      notable[NOTABLE.indexOf(note.kind)]++;
      if (loss < EQUAL) notable[4 + NOTABLE.indexOf(note.kind)]++;
    }
    if (g === 0 && !note) continue;
    const pos = positions.get(d.step);
    if (!pos) continue;
    keys.push({ step: d.step, loss, text: keyText(w, d, pos, g, loss, note) });
  }
  const outcome = rec.exhausted ? 5 : rec.winners.includes(me) ? (rec.selfDraw ? 1 : 0) : rec.selfDraw ? 3 : rec.loser === me ? 2 : 4;
  const result = outcome === 2 ? w.dealtIn(rec.winners.map((x) => rel(me, x)).join(w.list))
    : outcome === 3 ? w.othersSelfDraw(rec.winners.length ? rel(me, rec.winners[0]) : '?')
    : outcome === 4 ? w.othersWon(rec.winners.map((x) => rel(me, x)).join(w.list), rel(me, rec.loser))
    : w.outcomes[outcome];
  const points = rec.points[me] ?? 0;
  const setup = rec.game.setup;
  const title = w.handTitle(windName((((me - setup.east) % 4) + 4) % 4), setup.dealer === me, result, signed0(points), r.first_value ?? 0);
  // The discard that dealt in: the hand's last decision.
  let dealIn: string | null = null;
  if (outcome === 2) {
    const d = r.decisions[r.decisions.length - 1];
    const f = d?.analysis.actions[d.played];
    const opp = d?.analysis.opponents ? d.analysis.opponents.map((o) => w.pct(o.ready[0] ?? 0)).join('/') : '?';
    if (d && f) dealIn = w.dealInLine(f.danger ?? 0, opp, Math.max(0, d.loss ?? 0));
  }
  const people = rec.bots.some((b, i) => i !== me && b === null);
  return {
    id: rec.id, title, outcome, points, n: r.decisions.filter((d) => isNet(d.analysis)).length,
    loss: r.decisions.reduce((s, d) => s + Math.max(0, d.loss ?? 0), 0), grades, kinds, notable, dealIn, keys, people,
  };
}

/** The rules in words: each parameter by the page's name and value, the ones that score with the rules page's help. */
export function rulesText(w: DigestWords, info: RulesInfo, name: string): string {
  const v = info.values;
  const label = (p: string) => taiWord(p) ?? (has('param_' + p) ? t('param_' + p) : p);
  const help = (p: string) => (has('help_' + p) ? t('help_' + p).replace(/\n/g, ' ') : null);
  const colon = w.zh ? '：' : ': ';
  const out = [(w.zh ? '規則：' : 'Rules: ') + name];
  for (const p of [...PLAY_PARAMS, ...FLOWER_PARAMS]) {
    if (v[p] === undefined) continue;
    const h = help(p);
    out.push(`- ${label(p)} = ${v[p]}` + (h ? colon + h : ''));
  }
  const tai = info.items.map((x) => x.param).filter((p) => !FLOWER_PARAMS.includes(p));
  const counted = tai.filter((p) => (v[p] ?? 0) !== 0);
  const zero = tai.filter((p) => (v[p] ?? 0) === 0);
  out.push(w.zh ? '台（只有這些算台；值是台數）：' : 'Tai (only these score; the value is the tai):');
  for (const p of counted) {
    const h = help(p);
    out.push(`- ${label(p)} ${v[p]}` + (h ? colon + h : ''));
  }
  for (const p of Object.keys(v).filter((p) => p === 'quanqiu_duting' || p === 'dasixi_winds')) {
    const h = help(p);
    out.push(`- ${label(p)} = ${v[p]}` + (h ? ': ' + h : ''));
  }
  if (zero.length) out.push((w.zh ? '0 台（這套規則不算）：' : '0 tai (not scored in these rules): ') + zero.map(label).join(w.list));
  return out.join('\n');
}

function legend(w: DigestWords): string {
  const gn = [1, 2, 3].map((g) => t(GRADE_NAME[g]));
  const notes = NOTABLE.map((k) => t('note_' + k)).join(w.list);
  return w.zh ? [
    `單位：期望與失分都以「台」計（一台＝一個底）。失分＝最佳的期望減去打了的期望。等級：失分 ≥0.10 ${gn[0]}、≥0.25 ${gn[1]}、≥0.50 ${gn[2]}；<0.05 是網路本身的誤差，等於沒差。`,
    '期望：這個選擇之後這一局的平均結果（算進胡牌、放槍、被自摸）。胡／放槍：這個選擇之後到這局結束會胡、會放槍的機率；胡了平均 N 台：胡的話平均幾台。',
    '這張當下危險：打這張馬上有人胡的機率，括號裡是最可能胡它的對手。〔…〕：誰打過這種牌、場上看得到幾張；生張＝場上一張都沒出現過。這套規則沒有振聽，別人打過的牌只是比較安全，不是絕對安全。',
    '差在：失分拆成胡得少、放槍多、被自摸多。對手聽牌機率是網路的估計。自己 1／2／4／8 摸內聽牌：照網路的打法，接下來幾次摸牌內聽牌的機率；胡的話常有的台：胡的話各台出現的機率。',
    `${notes}：網路的最佳不是直覺的選擇；「有做到」是玩家選對了。`,
  ].join('\n') : [
    `Units: expected results and losses are in tai (one tai = one base). Loss = the best action's expected result minus the played one's. Grades: loss ≥0.10 ${gn[0]}, ≥0.25 ${gn[1]}, ≥0.50 ${gn[2]}; under 0.05 is the network's own error, as good as no loss.`,
    'Expected: the hand\'s average result after this choice (wins, deal-ins and others\' self-draws). Win / deal in: the chances of winning / dealing in by the end of the hand after this choice; a win N tai on average: the tai of a win.',
    'This tile deals in now: the chance that someone wins on this discard at once, with the opponent most likely to. [...]: who has discarded the kind, and how many copies are on the table; none seen = not one copy on the table yet. These rules have no furiten: a tile an opponent discarded is only safer, never safe.',
    'Why: the loss split into winning less, dealing in more and others\' self-draws. Opponents ready: the network\'s chances. Ready within 1/2/4/8 draws: as the network would play the hand on; a win would likely include: each tai item\'s chance in a win.',
    `${notes}: the network's best was not the obvious choice; "found" means the player chose it.`,
  ].join('\n');
}

/** The digest of these hands, oldest first (H1 the oldest). */
export function assemble(w: DigestWords, rules: string, hands: HandDigest[]): string {
  const n = hands.length;
  const out: string[] = [];
  out.push(w.zh ? '# 牌局摘要（由 app 的神經網路分析產生；數字都是網路的估計）' : "# Digest of the hands (from the app's neural network; every figure is its estimate)");
  out.push(rules);
  out.push(legend(w));
  const c = [0, 0, 0, 0, 0, 0];
  for (const h of hands) c[h.outcome]++;
  const points = hands.reduce((s, h) => s + h.points, 0);
  const people = hands.filter((h) => h.people).length;
  out.push(w.zh ? `共 ${n} 局（對電腦 ${n - people}、有真人 ${people}）：胡 ${c[0] + c[1]}（自摸 ${c[1]}）、放槍 ${c[2]}、被自摸 ${c[3]}、別人互胡 ${c[4]}、流局 ${c[5]}；總分 ${signed0(points)}。`
    : `${n} hands (${n - people} vs computer, ${people} with people): won ${c[0] + c[1]} (self-draw ${c[1]}), dealt in ${c[2]}, another self-drew ${c[3]}, others won ${c[4]}, drawn ${c[5]}; points ${signed0(points)}.`);
  const decisions = hands.reduce((s, h) => s + h.n, 0);
  const loss = hands.reduce((s, h) => s + h.loss, 0);
  const g = [0, 1, 2].map((i) => hands.reduce((s, h) => s + (h.grades[i] ?? 0), 0));
  const gn = [1, 2, 3].map((x) => t(GRADE_NAME[x]));
  out.push(w.zh ? `決策 ${decisions} 次；失分合計 ${w.f2(loss)}（每局 ${w.f2(loss / Math.max(1, n))}）；${gn[0]} ${g[0]}、${gn[1]} ${g[1]}、${gn[2]} ${g[2]}。`
    : `${decisions} decisions; total loss ${w.f2(loss)} (${w.f2(loss / Math.max(1, n))} a hand); ${gn[0]} ${g[0]}, ${gn[1]} ${g[1]}, ${gn[2]} ${g[2]}.`);
  const kinds = KINDS_OF_LOSS.map((k, i) => [t('stats_kind_' + k), hands.reduce((s, h) => s + (h.kinds[i] ?? 0), 0)] as const).sort((a, b) => b[1] - a[1]);
  out.push((w.zh ? '失分依種類：' : 'Loss by kind of decision: ') + kinds.map(([k, x]) => `${k} ${w.f2(x)}`).join(w.list));
  const notes = NOTABLE.flatMap((k, i) => {
    const x = hands.reduce((s, h) => s + (h.notable[i] ?? 0), 0);
    const y = hands.reduce((s, h) => s + (h.notable[4 + i] ?? 0), 0);
    return x > 0 ? [`${t('note_' + k)} ${x}/${y}`] : [];
  });
  out.push((w.zh ? '網路的最佳不是直覺選擇的時機（出現次數／有做到）：' : "Times the network's best was not the obvious choice (seen/found): ") + (notes.join(w.list) || w.none));
  const deals = hands.map((h, i) => [i, h] as const).filter(([, h]) => h.dealIn !== null).slice(0, 20);
  if (deals.length) out.push((w.zh ? '放槍的那一手：' : 'The discards that dealt in: ') + deals.map(([i, h]) => `H${i + 1} ${h.dealIn}`).join(w.semi));
  // The costliest key decisions, when there are too many.
  const all = hands.flatMap((h, i) => h.keys.map((k) => ({ i, k })));
  const kept = new Set(all.length <= MAX_KEYS ? all : [...all].sort((a, b) => b.k.loss - a.k.loss).slice(0, MAX_KEYS));
  out.push('');
  out.push(all.length > kept.size
    ? (w.zh ? `下面每局只列出有失分或值得一提的決策（只列失分最大的 ${kept.size} 次，其餘 ${all.length - kept.size} 次只算進上面的統計）。代號 [H局-步] 在 app 裡可以點開。`
      : `Below, each hand lists only its decisions with a loss or worth a note (the ${kept.size} costliest; the other ${all.length - kept.size} count in the figures above only). An id [H<hand>-<step>] opens that moment in the app.`)
    : (w.zh ? '下面每局只列出有失分或值得一提的決策。代號 [H局-步] 在 app 裡可以點開。'
      : 'Below, each hand lists only its decisions with a loss or worth a note. An id [H<hand>-<step>] opens that moment in the app.'));
  hands.forEach((h, i) => {
    const ks = all.filter((x) => x.i === i && kept.has(x)).map((x) => x.k);
    out.push('');
    out.push(w.handLine(i + 1, h.people, h.title, h.n, h.loss, ks.length));
    for (const k of ks) out.push(`[H${i + 1}-${k.step}] ` + k.text);
  });
  return out.join('\n') + '\n';
}
