# Warehouse Companion Demo

> This is an aspirational brainstorming document for later warehouse breadth. The implemented
> frozen five-tool P1B contract and authoritative phase roadmap are in
> `CURRENT_STATE_AND_ROADMAP.md` and root `SPEC.md`. Historical tool names below are candidate
> future concepts, not the active contract.

## 1. Product concept

The TC501 acts as an offline warehouse and back-of-store companion for workers
handling receiving, putaway, retrieval, picking, loading, inventory checks and
exceptions.

The assistant is not a general chatbot. It is a low-latency voice interface to
a bounded set of local operational tools. It uses the worker's current context
to understand short, imperfect requests and turn them into validated actions.

```text
Worker context
  + zone
  + active task
  + scanned item/pallet
  + microphone audio
          |
          v
LFM2.5-Audio ASR + trained generative LFM2.5-350M tool caller
          |
          v
schema validation + policy + confirmation
          |
          v
local SQLite database and event queue
          |
          +--> immediate local response
          +--> delayed enterprise sync when connected
```

The demo should visibly prove that loss of connectivity does not prevent the
worker from retrieving cached information, recording events or completing an
allowed local workflow.

## 2. Core product claims to demonstrate

1. **Responsive offline interaction**: voice-to-action works without internet.
2. **Context-aware routing**: the same short phrase can be interpreted using
   the active warehouse segment, task and scanned entity.
3. **Structured execution**: the model proposes typed tools rather than free
   text being treated as an operation.
4. **Operational continuity**: writes are recorded locally with timestamps and
   queued for later synchronization.
5. **Evidence-aware exceptions**: an issue can include barcode, image and voice
   evidence without requiring cloud inference.
6. **Safe boundaries**: consequential changes require validation or user
   confirmation; unknown requests fail closed.

## 3. Personas

### Floor associate

- Finds stock and storage locations.
- Confirms picks, putaway and counts.
- Records damaged, missing or incorrect stock.
- Requests replenishment or supervisor assistance.

### Receiving associate

- Looks up expected shipments.
- Records arrived quantities and condition.
- Reports discrepancies, seal problems and damage.
- Assigns received pallets to staging or putaway.

### Loader

- Checks trailer and route readiness.
- Confirms pallets loaded or missing.
- Reports loading exceptions.
- Verifies that a pallet belongs to the selected trailer or dock.

### Supervisor

- Receives queued escalations.
- Reviews exception evidence.
- Approves sensitive inventory changes.
- Checks task and load progress.

## 4. Operational segments

The application should maintain the current segment as explicit state whenever
possible. It should not make the model infer it solely from a short utterance.

| Segment | Typical context | Relevant tool family |
|---|---|---|
| Receiving | Dock, ASN, supplier, trailer | receive, discrepancy, damage, stage |
| Putaway | Pallet/LPN, destination zone | locate bin, validate destination, confirm putaway |
| Retrieval/picking | Order, SKU, requested quantity | locate, pick, substitute, shortage |
| Loading | Dock, trailer, route, pallet | validate assignment, confirm load, load status |
| Inventory | Zone, bin, SKU | lookup, cycle count, adjustment request |
| Exception/safety | Location, asset, evidence | report issue, isolate area, notify supervisor |

Segment context can come from:

- The screen or workflow the worker opened.
- A scanned barcode, pallet or location.
- Assigned task metadata.
- Dock/zone configuration.
- Recent tool results.

## 5. Candidate local tool catalog

### Read-only tools

| Tool | Purpose |
|---|---|
| `ping` | Verify that app and local inference are ready |
| `find_stock` | Return locations and available quantity for an SKU |
| `get_location_contents` | List stock expected in a bin or staging area |
| `get_task_status` | Retrieve current work-task state |
| `get_inbound_status` | Retrieve expected/received shipment quantities |
| `get_load_status` | Retrieve trailer progress and missing pallets |
| `validate_pallet_assignment` | Check whether a pallet belongs to an order/trailer |
| `lookup_sop` | Retrieve a short locally cached operating procedure |

### Local write tools

