// The AI coach on the page (docs/coach.md), a port of the app's coach/Coach.kt: the player's recent hands of one set
// of rules, reviewed by the network, put in a digest (digest.ts) and read by a language model with the player's own
// key (providers.ts), which finds the player's habits and explains them. Its answers are kept (the last few, in this
// browser), and can be asked more about. What the page shows while it works (the phase, the answer shown) lives here,
// so that a link from an answer to a moment of a review, and Back, find it as it was.
import * as engine from '../engine';
import { t } from '../i18n.svelte';
import { newId, records, type HandRecord } from '../records.svelte';
import { reviewOf, summarize } from '../review';
import { FLOWER_PRESETS, info, TAI_PRESETS } from '../rules';
import type { Level } from '../settings.svelte';
import { KEYS, load, save } from '../store';
import type { Position } from '../types';
import { assemble, DigestWords, handDigest, keySteps, rulesText, type HandDigest } from './digest';
import { coachKeys } from './keys.svelte';
import {
  ask, COACH_SCHEMA, CoachError, costOf, effortOf, modelOf, provider, type CoachModel, type Effort, type ErrorKind, type ProviderId, type Turn,
} from './providers';

/** The records of one set of rules: the same values of every parameter, whatever spec they were played under. `tai`,
 *  `flowers`: the presets nearest to them, with `changed` parameters set otherwise. */
export interface RulesGroup { key: string; spec: string; tai: string; flowers: string; changed: number; hands: HandRecord[] }

/** A coach's review and the questions after it, as the app keeps one (CoachSession). `hands`: the records' ids, H1 the
 *  first; turns[0] the digest. `model`: as it answered (shown); `asked`, `effort`: what questions after it keep. */
export interface CoachSession {
  id: string; time: string; provider: ProviderId; model: string; lang: string; rules: string; hands: string[]; system: string;
  turns: Turn[]; input: number; output: number; cost: number | null; asked: string; effort: Effort;
}

/** The digest made ready to send: its text, the hands in it, the hands that could not be reviewed. */
export interface Prepared { group: RulesGroup; rulesName: string; hands: HandRecord[]; skipped: number; digest: string; question: string }

export type Phase =
  | { kind: 'idle' }
  /** Hands reviewed and put in the digest: done of total. */
  | { kind: 'gathering'; done: number; total: number }
  | { kind: 'ready'; prepared: Prepared }
  /** Waiting for the model's answer (a question after it too). */
  | { kind: 'asking'; provider: ProviderId }
  /** An error; `prepared`: the digest it was to send, which closing the error brings back. */
  | { kind: 'failed'; error: ErrorKind | null; detail: string; prepared?: Prepared };

/** The first message: the digest, then the player's question. */
export function firstMessage(p: Prepared, zh: boolean): string {
  return p.question.trim() ? p.digest + '\n' + (zh ? '玩家想特別問：' : 'The player asks: ') + p.question.trim() : p.digest;
}

const SESSIONS = KEYS.coachSessions;
/** Sessions kept: the latest. */
const KEEP = 5;
/** Hands' parts kept while the page is open (a hand's is a few kB). */
const CACHE = 400;

const isZh = () => t('coach_lang') === 'zh-TW';

class Coach {
  phase = $state<Phase>({ kind: 'idle' });
  groups = $state<RulesGroup[] | null>(null);
  sessions = $state<CoachSession[]>([]);
  /** The page's choices and what it shows, kept while it is away (a review opened from an answer). */
  groupKey = $state<string | null>(null);
  question = $state('');
  shownId = $state<string | null>(null);
  scroll = 0;

  private cache = new Map<string, HandDigest & { m: string; lang: string }>();
  private abort: AbortController | null = null;
  /** The work going on (a digest made, an answer waited for): a new one waits for none. */
  private busy = false;

  constructor() {
    const s = load<CoachSession[]>(SESSIONS, []);
    this.sessions = Array.isArray(s) ? s.filter((x) => x && typeof x.id === 'string' && Array.isArray(x.turns)) : [];
  }

  /** The records in groups by their rules (hands played with the analysis shown left out: 顯示分析), the groups with
   *  the most recent hand first. */
  async loadGroups() {
    try {
      this.groups = await this.group(records.hands.filter((h) => !h.hints));
    } catch {
      this.groups = [];
    }
  }

