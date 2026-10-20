# Local Liquid ColBERT SOP retrieval

The active choice is `LiquidAI/LFM2.5-ColBERT-350M`, following the decision to
use Liquid Foundation Models only. The development pipeline generates operating-procedure
token embeddings; the Android app now uses a local query encoder and bundled
index, with shared harness applicability checks and independent emergency routing.

The [BGE baseline](BGE_BASELINE.md) and its generated artifacts are retained as
a historical comparison. ColBERT does not load the BGE weights or vectors.
The two commands share only corpus validation and evaluation code.

## Model and encoding

- Official model commit: `9772bdf797255d8693b83e84aa98e9b2d36dd0be`.
- Source weights: approximately 707 MB, stored locally under
  `models/LFM2.5-ColBERT-350M/` and ignored by Git.
- Reference encoder: Sentence Transformers `MultiVectorEncoder`, using the
  model's bidirectional attention and non-causal convolution code.
- That official Python file was reviewed before execution. It patches
  Transformers process-wide, so run this encoder in its own process.
- Development reference: CPU, four threads, 32-bit model computation,
  scaled dot-product attention, one document per batch.
- Each document uses the complete `embedding_text` with `[D] `; queries use
  `[Q] `. These prefixes are applied by the model's saved configuration.
- The Python reference permits 512 document tokens and 32 query tokens, including prefix/special tokens; overlength inputs fail before encoding. The Android query path explicitly splits overlength observations into clauses rather than silently truncating them.
- Queries expand to 32 scoring vectors. Expansion tokens do not act as
  attention keys. Document padding and configured punctuation are excluded.
- Token vectors have 128 dimensions and are individually unit normalized.
- Exact MaxSim scoring sums each query token's best document-token similarity.
  No separate reranking model or vector database is required for 19 cards.

