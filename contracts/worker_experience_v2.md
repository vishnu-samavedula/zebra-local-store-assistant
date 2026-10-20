# Worker experience v2

Status: implemented worker flow; updated 2026-10-20 against the app/harness and retained source/device evidence. Raw model interpretation, recognition reliability and full worker acceptance remain separate from supplied-call harness checks. Private checkpoint development is maintained separately.

## App surface

Separate continuous activity feeds for General and SOP Advisor. Switching modes displays that mode’s saved messages without adding a chat message. No chat-thread selection or new-chat workflow. Clear deletes only the selected mode’s visible and locally saved activity feed and resets pending workflow context. Stop appears at the talk/send position while a request is processing; it cancels processing and pending context while preserving the feed. Cancel dismisses an unfinished request without deleting the feed. Saved tickets and the selected mode survive these controls. Clear and Stop are unavailable during an atomic ticket save. Changing modes cancels pending drafts and invalidates their confirmation. Each mode’s feed stores at most 200 messages locally; older thread preferences remain preserved but are not replayed or displayed in this feed.

Visible feed is not model context. Supply only the pending tool, its grounded fields, missing fields, latest question and verified options. No completed-request history. Original report turns remain harness evidence, not model-generated fields.

## Modes and public model tools

| Mode | Public tool | Responsibility |
|---|---|---|
| General | `inventory_lookup` | Product-name stock/location search. One product with optional supplied variants, or up to two names without variant/category filters. |
| General | `request_replenishment` | Grounded catalog product, exact variant and additional quantity. |
| General | `task_lookup` | Assigned-task count, first two and local snapshot freshness. No task creation. |
| SOP Advisor | `create_report()` | Explicit worker reporting intent. Harness preserves original words and prepares a preview. No model-generated description, location or product claims. |

After every SOP guidance response, show Create a report? with Yes / No (also accepted as exact spoken/typed replies). Yes is explicit worker intent for that exact response: the harness prepares an original-wording preview without another generation call. No dismisses the offer and creates nothing. Submit remains a separate confirmation before saving. Offers are bound to one current guidance turn and invalidated by a new request, mode change, Clear or Stop.

SOP Advisor guidance is a direct harness → ColBERT retrieval path, available for audio and text. It does not require a generation-model tool call. Only source cards passing applicability checks are shown. Independent urgent guidance remains available. No supported card means no invented procedure or site facts.

“My colleagues are coughing” means guidance only. “I want to report colleagues coughing” can retrieve guidance and prepare a report. Guidance similarity never authorizes a write. Negated reporting does not authorize a report.

Direct reporting requests still require a valid model-selected report call; accepting the harness-owned guidance offer is the explicit control-path exception. Otherwise the harness must not silently turn an unrelated model operation into a report. For compatibility, legacy concern/damage tool names are accepted only as explicit SOP-report aliases and normalized to the unified report behavior. This compatibility path does not define new training targets. New reports persist as create_report; legacy persisted report rows remain readable.

## Reads

Answer in one turn. Do not ask read clarification questions or retain a read conversation. Ambiguous locations or bins return up to three grounded candidate records with section/bin labels and optional tap selection. A selected record is read through the harness without model inference. Unknown products return not-found. Insufficient scope returns a concise catalog fallback, not guessed filters. Allow up to two named products in one lookup, shown separately; these two-product calls carry no variant or category filters. Category/section/bin journeys from the historical profile are outside this narrowed training scope. Broader results can be inspected in Product catalog.

## Writes

Only replenishment and reporting can create tickets. Complete writes in one to three worker turns: the initial request plus at most two short follow-up questions establish missing fields. Replenishment needs product name, positive additional quantity and one resolved catalog variant. Ask only for dimensions that distinguish matching variants; a unique catalog match needs no unnecessary question. Submit is a separate harness control, not a fourth model turn. After that, offer a form rather than more model-led questions. The form supplies an explicit catalog variant and positive additional quantity for replenishment, or original worker-entered details for a report.

Every complete write produces a readable preview. Submit/confirmation is harness-controlled and must refer to its request key and current revision. Corrections invalidate old confirmation. No automatic save, unconfirmed save or duplicate ticket. Confirmed tickets appear in My Tickets. Each proposed replenishment write is reviewed and confirmed separately.

Workers can cancel pending requests, stop processing or clear the feed. Switching modes is a request boundary. Confirmation never authorizes task creation or procedures beyond the bundled cards. Confirmed replenishment creates its ticket and increments the exact resolved local stock variant atomically; other reports do not change inventory.

## Audio and timing

Android transcribes recorded audio before the worker question is shown. The original waveform remains the generation-model input in General and for model-selected reports. SOP guidance uses transcript text directly for retrieval. Transcription failure prevents downstream work and must not become fabricated worker words.

Model Lab charts measure generation only, with first-text/completion filters and a reset window. Private timing records also distinguish transcription, generation, harness processing and total post-recording processing. The small grey time under assistant replies shows that broader processing interval, excluding recording duration and screen rendering.

## Verification boundaries

Separate supplied-call harness correctness, actual retrieval/voice behavior, raw private-model interpretation and processing latency. Score raw tools and arguments before harness recovery; harness success cannot hide a wrong model operation or invented variant. Successful artifact loading does not establish worker acceptance.

The legacy schema and reference fixtures retain historical compatibility cases. The active tool contract documents this narrowed worker profile. The runtime rejects more than two calls, filtered two-product reads and category/bin/section calls before executing a query. Approved scope replies remain allowed.

Detailed model-development, corpus and evaluation evidence is maintained privately. This worker contract does not authorize training or establish broad model accuracy.
