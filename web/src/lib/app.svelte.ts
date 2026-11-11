// What the screens share: the hand under review (and a step of it to go to, once), and an error to show.
export const shared = $state({
  reviewId: null as string | null,
  reviewStep: null as number | null,
  error: null as string | null,
});