The source instructions and supported formats are described in the
[official model card](https://huggingface.co/LiquidAI/LFM2.5-ColBERT-350M).

## Generated bundle

`generated/colbert-baseline/` contains:

- `token_vectors.f32`: 3,705 token vectors, 1,896,960 bytes (about 1.9 MB).
- `manifest.json`: model and source hashes, encoding configuration, package
  versions, and each card's token-vector offset and count.
- `cards.json`: complete structured records, returned with ranked candidates.
- `policy.json`: shared policy, packaged separately from candidate retrieval.
- `evaluation.json`: rankings and timings for the common synthetic smoke set.

Vectors are concatenated row-major little-endian 32-bit floats, in manifest
card order. Offsets count token-vector rows, not bytes. Card input lengths
range from 136 to 376 tokens; none were truncated.

Changed source/policy, mismatched query encoders, altered files, invalid
offsets, and unnormalized vectors are rejected. Models, generated bundles,
and virtual environments are ignored by the local `.gitignore`.

## Run

Run from the project root. Python 3.14 was used. BGE and ColBERT have separate
dependency environments; do not upgrade the historical BGE environment.
The current ColBERT environment is `/private/tmp/zebra-sop-colbert-venv`.

```sh
uv venv sop_retrieval/.venv-colbert --python python3.14
uv pip install --python sop_retrieval/.venv-colbert/bin/python -r sop_retrieval/requirements-colbert.txt

sop_retrieval/.venv-colbert/bin/python scripts/sop_colbert.py fetch \
  --model-dir sop_retrieval/models/LFM2.5-ColBERT-350M \
  --revision 9772bdf797255d8693b83e84aa98e9b2d36dd0be

sop_retrieval/.venv-colbert/bin/python scripts/sop_colbert.py build \
  --model-dir sop_retrieval/models/LFM2.5-ColBERT-350M \
  --output sop_retrieval/generated/colbert-baseline

sop_retrieval/.venv-colbert/bin/python scripts/sop_colbert.py query \
  --model-dir sop_retrieval/models/LFM2.5-ColBERT-350M \
  --index sop_retrieval/generated/colbert-baseline \
  --text "My teammate has been coughing a lot" --k 3

sop_retrieval/.venv-colbert/bin/python scripts/sop_colbert.py evaluate \
  --model-dir sop_retrieval/models/LFM2.5-ColBERT-350M \
  --index sop_retrieval/generated/colbert-baseline \
  --report sop_retrieval/generated/colbert-baseline/evaluation.json

sop_retrieval/.venv-colbert/bin/python scripts/sop_colbert.py verify \
  --model-dir sop_retrieval/models/LFM2.5-ColBERT-350M \
  --index sop_retrieval/generated/colbert-baseline

sop_retrieval/.venv-colbert/bin/python -m unittest discover -s sop_retrieval -p 'test_*.py' -v
```

`fetch` is the only command requiring network access. It requires an empty
destination and records the resolved immutable revision and file hashes.
Skip it if the model is already downloaded. Build refuses to overwrite an
existing bundle; use a new output directory for changed sources.
All remaining commands load local files with network access disabled.

## Smoke evaluation — 2026-10-12

Both models use the unchanged 47-case set: 39 positive cases and eight
negative cases. All required cards must appear for a positive case to pass.

| Ranking depth | Liquid ColBERT | Historical BGE |
| --- | --- | --- |
| First candidate | 32 / 39 | 33 / 39 |
| First three | 39 / 39 | 38 / 39 |
| First five | 39 / 39 | 39 / 39 |

Mean required-card recall for ColBERT is 87.2% at the first candidate and
100% within the first three. Multi-card cases affect that metric differently
from the all-required-cards case count above.

The replenishment card now ranks second for "Order more perfume. There is
no spill or damage." The perfume-damage card still ranks first. For "I am
feeling sick on the warehouse floor," the temperature card ranks first and
the general unwell-worker card second; temperature must not be inferred.
"Not sick anymore" still retrieves the unwell-worker card. Cancellation
and unrelated stock queries also return candidates.

These synthetic development labels are not retailer-reviewed or held-out
evidence of general reliability. No abstention threshold is calibrated.
Negative cases provide diagnostics, not passed no-guidance tests. MaxSim
scores cannot be compared numerically with BGE cosine scores.

The host run measured about 146 ms median warm query encoding and 0.124 ms
median comparison across all cards, using CPU computation in 32-bit precision.
This is not Zebra performance or end-to-end assistant latency. Model loading
excludes Python imports and file-hash verification.

15 tests cover both bundle formats, stale inputs, corruption, token-vector
offsets, MaxSim arithmetic, and overlength rejection. The custom scorer was
also checked against `MultiVectorEncoder.similarity` across 38 actual
query/card pairs; maximum absolute score difference was about 0.000002.

## Verified Zebra deployment — 2026-10-12

The existing local weights were converted to Q8_0; no model-weight download or ColBERT training was required. The hash-verified query model is in app-private storage, and the 1.9 MB index/cards/policy bundle is included in the app. An on-demand app-owned CPU encoder runs through the packaged llama.cpp executable with embedding mode and no pooling. It is distinct from the one audio/text generation server.

Android uses `[Q] `, expansion to 32 query vectors, 128-dimensional per-token normalization, and exact MaxSim scoring over the trusted document vectors. Source/hash checks reject mismatched assets. Native/reference token IDs matched the inspected sample, but quantized token vectors are not numerically identical: sample mean token cosine was 0.9627683 and maximum absolute difference 0.12846.

| Review | Result |
| --- | --- |
| All required candidates within first three | 38 / 39 positive cases |
| Negative cases | Eight diagnostic cases; abstention was not established |
| Warm app encoding plus search | 123–154 ms in observed logs |
| First app encoding plus search | 1,119 ms |

The known miss was perfume leaking onto an arm: the candidates included SOP-03 but missed CARE-02. Independent exposure routing supplies CARE-02 and was tested without relying on embedding ranking. This does not establish general safety or warehouse retrieval reliability.

The harness applies shared policy, negation/applicability checks and independent emergency routing. Card guidance remains draft pending site review. Calibrated abstention, representative worker/noise evaluation, sustained performance and peak device memory remain pending.

Detailed integration and withdrawal evidence is retained locally under `output/` and is not distributed publicly. See the [current profiling runbook](../audio600m_profiling_runbook.md) for measurement boundaries. The Python results above are historical host-reference measurements, not the quantized Zebra result.
