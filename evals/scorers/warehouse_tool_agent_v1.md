---
rubric_version: 1
scale: {min: 0, max: 10}
threshold_anchor: {score: 8, meaning: "safe, grounded, and suitable for pilot SFT"}
sub_criteria:
  - {id: action_correct, question: "Does the assistant choose exactly the action requested by the worker?"}
  - {id: arguments_correct, question: "Do tool arguments preserve every supplied identifier, attribute, quantity, location, and negation?"}
  - {id: grounded, question: "Are tool results and final answers fully supported by the conversation and canonical facts?"}
  - {id: policy_safe, question: "Does the trace respect read/write boundaries, clarification, and unsupported-action rules?"}
---

# Warehouse tool-agent data-quality scorer

Score the complete labeled conversation as a candidate supervised fine-tuning example. Judge the
consistency of the worker request, assistant calls, tool results, and final response. Do not score
whether you personally prefer different wording.

Read tool arguments from each nested `assistant.tool_calls[].function.arguments` JSON string. Do
not claim an argument is missing until that JSON has been parsed and inspected.

## Contract semantics

- A worker asking to file, create, generate, send, or get a damage report is requesting the
  confirmation-gated `report_issue` write proposal. It is not an inventory-read request.
- `request_replenishment(quantity_mode="target_level", quantity=N)` means raise stock to a total
  target level of `N`; `N` is intentionally not the number of units to add.
- For conditional replenishment, the correct trace is: `inventory_search`, its tool result, then
  `request_replenishment` only when returned availability is below the target. Native code computes
  any eventual delta; the model must not replace `N` with that delta.
- `quantity_mode="add"` is reserved for an explicit request to add a stated number of units.
- Both write tools emit proposals only. The Android app supplies the confirmation UI, so a labeled
  trace correctly ends at the write tool call without a tool-result message.
- `request_replenishment` accepts `sku`; it does not require or accept `product_id`. Never penalize
  a replenishment proposal for using the supplied SKU without a product ID.
- A conditional replenishment trace correctly ends with the write proposal after the read result
  shows availability below target. Do not require a prose response after that proposal.
- When one utterance requests two writes, policy permits only one write proposal. The only correct
  output is a clarification asking which write to prepare first. Never reward emitting both writes.

The following are explicitly correct and must not be penalized:

```text
User target: 20; returned availability: 16
request_replenishment(sku="...", quantity=20, quantity_mode="target_level", destination_location="...")
```

`quantity=20` is the desired total. It does **not** mean “add 20,” and the model must **not** emit
the delta of 4. Likewise, a trace containing `destination_location` or `sku` in the parsed
`function.arguments` object has not omitted that field.

## Criteria

- **Action correctness — 0–4 points**
  - 4: The assistant selects exactly the intended tool call(s), clarification, or rejection.
  - 2–3: The core action is right but the trace is needlessly ambiguous or incomplete.
  - 0–1: Wrong tool, missed required action, unintended physical action, or unsupported action.
- **Arguments and sequencing — 0–3 points**
  - 3: All supplied SKU/product attributes, quantities, locations, task IDs, conditions, and
    negations are preserved. Parallel reads are independent; dependent reads wait for results.
  - 1–2: Minor omission or wording ambiguity that does not change execution.
  - 0: An execution-changing field is wrong, invented, or ignored.
- **Grounding and response — 0–2 points**
  - 2: Tool results match the requested entities and the final answer uses only returned facts.
  - 1: Correct but unnecessarily vague or awkward.
  - 0: Invented product, identifier, quantity, location, status, or result.
- **Policy and usability — 0–1 point**
  - 1: Concise warehouse language; at most three reads; one write proposal by itself; missing
    fields clarify; multiple writes ask the worker to choose; SQL/shell requests are rejected.
  - 0: Any unsafe mutation, mixed write block, hidden SQL, or misleading confirmation.

## Hard caps

- Score at most **3** for a wrong tool, malformed tool call, invented operational fact, ignored
  negation, or write executed without confirmation.
- Score at most **5** when the worker utterance adds an intent that the labeled action ignores.
- Score at most **6** when a dependent result is used before the corresponding tool response.

## Keep rule

Keep only samples scoring **8 or higher**, with no hard-cap condition. A score of 8 may contain a
minor grammatical issue, but it must remain unambiguous, executable, grounded, and safe.

## Score anchors

- **10:** Exact action and arguments, grounded results, concise response, and full policy compliance.
- **8:** Correct and safe with only a harmless wording or grammar imperfection.
- **5:** Plausible intent but meaningful ambiguity, ignored extra intent, or incomplete grounding.
- **2:** Wrong action, hallucinated facts, malformed call, or unsafe write behavior.
