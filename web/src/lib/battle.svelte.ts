// Battles online: a room on a battle server (docs/battle.md), reached over a WebSocket. The room is the only
// authority: it deals, keeps every table's record, sends this player only their own seat's view, plays the bots and
// whoever runs out of time, and scores. This keeps what the room said and sends the player's decisions. Nothing here
// helps the player while a hand is played: the review and each decision's analysis come after, from the records.
// A port of Thirteen's battle.svelte.ts to mahjong's tables; the app's battle screen is to follow it (docs/app.md).
import { tick } from 'svelte';
import { shared } from './app.svelte';
import { calloutsDone } from './callouts';
import * as engine from './engine';
import { t } from './i18n.svelte';
import { handRecord, newId, records } from './records.svelte';
import { NET_BOT, settings } from './settings.svelte';
import { load, save } from './store';
import type { Action, GameRecord, View } from './types';
import { later } from './clock';
import { ms } from './timing';

// ---- The protocol (protocol/src/main/kotlin/.../Messages.kt): JSON, the type in "t" ----

export const PROTOCOL_VERSION = 1;
/** Validation.kt's Limits. */
export const TIME_LIMITS = [5, 10, 15, 20, 30, 60];
export const HAND_CHOICES = [1, 2, 4, 8, 12, 16];
export const NAME_LENGTH = 16;

export type Mode = 'TABLE' | 'CONTEST';
export type Length = 'HAND' | 'ROUND' | 'GAME';
export const LENGTHS: Length[] = ['HAND', 'ROUND', 'GAME'];
export type Phase = 'LOBBY' | 'PLAYING' | 'REVIEW' | 'OVER';
export interface RuleChoice { tai: string; flowers: string; overrides: Record<string, number> }
export interface RoomSettings { mode: Mode; length: Length; hands: number; rules: RuleChoice; timeLimit: number; bot: string }
export interface PlayerView {
  id: number; name: string; bot: boolean; connected: boolean; seat: number; ready: boolean; done: boolean; total: number;
  left: boolean;
}
export interface RoomView {
  pin: string; settings: RoomSettings; rules: string; phase: Phase; hand: number; host: number; players: PlayerView[];
  remainingMs?: number | null;
}
/** A table of a closed hand: player ids by seat (-1 a contest's bot), the record, each seat's points. */
export interface TablePlay { seats: number[]; record: GameRecord; points: number[]; timeouts: number[] }
/** A closed hand: one table, or a contest's tables in the order of `players`. */
export interface HandOutcome { index: number; tables: TablePlay[]; players: number[] }

type ServerMsg =
  | { t: 'welcome'; version: number }
  | { t: 'joined'; room: string; you: number; token: string }
  | { t: 'state'; room: RoomView }
  | { t: 'table'; hand: number; seat: number; step: number; view: View; full: boolean; remainingMs?: number | null; seats: number[] }
  | { t: 'result'; outcome: HandOutcome }
  | { t: 'history'; outcomes: HandOutcome[] }
  | { t: 'error'; code: string; message?: string }
  | { t: 'closed'; reason?: string };
type ClientMsg = Record<string, unknown> & { t: string };

/** The player's table now: their seat, the view of it (every event from the deal), and the time left to decide. */
export interface MyTable {
  hand: number;
  seat: number;
  /** The decision point, sent back with a decision. */
  step: number;
  /** Player ids by seat; -1 a contest's bot. */
  seats: number[];
  view: View;
  /** When the time to decide runs out (Date.now()), or null. */
  deadline: number | null;
}

/**
 * The WebSocket address for what the player typed: nothing for the server of this page (its /ws, as Caddy and Vite's
 * proxy serve it), a name ("mahjong.example.com"), or a full ws(s):// or http(s):// address.
 */
