# The AI coach

The records page's **AI 講評 / AI coach** (2026-11-18, the user's): the player's latest hands of one set of rules,
reviewed by the network, are put in a digest and read by a language model with the player's own API key, which finds
the habits that keep coming back and explains them. In the app and on the web page alike.

Code: `android/app/src/main/java/io/github/kennyfs/mahjong/coach/` (Coach.kt the flow, Digest.kt the digest,
Providers.kt the requests, Keys.kt the keys) and `ui/CoachScreen.kt`; tests in `android/app/src/test/.../coach/`. The
page's is a port of it, word for word where it can be: `web/src/lib/coach/` (coach.svelte.ts, digest.ts,
providers.ts, keys.svelte.ts), `web/src/components/Coach.svelte` and `CoachKeys.svelte` (see "On the page" below).

## The division of work

The network does the mahjong; the model finds patterns and explains. Every figure in the digest comes from the
engine (the best action, each action's expected result, win and deal-in chances, the tai of a win, the danger of a
discard now and who would win on it, the opponents' chances of being ready, shanten, useful tiles, waits); the model
is told to take the network's best as the answer, never to work out tiles itself, to call something a habit only with
two or more decisions as evidence, and to judge decisions by their loss, not by the hand's result.

A first try (16 hands of the `greedy` bot under simple2, an answer written from the digest by hand) showed what the
model needs besides the figures: the rules that differ from riichi and from other house rules (no furiten, so a tile
an opponent discarded is not safe; 過水; the preset's own tai table, in which simple2 scores 清一色 1 tai and 碰碰胡
nothing), each action's tai of a win (a pair worth a tai is otherwise invisible), and the human cues for safety (who
discarded the kind, how many copies are on the table).

## What is sent

- **Which hands.** One set of rules at a time: the records grouped by the values of every parameter (whatever spec
  they were played under), named by the nearest presets (`簡單2・見花見台＋2 項自訂`); the latest 50, 100 or 200 of
  the group (100 at first; the choice is remembered), practice and battles alike, each hand marked vs computer or
  with people. Hands played with the analysis shown (顯示分析) are left out, as in the statistics. A hand the engine
  cannot replay is left out and counted.
- **The digest** (Digest.kt), in the app's language: the rules (each parameter by the rules page's name and help
  text, the tai items that score with their values and the ones that do not), a legend of the figures, the totals
  (results, the loss by grade and by kind, the times the network's best was not the obvious action and how often the
  player found it, the discards that dealt in), then per hand its title and its key decisions: questionable or worse
  (Review.kt's grades) or a notable best (notableOf). Each key decision: turn, wall, hand, the draw or the discard to
  answer, melds, flowers, the opponents' chances of being ready; the network's outlook for the hand (below); the
  played action, the best and the obvious one with their figures; why the played one is worse (reasonOf). At most
  150 key decisions (the costliest; a hundred hands have about 300); the rest count in the totals only. No names.
- **Ids.** `[H9-73]`: hand 9 of the digest (oldest first), step 73 of its record. The answer's ids are links that
  open the review at that step; an id that names no hand or step of the session stays plain text.
- **Size.** About 550 tokens a hand by Claude's count (48 hands in Chinese, 52 key decisions: 26k tokens with the
  instructions); 100 hands some 50k. A hand's part is kept (`coach-cache.json`, the latest 400 hands, by network and
  language; on the page, while it is open), so a second run reviews only the new hands.

The page shows the digest's size and an estimated price before anything is sent, and the whole text on request; the
first sending to each provider says what goes where (and, for Gemini, that its free tier uses the content).

## The network's outlook (two more outputs)

The actor has heads used only in training. Two of them were checked against what happened on 1,500 self-play hands
no network trained on (`actor_221.pt`, 2026-11-17) and are now exported (`python/mahjong/model.py` Shipped,
`python/export_actor.py`): **ready_within**, the chance of being ready within the seat's next 1, 2, 4, 8 draws
(calibrated within 0.01–0.03, AUC 0.82–0.92), and **tai_items**, the chance of each tai item in its win (means right,
AUC 0.72–0.96 for 見風, 三元, 門清, 獨聽). Both are per position, under the network's own play. The engine reads them
when the file has them (`cpp/learn/netmodel.cpp`; older files load as before) and adds `me` to the analysis
(docs/gui-engine.md); the digest gives the readiness while the hand is not ready, and the tai items likelier than 15%
given a win. Not exported: the opponents' copies held (calibrated, but not needed here) and an opponent's eventual
winning tile (barely better than the danger head).

A new export changes the network's id (BuildConfig.MODEL_ID), so every record's summary is reviewed again.

## The providers

The player's own key, straight from the phone to the provider; no server of ours. Raw HTTP on OkHttp (the app has it
for battles): three providers' SDKs would add much to the APK for one request each.

| | Models | Request | Thinking | JSON |
| --- | --- | --- | --- | --- |
| Claude | `claude-haiku-5-5`, `claude-sonnet-5-5` (default), `claude-opus-5-5` | Messages API, `anthropic-version: 2026-10-21` | adaptive, `output_config.effort` | `output_config.format` json_schema |
| Gemini | `gemini-4-hydrogen` | `v1beta/models/…:generateContent`, `x-goog-api-key` | `thinkingConfig.thinkingLevel` | `responseMimeType` + `responseJsonSchema` |
| ChatGPT | `gpt-6.1-sol` | Responses API, `store: false` | `reasoning.effort` | `text.format` json_schema, strict |
| Custom | the player's | `{url}/chat/completions` (OpenAI-compatible), https only | — | `response_format` json_schema; once more without it on a 400/422 |