  private async group(hands: HandRecord[]): Promise<RulesGroup[]> {
    const presets = await Promise.all(TAI_PRESETS.flatMap((tp) => FLOWER_PRESETS.map(async (f) => ({ tai: tp, flowers: f, values: (await info(`${tp},${f}`)).values }))));
    const bySpec = new Map<string, HandRecord[]>();
    for (const h of hands) bySpec.set(h.game.rules, [...(bySpec.get(h.game.rules) ?? []), h]);
    const byKey = new Map<string, { spec: string; values: Record<string, number>; hands: HandRecord[] }>();
    for (const [spec, hs] of bySpec) {
      const values = (await info(spec)).values;
      const key = Object.keys(values).sort().map((k) => `${k}=${values[k]}`).join(',');
      const g = byKey.get(key);
      if (g) g.hands.push(...hs);
      else byKey.set(key, { spec, values, hands: [...hs] });
    }
    const diff = (a: Record<string, number>, b: Record<string, number>) => [...new Set([...Object.keys(a), ...Object.keys(b)])].filter((k) => a[k] !== b[k]).length;
    const when = (h: HandRecord) => Date.parse(h.time) || 0;
    return [...byKey].map(([key, g]) => {
      const near = presets.reduce((b, p) => (diff(p.values, g.values) < diff(b.values, g.values) ? p : b));
      return { key, spec: g.spec, tai: near.tai, flowers: near.flowers, changed: diff(near.values, g.values), hands: g.hands.sort((a, b) => when(b) - when(a)) };
    }).sort((a, b) => when(b.hands[0]) - when(a.hands[0]));
  }

  /** Reviews the group's latest `count` hands and puts them in a digest (phase ready), in the page's language. */
  async prepare(group: RulesGroup, count: number, rulesName: string, question: string) {
    if (this.busy) return;
    this.busy = true;
    const zh = isZh();
    const w = new DigestWords(zh);
    const chosen = group.hands.slice(0, count).reverse();
    const abort = (this.abort = new AbortController());
    this.phase = { kind: 'gathering', done: 0, total: chosen.length };
    try {
      const parts: [HandRecord, HandDigest][] = [];
      let skipped = 0;
      for (const [i, h] of chosen.entries()) {
        if (abort.signal.aborted) return;
        const d = await this.handDigest(w, h);
        if (d) parts.push([h, d]);
        else skipped++;
        if (abort.signal.aborted) return;
        this.phase = { kind: 'gathering', done: i + 1, total: chosen.length };
      }
      if (!parts.length) {
        this.phase = { kind: 'failed', error: null, detail: t('coach_no_hands') };
        return;
      }
      const digest = assemble(w, rulesText(w, await info(group.spec), rulesName), parts.map((x) => x[1]));
      this.phase = { kind: 'ready', prepared: { group, rulesName, hands: parts.map((x) => x[0]), skipped, digest, question } };
    } catch (e) {
      if (!abort.signal.aborted) this.phase = { kind: 'failed', error: null, detail: String((e as Error).message ?? e) };
    } finally {
      this.busy = false;
      if (this.abort === abort) this.abort = null;
    }
  }

  /** A hand's part of the digest: kept from before for this network and language, or reviewed now (its summary kept
   *  with the record too). Null when it cannot be: no network, or a record the engine cannot replay. */
  private async handDigest(w: DigestWords, h: HandRecord): Promise<HandDigest | null> {
    const lang = w.zh ? 'zh' : 'en';
    const kept = this.cache.get(h.id);
    if (kept && kept.m === engine.REVIEW_ID && kept.lang === lang) return kept;
    try {
      const r = await reviewOf(h);
      if (r.first_value === undefined) return null;
      if (records.get(h.id)?.rev?.m !== engine.REVIEW_ID) records.setReview(h.id, summarize(r));
      const positions = new Map<number, Position>();
      for (const step of keySteps(r)) positions.set(step, (await engine.view({ ...h.game, actions: h.game.actions.slice(0, step) }, h.me)).position);
      const d = { ...handDigest(w, h, r, positions), m: engine.REVIEW_ID, lang };
      this.cache.delete(h.id);
      this.cache.set(h.id, d);
      while (this.cache.size > CACHE) this.cache.delete(this.cache.keys().next().value!);
      return d;
    } catch {
      return null;
    }
  }

  /** Stops what is going on: the digest being made (back to nothing), the answer waited for (back to what was sent). */
  cancel() {
    this.abort?.abort();
    this.abort = null;
    if (this.phase.kind === 'gathering') this.phase = { kind: 'idle' };
  }

  dismiss() {
    if (this.busy) return;
    this.phase = this.phase.kind === 'failed' && this.phase.prepared ? { kind: 'ready', prepared: this.phase.prepared } : { kind: 'idle' };
  }

