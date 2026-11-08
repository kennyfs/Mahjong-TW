// What the screens share: the hand under review, the analysis asked for, and an error to show.
import type { Action, GameRecord, Position } from './types';

export interface AnalysisTarget {
  /** A decision of a recorded hand (the record cut there, and the seat deciding), or a position set up by hand. */
  game?: GameRecord;
  seat?: number;
  rules?: string;
  position?: Position;
  /** The action the player took there, marked in the list. */
  chosen?: Action | null;
  names?: string[];
}

export const shared = $state({
  reviewId: null as string | null,
  /** A review of a hand not in the records (a battle's, kept with the battle). */
  analysis: null as AnalysisTarget | null,
  error: null as string | null,
});