| Tool | Purpose | Confirmation |
|---|---|---|
| `confirm_pick` | Record an item/quantity picked | Required when quantity differs from task |
| `confirm_putaway` | Record pallet/bin placement | Required on location mismatch |
| `record_receipt` | Record received quantity | Required on variance |
| `record_cycle_count` | Record observed count | Always preview before commit |
| `report_damage` | Create damage exception | No, unless inventory is adjusted |
| `report_shortage` | Create shortage exception | No |
| `report_safety_issue` | Create safety incident | No; immediately queue notification |
| `request_replenishment` | Queue replenishment request | No |
| `notify_supervisor` | Queue a structured notification | Preview recipient and reason |

The first demo should implement only five to eight tools. A large catalog makes
evaluation and the spoken story less clear.

## 6. Recommended demo story

The strongest demo is one connected shift narrative rather than unrelated
voice commands.

### Scene 1: local readiness

Worker: "Are you there, Zebra?"

Expected behavior:

```text
ping() -> "I am here. Local warehouse data is ready."
```

### Scene 2: retrieve stock

Worker: "Where are the blue nitrile gloves, size large?"

Expected call:

```json
{
  "tool": "find_stock",
  "arguments": {
    "query": "blue nitrile gloves size large"
  }
}
```

Expected response: identify the canonical SKU, primary bin, reserve bin and
available quantity. If two products match, ask a clarification question rather
than selecting one.

### Scene 3: confirm work

The worker scans the bin and SKU, then says:

> "Picked four for order 1047."

The app already knows the active order and scanned SKU. The model should fill
only the spoken quantity and propose `confirm_pick`. The application validates
the quantity against the assigned task before committing it locally.

### Scene 4: receiving exception

Worker at receiving:

> "We expected twelve cases. Ten arrived and two are damaged."

Expected behavior:

- Read current ASN/trailer from application context.
- Propose a receipt of ten good and two damaged cases.
- Create a damage exception.
- Offer to capture a photo.
- Queue the event for synchronization.

### Scene 5: visual evidence

Worker captures the damaged cartons. LFM2.5-VL-450M later produces a constrained
observation such as:

```json
{
  "visible_cartons": 2,
  "damage_indicators": ["crushed_corner", "torn_outer_packaging"],
  "image_usable": true
}
```

The VLM output is evidence attached to the exception. It does not independently
change inventory.

### Scene 6: offline continuity

The app shows that the receipt and exception are stored locally with a
`PENDING_SYNC` state. When simulated connectivity returns, a demo sync process
changes them to `SYNCED`.

## 7. Application context contract

The model should receive a small structured context block with every request:

```json
{
  "user": {
    "id": "EMP-017",
    "role": "RECEIVING_ASSOCIATE"
  },
  "segment": "RECEIVING",
  "zone": "DOCK-04",
  "active_task": {
    "type": "UNLOAD",
    "id": "TASK-9007"
  },
  "focused_entities": {
    "asn": "ASN-20031",
    "trailer": "TRL-782",
    "pallet": null,
    "sku": null
  },
  "connectivity": "OFFLINE"
}
```

Only tools relevant to this context should be included in the prompt. Context
is authoritative application state. The model may ask to change it, but must
not silently overwrite it.

## 8. Simulator data domains

The original P1A design used a deterministic local warehouse simulator and
Android SQLite directly; no warehouse-management backend is needed initially.

### Master data

- Products/SKUs, descriptions, synonyms, units and barcodes.
- Warehouse, zones, aisles, bins, docks and staging lanes.
- Pallet/LPN identifiers and container hierarchy.
- Users, roles and permitted tool sets.
- Suppliers, stores/customers and delivery routes.
- Equipment/assets such as forklifts, conveyors and dock doors.

### Transaction data

- Inbound ASNs, trailers and expected shipment lines.
- Outbound orders, waves and pick lines.
- Loading plans and pallet-to-trailer assignments.
- Putaway and replenishment tasks.
- Cycle-count tasks.
- Inventory balances and reservations.

### Event data

- Receipts and receipt discrepancies.
- Picks, shorts and substitutions.
- Putaway confirmations and location mismatches.
- Counts and adjustment requests.
- Damage and safety incidents.
- Notifications and supervisor acknowledgements.
- Offline sync queue with retry and conflict state.

### Knowledge data

- Short SOP passages.
- Escalation rules.
- Damage categories.
- Safety-response instructions.
- Tool descriptions and JSON schemas.

## 9. Minimum deterministic demo dataset

The first simulator seed should be small enough to understand by inspection:

