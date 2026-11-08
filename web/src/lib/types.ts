// The engine's JSON forms (cpp/command/guiengine.h, docs/gui-engine.md), as the page uses them.
import type { Tile } from './tiles';

export interface Setup { dealer: number; east: number; round: number; streak: number }

/** A hand: rules, setup, the whole wall and every decision. Only the authority (practice here, a room) holds it. */
export interface GameRecord {
  rules: string;
  setup: Setup;
  wall: string;
  actions: (number | string)[][];
}

export type ActionType = 'discard' | 'tsumo' | 'ankan' | 'kakan' | 'pass' | 'ron' | 'pon' | 'kan' | 'chi';
export interface Action { type: ActionType; tile?: Tile | null; tiles?: Tile[] }

export type MeldType = 'chi' | 'pon' | 'kan' | 'ankan' | 'kakan';
/** tile null: another player's concealed kong. */
export interface Meld { type: MeldType; tile: Tile | null; tiles?: Tile[]; called?: Tile; from?: number }
export interface Discard { tile: Tile; drawn: boolean; called: boolean }
export interface PlayerPos { melds: Meld[]; flowers: string; discards: Discard[] }
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
}

export type EventType = 'draw' | 'flower' | 'discard' | 'chi' | 'pon' | 'kan' | 'ankan' | 'kakan' | 'tsumo' | 'ron' |
  'flower_win' | 'exhausted';
export interface GameEvent { seat: number; type: EventType; tile: Tile | null; replacement?: boolean; drawn?: boolean; tiles?: Tile[] }

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
}

export interface Pending { phase: Phase; current: number; pending: number[]; actions: number; event_count: number }

export interface Wait { tile: Tile; left: number; ron_tai: number; tsumo_tai: number }
export interface ActionFigures { action: Action; shanten: number; ukeire: number; waits: Wait[]; win_tai: number }
/** Tile efficiency now (`source` "efficiency"); a network later adds fields per action, which a screen shows when present. */
export interface Analysis { source: string; actions: (ActionFigures & Record<string, unknown>)[] }

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