  /** Sends the prepared digest to provider `p`'s `model` (undefined: a custom server's), thinking at `effort`; the
   *  answer becomes the newest session. */
  async send(prepared: Prepared, p: ProviderId, model: CoachModel | undefined, effort: Effort, level: Level) {
    if (this.busy) return;
    const zh = isZh();
    const key = coachKeys.get(p);
    if (key === null) {
      this.phase = { kind: 'failed', error: 'KEY', detail: '', prepared };
      return;
    }
    const system = systemPrompt(level, zh);
    const first: Turn = { user: true, text: firstMessage(prepared, zh) };
    const asked = model?.id ?? coachKeys.custom.model;
    const abort = (this.abort = new AbortController());
    this.busy = true;
    this.phase = { kind: 'asking', provider: p };
    try {
      const r = await ask(p, asked, effort, key, coachKeys.custom, system, [first], COACH_SCHEMA, abort.signal);
      if (!r.text.trim()) throw new CoachError('BAD_ANSWER', '');
      const s: CoachSession = {
        id: newId(), time: new Date().toISOString(), provider: p, model: p === 'custom' ? coachKeys.custom.model : r.model, lang: zh ? 'zh' : 'en',
        rules: prepared.rulesName, hands: prepared.hands.map((h) => h.id), system, turns: [first, { user: false, text: r.text, raw: r.raw }],
        input: r.input, output: r.output, cost: costOf(model, r), asked, effort,
      };
      this.sessions = [s, ...this.sessions].slice(0, KEEP);
      this.shownId = null;
      this.persist();
      this.phase = { kind: 'idle' };
    } catch (e) {
      if (abort.signal.aborted) this.phase = { kind: 'ready', prepared };
      else if (e instanceof CoachError) this.phase = { kind: 'failed', error: e.kind, detail: e.detail, prepared };
      else this.phase = { kind: 'failed', error: null, detail: String((e as Error).message ?? e), prepared };
    } finally {
      this.busy = false;
      if (this.abort === abort) this.abort = null;
    }
  }

  /** Asks more about a session, of the same model at the same thinking: the answer is added to it. */
  async followUp(session: CoachSession, question: string) {
    if (this.busy || !question.trim()) return;
    const p = provider(session.provider);
    if (!p) return;
    const model = modelOf(p, session.asked || null);
    const asked = p.id === 'custom' ? session.asked || coachKeys.custom.model : model?.id;
    if (!asked) return;
    const key = coachKeys.get(p.id);
    if (key === null) {
      this.phase = { kind: 'failed', error: 'KEY', detail: '' };
      return;
    }
    const turns: Turn[] = [...session.turns, { user: true, text: question.trim() }];
    const abort = (this.abort = new AbortController());
    this.busy = true;
    this.phase = { kind: 'asking', provider: p.id };
    try {
      const r = await ask(p.id, asked, effortOf(session.effort), key, coachKeys.custom, session.system, turns, null, abort.signal);
      const cost = costOf(model, r);
      const s: CoachSession = {
        ...session, turns: [...turns, { user: false, text: r.text, raw: r.raw }], input: session.input + r.input, output: session.output + r.output,
        cost: session.cost !== null && cost !== null ? session.cost + cost : null,
      };
      this.sessions = this.sessions.map((x) => (x.id === s.id ? s : x));
      this.persist();
      this.phase = { kind: 'idle' };
    } catch (e) {
      if (abort.signal.aborted) this.phase = { kind: 'idle' };
      else if (e instanceof CoachError) this.phase = { kind: 'failed', error: e.kind, detail: e.detail };
      else this.phase = { kind: 'failed', error: null, detail: String((e as Error).message ?? e) };
    } finally {
      this.busy = false;
      if (this.abort === abort) this.abort = null;
    }
  }

  delete(id: string) {
    this.sessions = this.sessions.filter((s) => s.id !== id);
    this.persist();
  }

  /** Kept in this browser; when there is no room (the records take most of it), the oldest sessions go first. */
  private persist() {
    let list = this.sessions;
    while (!save(SESSIONS, list) && list.length > 1) list = list.slice(0, -1);
  }
}

export const coach = new Coach();

/** The instructions: one text in English (the models follow it best), the answer's language by the page's. The same
 *  as the app's (coach/Coach.kt systemPrompt). */