export function serverUrl(input: string): string | null {
  let s = input.trim();
  if (!s) {
    if (!location.host) return null;
    return `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`;
  }
  if (/\s/.test(s)) return null;
  if (s.startsWith('https://')) s = 'wss://' + s.slice(8);
  else if (s.startsWith('http://')) s = 'ws://' + s.slice(7);
  // A name alone: TLS, but not on this machine (a server run here, or reached through an SSH tunnel).
  else if (!s.startsWith('wss://') && !s.startsWith('ws://')) s = (/^(localhost|127\.0\.0\.1|\[::1\])(:|\/|$)/.test(s) ? 'ws://' : 'wss://') + s;
  const rest = s.slice(s.indexOf('://') + 3);
  const host = rest.split('/')[0].replace(/^\[/, '').split(/[\]:]/)[0];
  if (!host) return null;
  // Without TLS only on this machine, or from a page itself served without it (a server on the local network).
  const local = ['localhost', '127.0.0.1', '::1'].includes(host);
  if (s.startsWith('ws://') && !local && location.protocol === 'https:') return null;
  return rest.includes('/') ? s : s + '/ws';
}

/** A player's name as shown: a table game's bots are 電腦 1 to 電腦 3. */
export function nameOf(p: PlayerView): string {
  return p.bot ? t('cpu_n', Number(p.name) || 1) : p.name;
}

/** Who is ranked, the most points first: everyone at a table game's table, bots too; a contest's people. */
export function standings(r: RoomView): PlayerView[] {
  const ranked = r.settings.mode === 'TABLE' ? r.players.filter((p) => !p.bot || p.seat >= 0) : r.players.filter((p) => !p.bot);
  return ranked.sort((a, b) => b.total - a.total);
}

/** Rank by standing, equal totals sharing a rank. */
export function rankOf(r: RoomView, p: PlayerView): number {
  return 1 + standings(r).filter((x) => x.total > p.total + 1e-9).length;
}

/** The battle server where none was ever set (the user's, 2026-11-13): the public one, the same host as the page's. */
export const DEFAULT_SERVER = 'mahjong.1li.tw';

const NAME_KEY = 'mahjong.battle.name';
const SERVER_KEY = 'mahjong.battle.server';
const CHOICES_KEY = 'mahjong.battle.choices';
const DEFAULT_NAME_KEY = 'mahjong.battle.default_name';

const LENGTH_OF = { hand: 'HAND', round: 'ROUND', game: 'GAME' } as const;

class Battle {
  name = $state(load<string>(NAME_KEY, ''));
  /** The name of a player who gives none: "玩家" and a number of their own. */
  readonly defaultName: string;
  /** Empty: the server of this page (the player cleared it); never set: DEFAULT_SERVER. */
  server = $state(load<string>(SERVER_KEY, DEFAULT_SERVER));
  pin = $state('');
  /** Connecting, or waiting for the room's answer. */
  busy = $state(false);
  reconnecting = $state(false);

  room = $state<RoomView | null>(null);
  /** When `room` arrived, for its countdown. */
  roomAt = $state(0);
  me = $state(-1);
  table = $state<MyTable | null>(null);
  outcomes = $state<HandOutcome[]>([]);
  /** The room is gone but its results stay to look at. */
  finished = $state(false);
  /** The last hand's result has been seen: the final standings now. */
  lastSeen = $state(false);
  /** The battle in the records (its hands are kept as they close). */
  battleId = $state<string | null>(null);

  private ws: WebSocket | null = null;
  private joined: { room: string; you: number; token: string } | null = null;
  /** Sent once the room says welcome: create, join or resume. */
  private pending: ClientMsg | null = null;
  private resumeUrl: string | null = null;
  private startedAt = 0;
  /** The table as it was before a decision was sent, back if the room refuses it. */
  private beforeAct: MyTable | null = null;
  /** The room's views not shown yet (when each came), and whether they are being shown (showViews). */
  private views: { msg: Extract<ServerMsg, { t: 'table' }>; at: number }[] = [];
  private showingViews = false;

  constructor() {
    let d = load<string>(DEFAULT_NAME_KEY, '');
    if (!d) {
      d = t('battle_default_name', 1000 + Math.floor(Math.random() * 9000));
      save(DEFAULT_NAME_KEY, d);
    }
    this.defaultName = d;
    // A reload in a room: its seat is taken back.
    const saved = loadSeat();
    if (saved) {
      this.joined = { room: saved.room, you: saved.you, token: saved.token };
      this.battleId = saved.battle;
      this.startedAt = saved.startedAt ?? Date.now();
      this.resumeUrl = saved.url;
      this.pending = { t: 'resume', room: saved.room, token: saved.token };
      this.open(saved.url);
    }
  }

