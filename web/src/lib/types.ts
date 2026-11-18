// The engine's JSON forms (cpp/command/guiengine.h, docs/gui-engine.md), as the page uses them.
import type { Tile } from './tiles';

export interface Setup { dealer: number; east: number; round: number; streak: number }

/** A hand: rules, setup, the whole wall and every decision. Only the authority (practice here, a room) holds it. */
/** A hand: its rules, setup, wall and every decision; or (a review's branch, reshuffled; a position of the analysis
 *  played on) taken on from a seat's position with the tiles that seat had not seen dealt again by a seed (`base`, no
 *  wall: docs/gui-engine.md): its position in another hand (with another hand of tiles in place of its own, `hand`
 *  and `drawn`), or a position by itself. */
export interface GameRecord {
  rules: string;
  setup: Setup;
  wall?: string;
  base?: { game: GameRecord; seat: number; seed: string; hand?: string; drawn?: Tile | null } | { position: Position; seed: string };
  actions: (number | string)[][];
}

export type ActionType = 'discard' | 'tsumo' | 'ankan' | 'kakan' | 'pass' | 'ron' | 'pon' | 'kan' | 'chi' | 'declare';
export interface Action { type: ActionType; tile?: Tile | null; tiles?: Tile[] }

export type MeldType = 'chi' | 'pon' | 'kan' | 'ankan' | 'kakan';
/** tile null: another player's concealed kong. */
export interface Meld { type: MeldType; tile: Tile | null; tiles?: Tile[]; called?: Tile; from?: number }
/** `drawn`: the tile just drawn (摸切); `called`: taken by a call; `after_call`: the seat's first discard after a call of
 *  its own (an engine from 2026-11-19). */
export interface Discard { tile: Tile; drawn: boolean; called: boolean; after_call?: boolean }
/** `declared`: a ready hand declared (天聽, 地聽 on the first turn; 報聽 later: docs/rules.md 5.1); the hand is locked from then on. */
export interface PlayerPos { melds: Meld[]; flowers: string; discards: Discard[]; declared?: 'tianting' | 'diting' | 'baoting' }
export interface Claim { seat: number; tile: Tile | null; rob_kong: boolean }

export interface Position {
  setup: Setup;
  me: number;
  hand: string;
  drawn: Tile | null;
  after_call: boolean;
  claim: Claim | null;
  players: PlayerPos[];
  wall_left: number;
  /** The seat has passed a win this turn (過水): it may not win on a discard until its next draw. */
  missed?: boolean;
}

export type EventType = 'draw' | 'flower' | 'discard' | 'chi' | 'pon' | 'kan' | 'ankan' | 'kakan' | 'tsumo' | 'ron' |
  'flower_win' | 'exhausted' | 'declare';
export interface GameEvent { seat: number; type: EventType; tile: Tile | null; replacement?: boolean; drawn?: boolean; tiles?: Tile[];
  declared?: 'tianting' | 'diting' | 'baoting' }

export interface TaiItem { item: string; name: string; tai: number }
export interface Winner { seat: number; tai: number; items: TaiItem[] }
export interface Result {
  exhausted: boolean;
  winners: Winner[];
  loser: number;
  win_tile: Tile | null;
  self_draw: boolean;
  flower_win: boolean;
  dealer_tai: number;
  points: number[];
  base_delta: number[];
  tai_delta: number[];
  next_dealer: number;
  next_streak: number;
}

export type Phase = 'self' | 'react' | 'rob_kong' | 'over';
export interface View {
  rules: string;
  position: Position;
  phase: Phase;
  current: number;
  seat_winds: number[];
  my_turn: boolean;
  legal: Action[];
  events: GameEvent[];
  event_count: number;
  result: Result | null;
  hands?: string[];
  /** On the player's own turn to discard: each discard that leaves the hand ready, with what it then waits on. */
  ready?: Record<Tile, Tile[]>;
  /** With each of those, the tiles the hand then waits with (聽的相關牌組). */
  ready_tiles?: Record<Tile, string>;
  /** The player's own standing: 0 ready (聽牌), -1 complete, else how many tiles from ready (向聽); on their own turn
   *  after the best discard. */
  shanten?: number;
  /** Between the player's turns, a ready hand's tiles that it waits with ("" otherwise). */
  wait_tiles?: string;
}

