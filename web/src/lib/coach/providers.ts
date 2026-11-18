// The language models the AI coach can ask (docs/coach.md): with the player's own key, straight from the browser to
// the provider; nothing goes through a server of ours. A port of the app's coach/Providers.kt: the same requests, the
// same models and prices. Each provider's request asks for the answer as JSON of COACH_SCHEMA (its structured output),
// thinking as much as the player chose (Effort; medium at first).

/** How much a model thinks before it answers: more may be more careful, and is slower and costs more (thinking is
 *  billed as output). Each provider's own levels by these names (Claude's effort, Gemini's thinkingLevel, OpenAI's
 *  reasoning effort); a custom server is sent none. */
export type Effort = 'low' | 'medium' | 'high';
export const EFFORTS: Effort[] = ['low', 'medium', 'high'];
export const effortOf = (id: unknown): Effort => (EFFORTS.includes(id as Effort) ? (id as Effort) : 'medium');
/** The tokens of thinking and answer, roughly, for the estimate (Claude's answer to 48 hands at medium: 1.6k). */
export const OUTPUT_GUESS: Record<Effort, number> = { low: 2000, medium: 3000, high: 6000 };

/** A model a provider offers here, with its US$ per million tokens: input, input read from the provider's cache,
 *  output (thinking included). The providers' published prices (2026-11-18), for the estimate only. */
export interface CoachModel { id: string; label: string; inPrice: number; cachedPrice: number; outPrice: number }

export type ProviderId = 'claude' | 'gemini' | 'openai' | 'custom';
export interface Provider {
  id: ProviderId;
  label: string;
  /** Cheapest first; `defaultModel` unless the player chose another. */
  models: CoachModel[];
  defaultModel: string;
  /** Where a key is made, as words (not a link: the providers move their pages). */
  keySite: string;
  /** Its tokenizer's count against tokens(): Claude's, measured (26k tokens of an estimated 22k). */
  tokenScale: number;
}

export const PROVIDERS: Provider[] = [
  {
    id: 'claude', label: 'Claude', defaultModel: 'claude-sonnet-5-5', keySite: 'platform.claude.com', tokenScale: 1.2,
    models: [
      { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5', inPrice: 0.1, cachedPrice: 0.01, outPrice: 0.5 },
      { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5', inPrice: 2, cachedPrice: 0.1, outPrice: 10 },
      { id: 'claude-opus-5-5', label: 'Claude Opus 5.5', inPrice: 4, cachedPrice: 0.2, outPrice: 20 },
    ],
  },
  {
    id: 'gemini', label: 'Gemini', defaultModel: 'gemini-4-hydrogen', keySite: 'aistudio.google.com', tokenScale: 1,
    models: [{ id: 'gemini-4-hydrogen', label: 'Gemini 4 Hydrogen', inPrice: 1.5, cachedPrice: 0.075, outPrice: 7.5 }],
  },
  {
    id: 'openai', label: 'ChatGPT', defaultModel: 'gpt-6.1-sol', keySite: 'platform.openai.com', tokenScale: 1,
    models: [{ id: 'gpt-6.1-sol', label: 'GPT-6.1 Sol', inPrice: 2, cachedPrice: 0.1, outPrice: 10 }],
  },
  /** Any server that speaks OpenAI's Chat Completions (OpenRouter, a company's gateway, a local model behind HTTPS):
   *  its address and model are the player's. No price is known. It must allow a page's requests (CORS). */
  { id: 'custom', label: '', defaultModel: '', keySite: '', tokenScale: 1, models: [] },
];

export const provider = (id: string) => PROVIDERS.find((p) => p.id === id);
/** A provider's model of this id, else its default (undefined for a custom server: its model is the player's). */
export const modelOf = (p: Provider, id?: string | null) => p.models.find((m) => m.id === id) ?? p.models.find((m) => m.id === p.defaultModel);

/** A server of the Chat Completions kind: its base address (…/v1) and the model to ask. */
export interface CustomServer { url: string; model: string }
export const customReady = (c: CustomServer) => !!c.url.trim() && !!c.model.trim();

/** A turn of the conversation. `raw`: the provider's own form of an answer, sent back as it came (Claude's content
 *  blocks with their thinking, Gemini's content with its thought signatures). */
export interface Turn { user: boolean; text: string; raw?: unknown }

/** An answer: its text, its own form (Turn.raw), the tokens it took (input, of which read from the cache and written
 *  to it, output). */
export interface Reply { text: string; raw?: unknown; input: number; cached: number; output: number; model: string; written: number }

export type ErrorKind = 'KEY' | 'QUOTA' | 'REFUSED' | 'TRUNCATED' | 'NETWORK' | 'SERVER' | 'BAD_ANSWER';
export class CoachError extends Error {
  constructor(public kind: ErrorKind, public detail: string) { super(detail); }
}

/** What the coach answers first: JSON of this form, in every provider's structured output. */
export const COACH_SCHEMA = {
  type: 'object',
  properties: {
    summary: { type: 'string' },
    habits: {
      type: 'array',
      items: {
        type: 'object',
        properties: { title: { type: 'string' }, body: { type: 'string' }, refs: { type: 'array', items: { type: 'string' } }, rule: { type: 'string' } },
        required: ['title', 'body', 'refs', 'rule'],
        additionalProperties: false,
      },
    },
    strengths: { type: 'string' },
    practice: { type: 'string' },
  },
  required: ['summary', 'habits', 'strengths', 'practice'],
  additionalProperties: false,
};

/** The tokens one answer may take, its thinking included. */
const MAX_OUTPUT = 16000;

type Json = Record<string, any>;

class HttpError extends Error {
  constructor(public code: number, public body: string) { super(`HTTP ${code}`); }
}

/** A request's answer as JSON (an HTTP error: HttpError, which ask turns into a CoachError). `signal` cancels it. */
async function post(url: string, body: Json, headers: Record<string, string>, signal?: AbortSignal): Promise<Json> {
  let r: Response;
  try {
    r = await fetch(url, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body), signal });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    // (A browser hides why a request failed: no network, or a server that does not allow a page's requests.)
    throw new CoachError('NETWORK', String((e as Error).message ?? e));
  }
  const text = await r.text();
  if (!r.ok) throw new HttpError(r.status, text);
  try {
    return JSON.parse(text);
  } catch {
    throw new CoachError('BAD_ANSWER', text.slice(0, 300));
  }
}