| Entity | Initial quantity |
|---|---:|
| Products/SKUs | 100-200 |
| Product synonyms | 3-6 per important SKU |
| Warehouse zones | 6 |
| Storage bins | 40-60 |
| Docks | 6 |
| Pallets/LPNs | 40 |
| Inbound ASNs | 8-12 |
| Outbound orders | 15-25 |
| Active tasks | 20-30 |
| Equipment/assets | 10-15 |
| Users/personas | 6-10 |
| SOP passages | 20-30 |
| Seeded exceptions | 10-15 |

Include a small number of deliberate inconsistencies to drive the demo:

- Expected quantity does not equal received quantity.
- Pallet assigned to the wrong trailer.
- SKU stored in both primary and reserve locations.
- Pick request exceeds available quantity.
- Damaged stock still present in available balance.
- Duplicate spoken product descriptions.
- Pending offline event conflicts with a later server version.

## 10. Synthetic language data

Synthetic utterances are needed for prompting, evaluation and eventual
fine-tuning. They should not all be polished warehouse commands.

### Intent families

- Presence/readiness.
- Stock lookup and location lookup.
- Pick and putaway confirmation.
- Receipt confirmation and discrepancy.
- Count reporting.
- Damage/shortage/safety reporting.
- Replenishment request.
- Pallet/trailer validation.
- Task/load status.
- Supervisor notification.
- Clarification and cancellation.
- Out-of-domain conversation.

### Linguistic variation

For each intent, create:

- Direct commands: "Find SKU 8831."
- Natural questions: "Where did we put the large blue gloves?"
- Fragments: "Four picked, order 1047."
- Corrections: "Make that three, not four."
- Pronouns/deictic references: "Put this one on dock four."
- Warehouse shorthand: "Two shorts on the ASN."
- Hesitations and self-repair.
- Singular/plural and unit ambiguity.
- Similar-sounding SKU and location identifiers.
- Requests missing one required argument.
- Requests containing two legitimate actions.
- Requests that must be rejected or confirmed.

### Tool-call labels

Each utterance/episode should include:

```json
{
  "segment": "RECEIVING",
  "context": {},
  "utterance": "Ten good, two damaged.",
  "expected_tool_calls": [],
  "required_clarification": null,
  "requires_confirmation": true,
  "expected_final_response": {},
  "safety_tags": []
}
```

Do not train or score solely on exact final-response wording. Evaluate tool,
arguments, clarification behavior and policy outcome separately.

## 11. Synthetic audio data

Text paraphrases alone do not test the Audio model. Create audio variants with:

- Multiple voices, speaking rates and pitch ranges.
- Regional accents represented by licensed or consented voices.
- Close-talk, arm's-length and clipped microphone conditions.
- Masks or face coverings where recordings are consented.
- Push-to-talk clipping at the beginning and end.
- Pauses, false starts and corrections.
- Warehouse reverberation.
- Forklift movement and reverse alarms.
- Dock plates, pallet jacks, rolling carts and cardboard handling.
- Fans, HVAC, refrigeration and idling trucks.
- Distant speech and radio chatter.

Create controlled signal-to-noise buckets, for example clean, moderate and
difficult, and retain the original clean source. Do not mix evaluation audio
from the same base recording or voice into the training split.

Synthetic noise is useful for coverage but cannot replace recordings from the
real target environment. A later pilot should collect consented, non-sensitive
TC501 recordings across zones and shifts.

## 12. Synthetic vision data

For the VLM phase, create still images rather than full synthetic videos first:

- Correct and incorrect pallet labels.
- Readable, blurred, dirty and partially occluded labels.
- Intact, crushed, wet, torn and open cartons.
- Mixed-SKU pallets.
- Empty, partly filled and overfilled bins.
- Pallets in correct and incorrect staging lanes.
- Blocked aisles and floor spills.
- Trailer interiors at different loading stages.
- Low light, glare, motion blur and oblique camera angles.

Annotations should include:

- Scene and asset identifiers.
- Visible objects and counts.
- OCR truth where relevant.
- Damage/safety attributes.
- Image-quality attributes.
- Expected observation JSON.
- Whether the image is sufficient to support the requested action.

Do not teach the VLM to make the final inventory transaction. Its job is to
produce observations that the application and policy layer can use.

## 13. Multimodal episode data

The eventual audio-plus-vision demonstration needs linked episodes, not two
independent datasets:

```text
application context
  + spoken request
  + optional barcode
  + one or more images
  + tool results
  + expected action/clarification
```