**Model and thinking** (模型與思考, 2026-11-18): under the provider, a line names the model and the thinking
(`Claude Sonnet 5.5・思考：中`) with 變更, which opens a dialog: the provider's models with their prices, and the
thinking low, medium or high (each provider's own levels by these names: Claude's effort, Gemini's thinkingLevel in
capitals, OpenAI's reasoning effort). Kept per provider in the settings (`coachModels`, `coachEffort`); a session
keeps the model asked and the thinking (`asked`, `effort`), and questions after it use them. Gemini and ChatGPT have
one model each here (not known well enough to offer more); a custom server's model is the one in its settings, and no
thinking is sent to it. The default stays Sonnet at medium: reading a long digest is analysis, but the network has
done the hard part. Haiku costs a twentieth of it, for those who want it cheap; Opus twice.

Prices for the estimate (US$ per million tokens, input/cached/output, 2026-11-18): Claude Haiku 5.5 0.10/0.01/0.50,
Sonnet 5.5 2/0.10/10, Opus 5.5 4/0.20/20; Gemini 1.5/0.075/7.5; ChatGPT 2/0.10/10. The estimate counts the digest's
characters (Coach.tokens, times Claude's measured 1.2 for Claude) and guesses the answer and its thinking by the
level: 2k, 3k, 6k tokens. Claude Sonnet at medium, 48 hands: US$0.07 the review (1.6k tokens of answer, no thinking),
about as much again each question after it; 100 hands about twice that.

The answer is JSON (COACH_SCHEMA): `summary`, `habits` [{`title`, `body`, `refs`, `rule`}], `strengths`, `practice`.
Questions after it are answered in plain text. Follow-ups send the whole conversation again, the model's turns as they
came (Claude's content blocks with their thinking, Gemini's content with its thought signatures, as those APIs ask),
at the full price: nothing is marked for Claude's prompt cache. A question is asked without the review's format, and a
change of `output_config.format` changes the prompt, so it cannot read what the review wrote to the cache; a write
costs a quarter more than input. Tried first with the digest marked: the review and its question each wrote the whole
prompt, US$0.15 billed for what costs US$0.13 unmarked.

The instructions (`systemPrompt` in Coach.kt) are one English text; the answer's language is the app's (zh-TW or
en-US), the player's level (the settings' 失誤門檻) sets how much to say.

Errors are shown with the provider's own words: a wrong key, credit or rate limits, a refusal, an answer cut off, the
network. Nothing is retried by itself.

**Tried** (2026-11-18, on the emulator, 48 hands of simple2 見花見台 in Chinese): Claude with a real key, a review in
25 s (four habits, every id a link that opens its step), a question after it in 8 s; Gemini with a wrong key, its 400
`API key not valid` shown as a wrong key. Not tried with a real key: Gemini, ChatGPT, a custom server; their request
shapes follow each provider's documentation of 2026-11-17 (Gemini's for this model shows generateContent's fields but
no example with `thinkingLevel`; its enum values are written in capitals as the reference lists them). The unit tests
check the shapes and the reading of documented answers.

On the page (2026-11-18, headless Firefox, the same 48 hands imported): the same digest (52 key decisions, about 26k
tokens; the outlook lines there); Claude with a wrong key, its 401 read across origins and shown as a wrong key; then
an answer mocked at the network level: the request named the chosen model and thinking (Haiku, low) and carried the
browser header, the ids opened the review at their steps and Back came back to the answer, a question after it sent
the conversation again. Not tried on the page with a real key, nor any provider but Claude.

## On the page

The same flow and words (the strings are the app's), with what a page can do instead:

- **Requests** with `fetch`, straight from the browser to the provider (no server of ours). Claude's take the header
  `anthropic-dangerous-direct-browser-access: true`, which Anthropic asks of a page that calls it with the user's
  own key. Gemini's and OpenAI's APIs answer a page's requests by their documentation; a custom server must allow
  them (CORS), and the error for an unreachable service says so.
- **Keys** in the browser's storage (`mahjong.coach.keys`), not encrypted: a page has no key store like Android's.
  The settings and the key dialog say so, and that a shared computer should not keep one; Delete removes it. They go
  only to their provider, when the player sends.
- **Kept**: the latest five sessions in the browser's storage (`mahjong.coach.sessions`; the oldest go first when
  there is no room beside the records); the hands' parts of the digest while the page is open.
- **Links** open the review over the coach's page (nav.open's `over`): Back comes back to the answer.
- The engine: the page's WebAssembly engine must be built with a network that has the two outputs (web/README.md);
  without them the digest has no outlook lines, as with an older network in the app.

## The keys

`coach_keys.xml` (CoachKeys): each key encrypted with AES-GCM by a key of the Android Keystore that never leaves it,
the file left out of backups and device transfers (`res/xml/data_extraction_rules.xml`, `backup_rules.xml`; a key
restored elsewhere could not be opened anyway, and is then dropped). The settings show only whether a key is set;
pasting a new one replaces it, Delete removes it. (EncryptedSharedPreferences is deprecated; this is what it did.)

## Kept on the phone

`coach.json`: the latest five sessions (the hands' ids, the instructions, the conversation, tokens and cost), so that
an answer and its links survive a restart and can be asked more about.