/** An HTTP error's kind by its status, with the provider's own words for it (each puts them in "error"). */
export function httpError(code: number, body: string): CoachError {
  let words: string | null = null;
  try {
    const e = JSON.parse(body).error;
    words = typeof e === 'string' ? e : typeof e?.message === 'string' ? e.message : null;
  } catch { /* not JSON */ }
  words ??= body.slice(0, 300);
  // (Gemini answers a key it does not know with 400 INVALID_ARGUMENT, reason API_KEY_INVALID.)
  const kind: ErrorKind = code === 401 || code === 403 || (code === 400 && (body.includes('API_KEY_INVALID') || body.includes('API key not valid'))) ? 'KEY'
    : code === 402 || code === 429 ? 'QUOTA' : 'SERVER';
  return new CoachError(kind, `HTTP ${code}: ${words}`);
}

/**
 * Asks provider `p`'s `model` (its id; a custom server's own) with `key`, thinking at `effort`: `system`, then the
 * turns (the last one the user's). With `schema`, the answer is JSON of it. Throws CoachError (an AbortError when
 * cancelled).
 */
export async function ask(
  p: ProviderId, model: string, effort: Effort, key: string, custom: CustomServer, system: string, turns: Turn[], schema: Json | null,
  signal?: AbortSignal,
): Promise<Reply> {
  try {
    switch (p) {
      case 'claude':
        // (A page's request to Anthropic's API says so: its key is the player's own, in their own browser.)
        return parseClaude(await post('https://api.anthropic.com/v1/messages', claudeBody(model, effort, system, turns, schema),
          { 'x-api-key': key, 'anthropic-version': '2026-10-21', 'anthropic-dangerous-direct-browser-access': 'true' }, signal), model);
      case 'gemini':
        return parseGemini(await post(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
          geminiBody(effort, system, turns, schema), { 'x-goog-api-key': key }, signal), model);
      case 'openai':
        return parseOpenai(await post('https://api.openai.com/v1/responses', openaiBody(model, effort, system, turns, schema),
          { Authorization: `Bearer ${key}` }, signal), model);
      case 'custom': {
        if (!customReady(custom)) throw new CoachError('KEY', '');
        const url = custom.url.trim().replace(/\/+$/, '') + '/chat/completions';
        if (!url.startsWith('https://')) throw new CoachError('NETWORK', 'https:// only');
        const auth: Record<string, string> = key ? { Authorization: `Bearer ${key}` } : {};
        try {
          return parseChat(await post(url, chatBody(custom.model.trim(), system, turns, schema), auth, signal), custom.model.trim());
        } catch (e) {
          // Not every such server knows a JSON schema: once more without it (the prompt asks for the JSON too).
          if (schema && e instanceof HttpError && (e.code === 400 || e.code === 422)) {
            return parseChat(await post(url, chatBody(custom.model.trim(), system, turns, null), auth, signal), custom.model.trim());
          }
          throw e;
        }
      }
    }
  } catch (e) {
    if (e instanceof HttpError) throw httpError(e.code, e.body);
    throw e;
  }
}