Useful episode types:

- Worker refers to "this pallet" after scanning it.
- Spoken quantity conflicts with visible carton count.
- Audio reports damage but the image is unusable.
- Image suggests damage but worker reports a different issue.
- Wrong pallet is photographed for the active task.
- Worker corrects an earlier spoken quantity after the image is captured.

The expected behavior should prefer authoritative sources explicitly. Barcode
and WMS identifiers outrank model guesses; user confirmation outranks inferred
quantity; visual evidence should be labeled as an observation rather than fact.

## 14. Negative and adversarial data

Include cases where the correct outcome is no tool call, a clarification or a
rejection:

- Ordinary conversation and unrelated questions.
- Speech from another worker in the background.
- Commands spoken without the PTT interaction.
- Unknown SKU, pallet, dock or order.
- User lacks permission for the requested action.
- Request attempts to bypass confirmation.
- Multiple conflicting quantities.
- Stale active task or changed segment.
- A label/image containing prompt-injection text.
- Model output invents a tool or additional argument.
- Tool returns an error or conflict.
- Connectivity disappears during synchronization.

## 15. Data splits and target volumes

Maintain separate artifacts for three purposes.

### Deterministic demo fixtures

- A few carefully scripted scenarios.
- Stable IDs and known expected results.
- Resettable database state.
- Never used to claim general accuracy.

### Prompt and regression evaluation

Start with approximately:

- 12-15 intent families.
- 40-60 text cases per family.
- 3 audio conditions for important cases.
- 600-900 text cases total.
- 500-1,000 audio clips total.
- At least 20% negative, ambiguous or out-of-domain cases.

This set should be manually reviewed and frozen before fine-tuning.

### Fine-tuning data

Only create this after measuring the stock models. A reasonable first target is
5,000-20,000 high-quality conversational/tool traces, with deliberate coverage
of corrections, tool failures, clarification and no-action outcomes. Quality
and schema correctness matter more than raw synthetic volume.

Split by underlying scenario, speaker and base audio—not by individual
paraphrase—to prevent leakage.

## 16. Evaluation metrics

### Agent correctness

- Correct tool selection.
- Required argument exact match or slot F1.
- Invalid/malformed call rate.
- Unnecessary tool-call rate.
- Clarification accuracy.
- Confirmation-policy compliance.
- Out-of-domain false activation rate.
- Tool-error recovery accuracy.

### Audio robustness

- End-to-end tool accuracy by noise bucket.
- Tool accuracy by zone/noise type.
- Identifier and numeric-quantity accuracy.
- Push-to-talk clipping sensitivity.

### On-device performance

- Model load time.
- Button-release to first tool call.
- Tool-result to final response.
- Peak proportional set size.
- Battery consumption over repeated tasks.
- Thermal state and throttling during a scripted shift loop.

### Vision robustness

- Structured observation accuracy.
- Count accuracy.
- Damage/safety attribute precision and recall.
- Unusable-image rejection rate.
- Latency per captured image.

## 17. Suggested implementation sequence

1. Preserve the tested audio, repository and policy boundaries as the baseline.
2. Generate, train, evaluate and export the P1B generative tool caller through LQH.
3. Keep the trained action model behind the locked parser, policy and export checks.
4. Measure lexical/SQLite catalog resolution before starting P1C semantic retrieval.
5. Evaluate real warehouse audio before deciding whether P1D audio adaptation is needed.
6. Add local sync states only when a connected workflow becomes part of the demo.
7. Keep the frozen P1B five-tool contract behind deterministic parsing, policy and confirmation
   gates after its data, evaluation and export checks pass.
8. Add the connected shift story and resettable fixture.
9. Add VLM-450M still-image evidence only after the audio/tool loop is stable.

## 18. Decisions to make before generating bulk data

- Which warehouse vertical: retail backroom, general distribution, grocery,
  manufacturing parts or parcel logistics?
- Which two personas will appear in the demo?
- Which operational segment anchors the story?
- Which five to eight tools are in scope?
- Which actions require confirmation or supervisor approval?
- Whether the assistant speaks responses or uses text/beeps for the demo.
- Whether barcode/RFID is part of the story or represented as preloaded context.
- Which terminology, units and identifier formats the simulated warehouse uses.
- Which languages and accents must be supported.
- What latency target makes the offline experience visibly compelling.
