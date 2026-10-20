# Floor worker journeys

Updated 2026-10-20. The active worker profile has four model tools, local procedure retrieval and harness-owned controls. See [worker contract](contracts/worker_experience_v2.md), [tool contract](tool_contracts.md), and [runtime architecture](docs/ARCHITECTURE.md).

## Stock and location

Ask for stock or location by product name. A one-product read may include explicitly supplied color, size or volume. If none is spoken, return the product's matching variants without invented filters. Up to two product names may be looked up together, without variant/category filters.

Results use local catalog/inventory facts and concise labels. Keep different product results separate. Distinguish a missing catalog product, unavailable variant and a known variant with zero stock. Reads do not ask follow-up questions: return grounded candidates, not-found or a Product catalog fallback. Historical category/section/bin conversations are outside this narrowed demo scope.

## Replenishment

Request a positive additional quantity of a product's resolved catalog variant. A complete request proceeds directly to preview. Missing dimensions prompt only the short questions needed to distinguish remaining variants; a unique match needs no redundant question.

Complete field collection within the initial request and at most two follow-up replies, then offer a form. The model receives pending slots and must emit the correct accumulated proposal after a clarification. Do not confuse existing quantity with requested additional stock or invent unspoken variant values.

The worker reviews a readable preview and explicitly submits it. Confirmation creates one local ticket and increments the exact stock variant atomically. Preview, cancellation, stale confirmation and repeated confirmation cannot add stock. Saved outcomes show local creation, not implied remote assignment or upload.

## SOP Advisor and reporting

Spoken and typed concerns retrieve approved applicable procedure cards directly through the harness. Unsupported procedures or site facts are not generated. Relevant urgent guidance remains independent of ordinary model proposal success.

Guidance alone does not create a ticket. Each current guidance result offers Create a report? Yes prepares an original-wording preview without another generation call; No dismisses the offer. Submit remains a separate confirmation.

A direct explicit report request requires the model's `create_report()` proposal. The harness owns the report text and preview. Product damage and general concerns use the same reporting tool; no generated free-form report argument is required. Negated reporting is not reporting intent.

## Tasks and local views

Task lookup shows count, first two configured records, update time and a prompt to open Task View. The configured worker snapshot is synthetic. There is no task creation or conversational task editing.

My Tickets shows real confirmed local harness tickets. Product catalog and Operating Procedures provide browsable local facts/cards. Displayed guidance ends with the standard Operating Procedures footer.

## Voice, state and controls

Voice is primary, with Type instead available. Android offline recognition supplies visible wording and control/retrieval input. In generation routes, the original waveform goes to the private audio checkpoint; the transcript is not substituted as its input.

General and SOP Advisor have separate saved feeds. Switching modes does not print a chat message and invalidates pending drafts. Visible messages do not become cross-request model memory. Only the focused pending tool/fields, latest question and choices support a clarification.

Clear removes the selected mode's feed and pending context. Stop cancels long processing and pending context while preserving the feed. Cancel dismisses an unfinished request. Persisted tickets survive these controls. Confirmations are bound to the current request/revision; corrections require a fresh preview.

## Experience and evidence

Answers should be short, readable and grounded. The small grey duration under replies is full post-recording processing, while Model Lab charts show generation latency with their own filters and reset window.

Evaluate recognized wording, raw model tool/argument correctness, harness/retrieval correctness, confirmation/persistence, and worker-visible latency separately. Supplied-call tests do not establish model understanding. Current synthetic/device smoke evidence is limited; real-worker noise reliability and broader acceptance remain pending.