const num = (x: unknown) => (typeof x === 'number' ? x : 0);
const str = (x: unknown) => (typeof x === 'string' ? x : null);

// ---- Claude (the Messages API) ----

export function claudeBody(model: string, effort: Effort, system: string, turns: Turn[], schema: Json | null): Json {
  return {
    model,
    max_tokens: MAX_OUTPUT,
    system: [{ type: 'text', text: system }],
    // Not marked for the prompt cache: a question after the review is asked without the review's format, which
    // changes the prompt, so it could not read the cache, and writing one costs a quarter more.
    messages: turns.map((t) => ({ role: t.user ? 'user' : 'assistant', content: !t.user && Array.isArray(t.raw) ? t.raw : t.text })),
    output_config: { effort, ...(schema ? { format: { type: 'json_schema', schema } } : {}) },
  };
}

export function parseClaude(o: Json, model: string): Reply {
  const stop = str(o.stop_reason);
  if (stop === 'refusal') throw new CoachError('REFUSED', JSON.stringify(o.stop_details ?? ''));
  if (stop === 'max_tokens') throw new CoachError('TRUNCATED', 'max_tokens');
  if (!Array.isArray(o.content)) throw new CoachError('BAD_ANSWER', JSON.stringify(o).slice(0, 300));
  const text = o.content.filter((b: Json) => b?.type === 'text').map((b: Json) => str(b.text) ?? '').join('');
  const u = o.usage ?? {};
  const cached = num(u.cache_read_input_tokens), written = num(u.cache_creation_input_tokens);
  return { text, raw: o.content, input: num(u.input_tokens) + written + cached, cached, output: num(u.output_tokens), model: str(o.model) ?? model, written };
}

// ---- Gemini (generateContent) ----

export function geminiBody(effort: Effort, system: string, turns: Turn[], schema: Json | null): Json {
  return {
    systemInstruction: { parts: [{ text: system }] },
    // A model turn goes back as it came, with its thought signatures (the API asks for them unchanged).
    contents: turns.map((t) => (!t.user && t.raw && typeof t.raw === 'object' && !Array.isArray(t.raw) ? t.raw
      : { role: t.user ? 'user' : 'model', parts: [{ text: t.text }] })),
    generationConfig: {
      maxOutputTokens: MAX_OUTPUT,
      thinkingConfig: { thinkingLevel: effort.toUpperCase() },
      ...(schema ? { responseMimeType: 'application/json', responseJsonSchema: schema } : {}),
    },
  };
}

export function parseGemini(o: Json, model: string): Reply {
  const block = str(o.promptFeedback?.blockReason);
  if (block) throw new CoachError('REFUSED', block);
  const cand = o.candidates?.[0];
  if (!cand) throw new CoachError('BAD_ANSWER', JSON.stringify(o).slice(0, 300));
  const finish = str(cand.finishReason);
  if (finish === 'MAX_TOKENS') throw new CoachError('TRUNCATED', finish);
  if (finish && finish !== 'STOP') throw new CoachError('REFUSED', finish);
  const content = cand.content;
  if (!content) throw new CoachError('BAD_ANSWER', JSON.stringify(cand).slice(0, 300));
  const text = (Array.isArray(content.parts) ? content.parts : []).filter((p: Json) => p?.thought !== true).map((p: Json) => str(p.text) ?? '').join('');
  const u = o.usageMetadata ?? {};
  return {
    text, raw: content, input: num(u.promptTokenCount), cached: num(u.cachedContentTokenCount),
    output: num(u.candidatesTokenCount) + num(u.thoughtsTokenCount), model: str(o.modelVersion) ?? model, written: 0,
  };
}