  get playerName() { return this.name.trim() || this.defaultName; }
  /** In a room still going (the lobby too). */
  get inRoom() { return this.room !== null && !this.finished && this.room.phase !== 'OVER'; }
  /** At a table: the other screens' bar is hidden. */
  get playing() { return this.inRoom && this.room!.phase !== 'LOBBY'; }
  get over() { return this.room !== null && (this.finished || this.room.phase === 'OVER'); }
  get mine(): PlayerView | null { return this.room?.players.find((p) => p.id === this.me) ?? null; }
  get isHost() { return this.room !== null && this.room.host === this.me; }
  get contest() { return this.room?.settings.mode === 'CONTEST'; }

  /** The names at a table's seats: the player's own as "you" when `you`, others' by their names, bots numbered. */
  namesAt(seats: number[], you = true): string[] {
    let bot = 0;
    return seats.map((id) => {
      const p = this.room?.players.find((x) => x.id === id);
      // A contest's bots are no players: numbered by seat.
      if (!p) return t('cpu_n', ++bot);
      return you && id === this.me ? t('you') : nameOf(p);
    });
  }

  /** Seats whose person is away or has left (the bot decides for them). */
  awayAt(seats: number[]): boolean[] {
    return seats.map((id) => {
      const p = this.room?.players.find((x) => x.id === id);
      return !!p && !p.bot && (!p.connected || p.left);
    });
  }

  /** A table game's totals by seat; a contest has none to show at the table (its bots are not players). */
  totalsAt(seats: number[]): number[] | null {
    if (this.contest) return null;
    return seats.map((id) => this.room?.players.find((x) => x.id === id)?.total ?? 0);
  }

  /** The player's table of a closed hand. */
  myTable(o: HandOutcome): TablePlay | null {
    if (o.tables.length === 1 && !this.contest) return o.tables[0];
    const i = o.players.indexOf(this.me);
    return i >= 0 ? o.tables[i] : null;
  }

  /** A new room's settings: the rules page's rules, the practice length, the last time limit and hands chosen. */
  defaults(mode: Mode): RoomSettings {
    const c = load<{ hands?: number; timeLimit?: number }>(CHOICES_KEY, {});
    return {
      mode, length: LENGTH_OF[settings.length], hands: HAND_CHOICES.includes(c.hands ?? 0) ? c.hands! : 4,
      rules: $state.snapshot(settings.rules), timeLimit: TIME_LIMITS.includes(c.timeLimit ?? 0) ? c.timeLimit! : 15,
      // The network's bot (protocol Validation.BOTS); a server without it plays tile efficiency, and says so.
      bot: 'net',
    };
  }

  // ---- Into a room ----

  create(mode: Mode) {
    if (this.busy || this.inRoom) return;
    this.start({ t: 'create', settings: this.defaults(mode) });
  }

  join() {
    if (this.busy || this.inRoom) return;
    if (!/^\d{6}$/.test(this.pin)) return fail(t('battle_pin_bad'));
    this.start({ t: 'join', pin: this.pin });
  }

  private start(msg: ClientMsg) {
    save(NAME_KEY, this.name.trim());
    save(SERVER_KEY, this.server.trim());
    this.clear();
    const url = serverUrl(this.server);
    if (!url) return fail(t('battle_server_bad'));
    this.resumeUrl = url;
    this.pending = msg;
    this.open(url);
  }

  private open(url: string) {
    this.busy = true;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch (e) {
      this.busy = false;
      return fail(t('battle_no_connection', (e as Error).message));
    }
    this.ws = ws;
    ws.onopen = () => this.sendOn(ws, { t: 'hello', version: PROTOCOL_VERSION, name: this.playerName });
    ws.onmessage = (e) => {
      let msg: ServerMsg;
      try { msg = JSON.parse(String(e.data)); } catch { return; }
      this.onMessage(ws, msg);
    };
    ws.onclose = (e) => this.onDown(ws, e.reason || (e.code === 1006 ? t('battle_lost') : String(e.code)));
  }

