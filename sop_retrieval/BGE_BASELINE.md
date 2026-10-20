# Local BGE SOP embedding experiment

Historical comparison only. The active retrieval choice is Liquid ColBERT;
see the [current run guide](README.md). BGE artifacts are preserved, and the
ColBERT pipeline does not load or depend on the BGE model.

This isolated development pipeline embeds the 19 bundled SOP cards and ranks
retrieval candidates. It does not connect the Android app, generate worker
guidance, route emergencies, or execute warehouse actions.

## Encoding and bundle

- Model: `BAAI/bge-small-en-v1.5`.
- Baseline commit: `5c38ec7c405ec4b44b94cc5a9bb96e735b38267a`.
- Documents: each card's complete `embedding_text`, with no prefix.
- Queries: prefix `Represent this sentence for searching relevant passages: `.
- CLS pooling (the first token's representation), unit normalization,
  384 dimensions, 512-token maximum including special tokens.
- Inputs above the limit fail rather than silently truncate.
- Search: exact dot product of normalized vectors, equivalent to cosine similarity.
- The index stores full card records, stable IDs, versions, source/model hashes,
  token lengths, and package versions. Shared policy is a separate file.
- Source, policy, index, or query-model mismatches fail before retrieval.

The generated bundle contains `manifest.json`, `vectors.f32`, `cards.json`, and
`policy.json`. Vectors are row-major little-endian 32-bit floats, in the manifest's
card order. The vector file is 29,184 bytes. Cards occupy 134–366 tokens; none
were truncated. There is no calibrated similarity threshold or applicability
decision. Query results are candidates even when an unrelated message is entered.

Models and generated bundles are ignored by this directory's `.gitignore`.
Do not commit weights, binaries, or real worker queries. Evaluation examples
are synthetic and their expected-card labels are proposed development labels.

## Run

Run from the project root. Python 3.14 was used for this baseline; dependencies
are pinned in `requirements.txt`. The current local environment is
`/private/tmp/zebra-sop-bge-venv`; temporary environments may disappear.

```sh
uv venv sop_retrieval/.venv --python python3.14
uv pip install --python sop_retrieval/.venv/bin/python -r sop_retrieval/requirements.txt

sop_retrieval/.venv/bin/python scripts/sop_embeddings.py fetch \
  --model-dir sop_retrieval/models/bge-small-en-v1.5 \
  --revision 5c38ec7c405ec4b44b94cc5a9bb96e735b38267a

sop_retrieval/.venv/bin/python scripts/sop_embeddings.py build \
  --model-dir sop_retrieval/models/bge-small-en-v1.5 \
  --output sop_retrieval/generated/baseline

sop_retrieval/.venv/bin/python scripts/sop_embeddings.py query \
  --model-dir sop_retrieval/models/bge-small-en-v1.5 \
  --index sop_retrieval/generated/baseline \
  --text "My teammate has been coughing a lot" --k 3

sop_retrieval/.venv/bin/python scripts/sop_embeddings.py evaluate \
  --model-dir sop_retrieval/models/bge-small-en-v1.5 \
  --index sop_retrieval/generated/baseline \
  --report sop_retrieval/generated/baseline/evaluation.json

sop_retrieval/.venv/bin/python -m unittest discover -s sop_retrieval -p 'test_*.py' -v
```

`fetch` requires network access and an empty destination; it resolves and records
an immutable commit. `build`, `query`, and `evaluate` use local files with
network access disabled. Skip `fetch` if the model is already downloaded.
Build refuses to overwrite an existing bundle; choose a new output directory
when rebuilding changed sources. Default source paths are resolved relative
to the project, and can be overridden with `--cards` and `--policy`.

## Baseline findings — 2026-10-12

47 examples cover all 19 cards: 39 positive cases and eight negative cases.
For multi-card cases, all required cards must appear for the case to pass.

| Ranking depth | Cases with all required cards | Mean required-card recall |
| --- | --- | --- |
| First candidate | 33 / 39 | 89.7% |
| First three | 38 / 39 | 97.4% |
| First five | 39 / 39 | 100% |

The first-three miss is "Order more perfume. There is no spill or damage."
The replenishment card ranks fifth. Negated hazard terms still attract
incident cards. "My teammate is not sick anymore and has stopped coughing"
also scores highly against the unwell-worker card. A cancellation attracts
the replenishment card. Similarity alone cannot decide whether to give guidance.

Eight negative examples have score diagnostics only; they do not constitute
a passed abstention test. These cases are a development smoke set, not held-out
evidence of general reliability. Safety routing and response applicability
are not evaluated here.

One development-host CPU run, using four threads, measured roughly 5 ms median
warm query encoding. Vector comparison was about 0.014 ms median. These are
host measurements, not Zebra timings or an end-to-end latency claim. Model
load timing excludes Python imports and model-file verification.

Seven integrity tests cover record/vector alignment, changed sources/policy,
corruption, duplicate IDs, and refusal to encode overlength input.

## Next integration work

Generate query embeddings locally on the Zebra with this same model, tokenizer,
pooling, prefixes, and normalization. Verify the Android deployment's rankings
against this reference; repeat checks if quantizing. Measure latency and memory
alongside the resident assistant model before choosing the final runtime.

Retrieve candidate cards from the worker's situation and relevant context.
The harness must account for corrections, negation, completed incidents,
cancellations, and multiple applicable cards before presenting guidance.
Load shared policy independently and keep emergency routing independent of
similarity. The source policy's readiness text predates this experiment;
it is retained unchanged here. All card guidance remains draft pending site review.