export function systemPrompt(level: Level, zh: boolean): string {
  const levelText = {
    beginner: 'The player calls themself a beginner: write about blunders and mistakes only (losses of 0.25 and more), at most two habits, and explain any mahjong term you use.',
    mid: 'The player is intermediate: write about mistakes and blunders; questionable decisions only where they make a clear habit.',
    expert: 'The player is strong: include questionable decisions where they make a habit, and be precise about the margins.',
  }[level];
  const language = zh
    ? 'Write everything in Traditional Chinese as used in Taiwan (zh-TW), with the usual Taiwanese mahjong words (聽牌, 放槍, 自摸, 向聽, 進張, 生張, 棄胡, 門清, 獨聽, 吃, 碰, 槓), and name tiles as the digest does (三萬, 五筒, 東, 白).'
    : 'Write everything in US English. Name tiles as the digest does (3 Characters, 5 Dots, East, White). Use plain words for the terms: ready, deal in, self-draw, shanten, useful tiles, fold, concealed hand, single wait.';
  return `You are a coach for Taiwanese 16-tile mahjong (台灣十六張麻將). The player uses a practice app whose neural network, trained by self-play and much stronger than the player, has reviewed every decision of the hands in the digest you receive. The digest gives the statistics the app computed and the decisions where the network found a loss or a best action that is not the obvious one; its header explains every figure and gives the rules of these hands. Your job: find the player's recurring habits across the hands, explain them in terms a person can use at the table, and suggest what to practise. You do not judge tiles yourself.

How to treat the data
- When the network says one action is better, take that as the answer. Your value is in explaining why in table terms (danger against an opponent likely ready, a pair that can be called and is worth a tai, a wait with more tiles left, speed against value) and in seeing patterns across many hands.
- Every figure is the network's estimate. Losses under 0.05 are its noise. A hand's result is mostly luck: judge decisions by their loss, never by whether the hand was won.
- Use only what the digest states. Do not invent tiles, waits, opponents' hands or positions, and do not work out tile efficiency or waits yourself: the digest gives them.
- A habit needs at least two decisions as evidence; something seen once is a single case, and say so.
- Cite decisions by their id exactly as written, such as [H9-73]: the app turns each into a link to that moment. Only ids that appear in the digest. A hand as a whole is H9, without brackets (it is not a link).
- The hands are numbered oldest first; if the later hands show a change, say so.

What the app's self-play showed (prefer these to common lore when you explain why)
- Reading an opponent: the number of melds and several discards in a row straight from the draw (摸切) are the signs of a ready hand; a call just made adds little by itself.
- Falling back: against an opponent very likely ready the network folds only about 20% of the time when one away and under 10% when ready; it turns to safety mostly when two or more away with fewer than 30 tiles left, or one away with fewer than 10.
- Safety (rates, not guarantees): a tile the opponent discarded deals in under 1%, an honor already seen about as rarely; a suji tile about a third to a half as often as an unseen tile of its rank; an unseen non-suji 4 to 6 is the most dangerous.
- Calls: one that brings the hand no closer mostly costs chances to win; late in the hand, one that does raises the chance of dealing in more than the chance of winning. Concealed and three or more away the network takes only about 60% of chows; two or fewer away it takes nearly every call that brings it closer. A pung of an honor pair helps to win (and may score a tai); it does not help defense.
- Early, with many tiles to win on, it often passes up a win on a discard to wait for a self-draw, mostly with a concealed hand.

Rules (these differ from Japanese riichi mahjong and from many house rules)
- 16 tiles: five sets and a pair. No riichi, no dora. Flowers are set aside and replaced.
- No furiten: a player may win on a tile they discarded themselves, so a tile an opponent discarded earlier is only less likely to deal in to them, not safe. The one lock is 過水: after passing up a win, a player cannot win on a discard until their own next draw.
- Only the tai items the digest lists score, with the values it gives; anything else is 0 tai here, even where other rules score it.

What to write
${levelText}
Answer the first message with a JSON object:
- summary: one or two sentences on how the hands went, telling luck (results) apart from decisions (losses). If the player asked something, answer it here or in a habit.
- habits: two to four, the costliest first. Each: title, a few words; body, what the player tends to do and why the network's choice is better, with the ids in the text; refs, the ids of the evidence; rule, one rule of thumb to try at the table.
- strengths: one thing the player did well, judged by the decisions (few losses, or a best action that is not the obvious one which the player found), never by a hand won; with an id if there is one.
- practice: one concrete suggestion for practising in the app (for example, practice with the analysis shown and watch the opponents' chances of being ready).
Keep each habit under about 120 words (or 300 Chinese characters), in plain words: the reader may be an older casual player. No talk of money or betting.
${language}
Later questions from the player: answer in plain text (not JSON), in the same language, short, citing ids the same way.`;
}