// ---- OpenAI (the Responses API) ----

export function openaiBody(model: string, effort: Effort, system: string, turns: Turn[], schema: Json | null): Json {
  return {
    model,
    instructions: system,
    input: turns.map((t) => ({ role: t.user ? 'user' : 'assistant', content: t.text })),
    reasoning: { effort },
    max_output_tokens: MAX_OUTPUT,
    // Not kept on OpenAI's side for later retrieval (its default is to keep it 30 days).
    store: false,
    ...(schema ? { text: { format: { type: 'json_schema', name: 'coach_review', strict: true, schema } } } : {}),
  };
}

export function parseOpenai(o: Json, model: string): Reply {
  if (o.status === 'incomplete') {
    const why = str(o.incomplete_details?.reason) ?? '';
    throw new CoachError(why === 'max_output_tokens' ? 'TRUNCATED' : 'REFUSED', why);
  }
  if (!Array.isArray(o.output)) throw new CoachError('BAD_ANSWER', JSON.stringify(o).slice(0, 300));
  const parts: Json[] = o.output.filter((x: Json) => x?.type === 'message').flatMap((x: Json) => (Array.isArray(x.content) ? x.content : []));
  const refusal = parts.find((x) => x?.type === 'refusal');
  if (refusal) throw new CoachError('REFUSED', str(refusal.refusal) ?? '');
  const text = parts.filter((x) => x?.type === 'output_text').map((x) => str(x.text) ?? '').join('');
  const u = o.usage ?? {};
  return { text, input: num(u.input_tokens), cached: num(u.input_tokens_details?.cached_tokens), output: num(u.output_tokens), model: str(o.model) ?? model, written: 0 };
}

// ---- Chat Completions (custom) ----

export function chatBody(model: string, system: string, turns: Turn[], schema: Json | null): Json {
  return {
    model,
    messages: [{ role: 'system', content: system }, ...turns.map((t) => ({ role: t.user ? 'user' : 'assistant', content: t.text }))],
    ...(schema ? { response_format: { type: 'json_schema', json_schema: { name: 'coach_review', strict: true, schema } } } : {}),
  };
}

export function parseChat(o: Json, model: string): Reply {
  const choice = o.choices?.[0];
  if (!choice) throw new CoachError('BAD_ANSWER', JSON.stringify(o).slice(0, 300));
  const refusal = str(choice.message?.refusal);
  if (refusal) throw new CoachError('REFUSED', refusal);
  if (choice.finish_reason === 'length') throw new CoachError('TRUNCATED', 'length');
  const u = o.usage ?? {};
  return {
    text: str(choice.message?.content) ?? '', input: num(u.prompt_tokens), cached: num(u.prompt_tokens_details?.cached_tokens),
    output: num(u.completion_tokens), model: str(o.model) ?? model, written: 0,
  };
}

/** US$ an answer cost by the model's prices (null without them: a custom server). A write to the prompt cache costs a
 *  quarter more than input. */
export function costOf(m: CoachModel | undefined, r: Reply): number | null {
  if (!m || m.inPrice <= 0) return null;
  return ((r.input - r.cached + r.written * 0.25) * m.inPrice + r.cached * m.cachedPrice + r.output * m.outPrice) / 1e6;
}

/** The answer's JSON object, from its text: as it is, or the outermost {…} of it (a model that wrapped it in words or
 *  a code fence, when its server took no schema). */
export function answerJson(text: string): Json | null {
  const obj = (s: string) => {
    try {
      const o = JSON.parse(s);
      return o && typeof o === 'object' && !Array.isArray(o) ? (o as Json) : null;
    } catch {
      return null;
    }
  };
  const whole = obj(text.trim());
  if (whole) return whole;
  const a = text.indexOf('{'), b = text.lastIndexOf('}');
  return a >= 0 && b > a ? obj(text.slice(a, b + 1)) : null;
}

/** Rough tokens of a text: about one a CJK character and one per 3.5 other characters (the providers' tokenizers
 *  differ: Provider.tokenScale; an estimate). */
export function tokens(text: string): number {
  let cjk = 0;
  for (const ch of text) if (ch.codePointAt(0)! >= 0x2e80) cjk++;
  return Math.floor(cjk * 1.3 + (text.length - cjk) / 3.5);
}