export interface Pending { phase: Phase; current: number; pending: number[]; actions: number; event_count: number }

export interface Wait { tile: Tile; left: number; ron_tai: number; tsumo_tai: number }
/** Tile efficiency: the tiles from ready (shanten, -1: a win), the improving tiles left, and when ready the waits. */
export interface ActionFigures { action: Action; shanten: number; ukeire: number; waits: Wait[]; win_tai: number }
/** What the network adds for an action: the expected result in points (q), how often it would choose it (policy), the
 *  chances of winning (by self-draw: tsumo), of dealing in and of another's self-draw, the tai of a win, and the three
 *  parts of q (winning, dealing in, others' self-draws); for a discard, the chance that it deals in now (danger), per
 *  opponent (the seats 1, 2, 3 places after the deciding one) with the tai it would pay for. */
export interface NetFigures {
  q: number; policy: number; win: number; tsumo: number; deal_in: number; others_tsumo: number; tai: number;
  parts: [number, number, number]; danger?: number; danger_each?: number[]; danger_tai?: number[];
}
export type Figures = ActionFigures & Partial<NetFigures>;
/** What the network thinks of an opponent: the chances that it is ready, 1, 2, 3 or more tiles away, and per kind
 *  (tiles.ts KINDS) the chance that a discard of it deals in to it now, with how many tai. */
export interface Opponent { seat: number; ready: number[]; danger: number[]; danger_tai: number[] }
/** What the network expects of the deciding seat's own hand, as it would play it on (networks exported from
 *  2026-11-17): the chances of being ready within its next 1, 2, 4, 8 draws, and of each tai item (by its parameter)
 *  being in its win. */
export interface MeFigures { ready_within: number[]; tai_items: Record<string, number> }
/** `source` "efficiency": tile efficiency alone; "network": the network's figures too, the best action and its value. */
export interface Analysis {
  source: string; actions: Figures[]; best?: number; value?: number; policy_value?: number; opponents?: Opponent[]; me?: MeFigures;
}
/** What an opponent really held: the tiles from ready, whether it had passed a win this turn, and its waits. */
export interface Truth { seat: number; shanten: number; missed: boolean; waits: { tile: Tile; tai: number }[] }
/** A decision of a reviewed hand: the record's step, the index of the action taken in the analysis' actions, and
 *  what it lost against the best (points). */
export interface ReviewDecision { step: number; played: number; analysis: Analysis; truth: Truth[]; loss?: number }
/** A hand's review for a seat: result = first_value - loss + luck; `unit`: a typical payment under its rules. */
export interface Review {
  seat: number; decisions: ReviewDecision[]; result: number; first_value?: number; loss?: number; luck?: number; unit?: number;
}
/** A record's actions as a review steps through them. `taken`: the action took effect (everything on a seat's own
 *  turn; of the answers to a discard only the claim that was made; a pass never). `over`: for a claim that was not
 *  made, the index of the one that took its place (-1 otherwise). `last`: the action the play ended with (the first
 *  win; without one the last action taken; -1 without any). */
export interface Steps { taken: boolean[]; over: number[]; last: number }
/** After a draw of `tile` (`left` copies unseen): the best action, its figures and value, the second best and how much
 *  worse it is. */
export interface WhatIfDraw { tile: Tile; left: number; value: number; figures: Figures; action: Action; next: Action | null; next_loss: number }
/** On `seat`'s discard of `tile`: the options (a pass among them) and the best one's index. */
export interface WhatIfClaim { seat: number; tile: Tile; left: number; best: number; options: Figures[] }
export interface WhatIf { draws: WhatIfDraw[]; claims: WhatIfClaim[] }

export interface RulesInfo {
  spec: string;
  values: Record<string, number>;
  tai_presets: string[];
  flower_presets: string[];
  items: { param: string; name: string }[];
}

export const sameAction = (a: Action, b: Action) =>
  a.type === b.type && (a.tile ?? null) === (b.tile ?? null) && (a.type !== 'chi' || (a.tiles ?? []).join() === (b.tiles ?? []).join());

/** A record's action as an Action. */
export function recordAction(a: (number | string)[]): { seat: number; action: Action } {
  const action: Action = { type: a[1] as ActionType };
  if (a.length > 2) action.tile = a[2] as Tile;
  return { seat: a[0] as number, action };
}
