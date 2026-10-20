# Warehouse Demo Catalog

The catalog reuses all 48 parent products from the Northstar store PoC at
`Search_Intelligence_mylfmo_assistant/storefront/data/catalog.json`.
`store_catalog.json` is a local snapshot. Product names are preserved except
"Pace 01 Runner", which becomes "Pace Runner", and the six fragrances,
whose display and spoken names are "North one", "After Rain", "Soft Hours",
"Citrus fresh", "Cedar", and "irish paper", as requested.
Source IDs and original product facts are preserved; these are demo labels.

`warehouse_catalog.jsonl` contains 384 demo SKU variants. Each declared color
and size combination is represented as a demo variant; the source does not
assert that every combination exists as a sellable SKU. Fragrance volume is
represented as size, with no color. Barcodes are not generated.

Each variant has a unique SKU and product ID, its source parent product ID,
spoken labels, aliases, category-specific attributes, and a source image path
where available. Image files have not been copied into this project.
Spoken labels retain distinct merchandising colors; basic color substitutions
are not assumed to be equivalent. Sock quantities are packs, with pairs per
pack recorded in `attributes.pack_count`.

Stock quantities, reserved quantities, and locations are deterministic synthetic
demo data, not actual retailer inventory. Available stock is on-hand minus
reserved. Every variant has its own inventory record, including some with zero
stock. Each parent product has its own section (for example, Section A1).
Within that section, each color/size variant has one dedicated bin (Bin 1,
Bin 2, etc.). Bin numbers restart within each section; a section/bin pair is
never shared across products or variants. Section codes are unique across
the 48 parent products. The largest section has 24 bins.

SKUs are unique four-digit identifiers, `SKU-1001` through `SKU-1384`.
Records contain separate `section` and numeric `bin` fields plus a readable
`location`, such as `Section A2 · Bin 2`. Parent records also contain their
section. Product IDs, names, variants, and stock quantities are preserved.

`warehouse_tasks.jsonl` is an earlier empty dataset, not the active assignment source. `industrial_task_snapshot.json` supplies the current synthetic worker, task records and update time to both Task View and harness lookup. The embedded harness initializes its own private SQLite catalog from the bundled records and persists created tickets; it does not use the historical version-6 repository migration described in older app records. No migration/reset is pending as part of the current training work.

To regenerate the catalog patch from the original store source:

```sh
node scripts/build_store_catalog.mjs /path/to/storefront/data/catalog.json
```

Apply the emitted patch with `apply_patch`. Review inventory assumptions when
the detailed demo journeys are defined.

## SOP Retrieval Data

`sop_cards.jsonl` contains 19 structured records: 11 incident SOPs, five
worker-care cards, and three assistance cards. Each has a stable `id`, title,
type, draft status, version, trigger examples, guidance, escalation, and an
`embedding_text` field. Optional fields include worker safety, clarification,
report destination, call script, location lookup, required site data, demo
rules, and sources.

Embed `embedding_text`, store the vector against `id`, and return the full
matched record to the agent. Multiple cards may apply to one concern, such
as a perfume leak plus skin exposure. These are source records. [Liquid ColBERT retrieval](../sop_retrieval/README.md) generates token vectors for all 19 cards; the index/cards/policy are bundled in Android and its local query encoder is deployed on Zebra. The shared harness applies policy and applicability checks with independent emergency routing. The earlier BGE baseline is historical comparison material.

`sop_policy.json` contains shared agent rules and fallback guidance. Load it
independently of retrieval. Emergency routing must not depend solely on the
closest embedding match. Contacts, exits, addresses, and medical-box locations
require separate verified site data.

`../SOP_GUIDE.md` remains the human-readable review copy. Keep both copies
consistent when editing content. All guidance is a draft pending site review.

`SOP-11` adds synthetic replenishment-readiness guidance, and `CARE-05` adds
synthetic unwell-worker guidance informed by public health precautions.
Both are demo drafts, not retailer-approved procedures. Their `short_response`
fields provide concise text for the intended harness response; adding source
records does not implement proactive retrieval or rebuild an installed app.
