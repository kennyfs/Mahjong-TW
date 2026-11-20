/** The glossary term a link in the tips' or the guide's words opened (Linked.svelte); App.svelte shows the glossary at
 *  it, over everything (not inside the paragraph the link is in). */
export const glossaryLink = $state<{ term: string | null }>({ term: null });