  private sendOn(ws: WebSocket | null, msg: ClientMsg) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(msg));
  }

  private send(msg: ClientMsg) { this.sendOn(this.ws, msg); }

  // ---- In the room ----

  changeSettings(s: RoomSettings) {
    save(CHOICES_KEY, { hands: s.hands, timeLimit: s.timeLimit });
    this.send({ t: 'settings', settings: $state.snapshot(s) });
  }

  startGame() { this.send({ t: 'start' }); }

  /** The player's decision (one of the view's legal actions), on the view it was made on. */
  act(a: Action) {
    const tb = this.table;
    if (!tb || !tb.view.my_turn) return;
    this.send({ t: 'act', hand: tb.hand, step: tb.step, action: $state.snapshot(a) });
    // Taken at once: no second decision on the same view, until the room shows what follows.
    this.beforeAct = tb;
    this.table = { ...tb, view: { ...tb.view, my_turn: false, legal: [] }, deadline: null };
  }

  /** A declared ready hand (天聽, 地聽, 報聽) whose only choice is the tile it drew: discarded by itself at a computer
   *  player's pace (bot_turn), as in practice. A win or a concealed kong it may make waits for the player. */
  private forcedDiscard() {
    const tb = this.table;
    if (!tb) return;
    const v = tb.view;
    if (!v.my_turn || v.phase !== 'self' || v.legal.length !== 1 || v.legal[0].type !== 'discard') return;
    if (!v.position.players[tb.seat]?.declared) return;
    const a = v.legal[0];
    later(() => {
      const now = this.table;
      if (now && now.hand === tb.hand && now.step === tb.step && now.view.my_turn) this.act(a);
    }, ms('bot_turn'));
  }

  ready() { if (this.room) this.send({ t: 'ready', hand: this.room.hand }); }

  /** Leaves the room for good (the bot plays on for the player) and its results. */
  leave() {
    this.saveGroup(true);
    this.send({ t: 'leave' });
    const ws = this.ws;
    this.ws = null;
    // The Leave goes out before the close.
    setTimeout(() => ws?.close(), 300);
    this.clear();
  }

  /** Back from the results of a finished room. */
  dismiss() { this.clear(); }

  private clear() {
    saveSeat(null);
    this.reconnecting = false;
    const ws = this.ws;
    this.ws = null;
    ws?.close();
    this.joined = null;
    this.pending = null;
    this.room = null;
    this.me = -1;
    this.table = null;
    this.outcomes = [];
    this.battleId = null;
    this.startedAt = 0;
    this.finished = false;
    this.lastSeen = false;
    this.busy = false;
    this.beforeAct = null;
    this.views = [];
  }

  // ---- What the room says ----

  private onMessage(ws: WebSocket, msg: ServerMsg) {
    if (ws !== this.ws) return;
    switch (msg.t) {
      case 'welcome':
        if (this.pending) this.sendOn(ws, this.pending);
        break;
      case 'joined':
        this.joined = { room: msg.room, you: msg.you, token: msg.token };
        this.me = msg.you;
        this.busy = false;
        this.reconnecting = false;
        // A resume tells everything again.
        this.pending = { t: 'resume', room: msg.room, token: msg.token };
        this.keepSeat();
        break;
      case 'state': {
        this.room = msg.room;
        this.roomAt = Date.now();
        if (msg.room.phase !== 'LOBBY' && !this.battleId) {
          this.battleId = newId();
          this.startedAt = Date.now();
          this.keepSeat();
        }
        if (msg.room.phase === 'OVER') saveSeat(null);
        break;
      }
      case 'table':
        this.views.push({ msg, at: Date.now() });
        void this.showViews();
        break;
      case 'result':
        this.addOutcomes([msg.outcome]);
        break;
      case 'history':
        this.addOutcomes(msg.outcomes);
        break;
      case 'error': {
        this.busy = false;
        // A seat that cannot be taken back: the room is gone (or went on without the player).
        if (this.joined && msg.code === 'NO_ROOM' && this.pending?.t === 'resume' && (this.reconnecting || !this.room)) {
          this.reconnecting = false;
          if (!this.room) {
            this.clear();
            return fail(t('battle_closed'));
          }
          return this.roomGone(t('battle_closed'));
        }
        // A room asked for the network's bots that does not know them (a server older than this page): tile
        // efficiency.
        if (msg.code === 'BAD_SETTINGS' && !this.joined && this.pending?.t === 'create') {
          const create = this.pending as unknown as { t: 'create'; settings: RoomSettings };
          if (create.settings.bot !== 'greedy') {
            this.pending = { t: 'create', settings: { ...create.settings, bot: 'greedy' } };
            return this.sendOn(ws, this.pending);
          }
        }
        // A decision refused: the view it was made on, again; or it came too late (the bot made it when the time ran
        // out, and the table went on): nothing to say.
        if ((msg.code === 'BAD_ACTION' || msg.code === 'NOT_ALLOWED') && this.room?.phase === 'PLAYING') {
          if (this.beforeAct && this.table?.step === this.beforeAct.step) this.table = this.beforeAct;
          this.beforeAct = null;
          return;
        }
        fail(errorText(msg.code));
        if (!this.joined && ['VERSION', 'NO_ROOM', 'ROOM_FULL', 'STARTED', 'TOO_MANY', 'BUSY', 'RATE'].includes(msg.code)) {
          this.ws = null;
          ws.close();
        }
        break;
      }
      case 'closed':
        if (msg.reason === 'resumed elsewhere' || msg.reason === 'left') return;
        this.roomGone(this.room?.phase === 'OVER' ? null : t('battle_closed'));
        break;
    }
  }

  /**
   * The room's views, one at a time: each once the words and moments of the one before are over (the deal's too), as
   * practice shows them. The room sends them at that pace already (protocol Pace); a slower pace in the settings, or
   * a slow connection, waits here. The time to decide counts from when a view came.
   */
  private async showViews() {
    if (this.showingViews) return;
    this.showingViews = true;
    try {
      while (this.views.length) {
        const { msg, at } = this.views.shift()!;
        const cur = this.table;
        // A view carries the events since the last one: they are added to what was shown (all of them when full).
        const view = msg.full || !cur || cur.hand !== msg.hand
          ? msg.view
          : { ...msg.view, events: [...cur.view.events, ...msg.view.events] };
        this.table = {
          hand: msg.hand, seat: msg.seat, step: msg.step, seats: msg.seats, view,
          deadline: msg.remainingMs != null && view.my_turn ? at + msg.remainingMs : null,
        };
        this.beforeAct = null;
        this.forcedDiscard();
        // (The table's effects have run by then: what this view brings is up.) A view that comes meanwhile waits.
        await tick();
        await calloutsDone();
      }
    } finally {
      this.showingViews = false;
    }
  }

  private onDown(ws: WebSocket, reason: string) {
    if (ws !== this.ws) return;
    this.ws = null;
    const j = this.joined;
    if (!j || this.room?.phase === 'OVER') {
      if (!j) {
        this.busy = false;
        fail(t('battle_no_connection', reason));
      }
      return this.roomGone(null);
    }
    // A dropped connection in a game: the seat is kept for a while, so it is taken back.
    if (this.reconnecting) return;
    this.reconnecting = true;
    this.busy = true;
    const url = this.resumeUrl ?? serverUrl(this.server);
    (async () => {
      for (let attempt = 0; attempt < 6 && url; attempt++) {
        await sleep(1000 * 2 ** Math.min(attempt, 3));
        if (!this.reconnecting) return;
        this.pending = { t: 'resume', room: j.room, token: j.token };
        this.open(url);
        const mine = this.ws;
        await sleep(8000);
        if (!this.reconnecting) return;
        if (this.ws === mine) {
          this.ws = null;
          mine?.close();
        }
      }
      this.reconnecting = false;
      this.busy = false;
      this.roomGone(t('battle_lost'));
    })();
  }

  /** The room is gone; what it showed stays when anything was played. */
  private roomGone(message: string | null) {
    saveSeat(null);
    if (message) fail(message);
    const ws = this.ws;
    this.ws = null;
    ws?.close();
    if (this.outcomes.length === 0) this.clear();
    else this.finished = true;
  }

  private keepSeat() {
    const j = this.joined;
    if (j) saveSeat({ url: this.resumeUrl ?? '', ...j, battle: this.battleId, startedAt: this.startedAt || undefined });
  }

  private addOutcomes(list: HandOutcome[]) {
    const known = new Map(this.outcomes.map((o) => [o.index, o]));
    for (const o of list) known.set(o.index, o);
    this.outcomes = [...known.values()].sort((a, b) => a.index - b.index);
    void this.recordHands(list);
  }

  /** The player's table of each closed hand goes to the records (as a table battle's or a contest's hand). */
  private async recordHands(list: HandOutcome[]) {
    const r = this.room;
    const group = this.battleId;
    if (!r || !group) return;
    for (const o of list) {
      const index = o.index + 1;
      if (records.hands.some((h) => h.group === group && h.index === index)) continue;
      const tp = this.myTable(o);
      const seat = tp ? tp.seats.indexOf(this.me) : -1;
      if (!tp || seat < 0) continue;
      try {
        const v = await engine.view(tp.record, seat, 0, true);
        if (!v.result) continue;
        const bots = tp.seats.map((id) => {
          const p = r.players.find((x) => x.id === id);
          return p && !p.bot ? null : r.settings.bot === 'net' ? NET_BOT : r.settings.bot;
        });
        records.add(handRecord(r.settings.mode === 'TABLE' ? 'table' : 'contest', tp.record, v.result, seat,
          this.namesAt(tp.seats, false), bots, group, index));
      } catch { /* not kept: the engine could not replay it */ }
    }
    this.saveGroup();
  }

  /** The battle as it stands, in the records, once a hand has closed. */
  private saveGroup(left = false) {
    const r = this.room;
    if (!this.battleId || !r || this.outcomes.length === 0) return;
    const people = r.settings.mode === 'TABLE'
      ? [...r.players].filter((p) => p.seat >= 0).sort((a, b) => a.seat - b.seat)
      : r.players.filter((p) => !p.bot);
    records.addBattle({
      id: this.battleId, time: new Date(this.startedAt || Date.now()).toISOString(),
      kind: r.settings.mode === 'TABLE' ? 'table' : 'contest', via: 'online', rules: r.rules,
      names: people.map(nameOf), me: people.findIndex((p) => p.id === this.me),
      totals: people.map((p) => p.total), hands: this.outcomes.length, left: left || undefined,
    });
  }
}

