# P1B schema-ablation smoke test

Date: 2026-09-22

Device: Zebra TC501, QCM6690, Android 15

Model: trained LFM2.5-350M P1B Q8_0

Harness: `LiquidTextServerDeviceTest.schemaAblationSmoke`

Sample: 10 prompts covering five tools, multi-read, clarification and rejection

## Results

| Runtime prompt | Correct expected behavior | Valid native calls on 8 actionable prompts | Required arguments | Warm median | Average prompt tokens | Average generated tokens |
|---|---:|---:|---:|---:|---:|---:|
| Full JSON schemas | 8/10 | 8/8 | 5/5 | 4,925 ms | 539.5 | 68.1 |
| Compact signatures | 2/10 | 0/8 | 0/5 | 2,941 ms | 186.4 | 65.7 |
| Schema-free | 2/10 | 0/8 | 0/5 | 1,437 ms | 95.4 | 34.7 |

The two apparent matches in each reduced-prompt mode are the two cases that expected no call.
Neither reduced mode produced a valid Liquid-native call for an actionable prompt. Compact mode
often emitted an unwrapped or fenced Python-like call; schema-free mode produced prose, invented
Python functions or fabricated answers.

The full-schema checkpoint selected and populated every positive tool case correctly, including
all five required-argument checks. It failed the two negative/safety expectations: an incomplete
damage report produced a proposed call with invented fields, and a weather question produced an
empty `report_issue` call. Android policy still prevents unsafe execution, but these remain model
quality regressions.

## Decision

The current checkpoint was trained to call tools conditionally on request-level schemas. Removing
the schemas is a material distribution shift, not a safe runtime-only optimization.

Train P1B-v2 from the base instruct checkpoint using the same reviewed tool-call targets but:

1. remove request-level JSON tool schemas;
2. use one short, frozen schema-free system contract in train, eval and Android;
3. retain Liquid-native Pythonic calls as assistant targets;
4. emphasize clarification, rejection and no-call safety cases;
5. evaluate schema-free on the frozen held-out set before GGUF conversion;
6. accept only if tool/argument accuracy is retained and malformed/unsafe calls do not regress.

The Android parser, allowlist, argument validation, confirmation gates and deterministic adapters
remain unchanged. This restriction applied to v1; balanced schema-free v4 later passed the locked
cloud, local llama.cpp and TC501 gates and now defaults to `SCHEMA_FREE`.

## P1B-v2 training result

The schema-free derivative retained the reviewed 295/100 train/eval semantics, removed all tool
definitions, and replaced every system message with
`prompts/warehouse_tool_agent_schema_free_v1.md`. No constrained decoder or response schema was
used.

| Run | Held-out mean | Scored | Cost |
|---|---:|---:|---:|
| Base instruct, schema-free | 5.36 | 99/100 | $0.271 |
| P1B-v2 LoRA, schema-free | 9.00 | 100/100 | $0.244 |

The trained checkpoint emitted parseable native calls with the correct tool name on all 40 tool
targets. After dropping `None` optionals as Android does, 29/40 exactly matched the full reference;
10 additional replenishment calls differed only by an absent optional `reason`, for 39/40 matches
on execution-critical arguments. One correction utterance retained the first quantity instead of
the corrected quantity. The ten safety, negation, multiple-write and missing-field cases at eval
indices 90–99 all scored 10/10.

## Promoted replacement

Correction-only v3 fixed the known quantity correction but regressed parallel and conditional
behavior, so it was rejected. Balanced v4 added 60 judge-approved examples across every tool plus
no-call safety and passed the release gate: 17/17 exact normalized actionable first turns, 3/3
no-call cases and 40/40 execution-critical held-out actions. Its Q8_0 GGUF also passed 10/10 local llama.cpp cases and
the physical TC501 instrumentation test.