/** The seat in a room, kept in this tab (sessionStorage) for a reload. */
interface Seat { url: string; room: string; you: number; token: string; battle: string | null; startedAt?: number }
const SEAT_KEY = 'mahjong.battle.seat';

function saveSeat(s: Seat | null) {
  try {
    if (s) sessionStorage.setItem(SEAT_KEY, JSON.stringify(s));
    else sessionStorage.removeItem(SEAT_KEY);
  } catch { /* off */ }
}

function loadSeat(): Seat | null {
  try {
    const s = JSON.parse(sessionStorage.getItem(SEAT_KEY) ?? 'null');
    return s && typeof s.url === 'string' && s.url && typeof s.room === 'string' && typeof s.token === 'string' ? s : null;
  } catch {
    return null;
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function fail(message: string) { shared.error = message; }

function errorText(code: string): string {
  switch (code) {
    case 'VERSION': return t('battle_err_version');
    case 'NO_ROOM': return t('battle_err_no_room');
    case 'ROOM_FULL': return t('battle_err_full');
    case 'STARTED': return t('battle_err_started');
    case 'TOO_MANY': case 'RATE': return t('battle_err_rate');
    case 'BUSY': return t('battle_err_busy');
    case 'BAD_SETTINGS': return t('battle_err_settings');
    case 'BAD_ACTION': return t('battle_err_action');
    default: return t('battle_err_not_allowed');
  }
}

export const battle = new Battle();
