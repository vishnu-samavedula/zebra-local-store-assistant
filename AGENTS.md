# Project agent instructions

## Communication

- Keep responses concise and precise by default. Expand only when the user requests more detail.
- Ground claims in factual evidence from the project, data, code, tests, or authoritative sources. Distinguish verified facts, assumptions, and proposals; state uncertainty when evidence is missing.
- Expand machine learning acronyms in user-facing responses rather than using the acronyms.

## Reasoning and development

- Execute only against goals and outcomes clarified and aligned with the user. If scope or the intended outcome is unclear, clarify before implementing; do not invent requirements or expand the task independently.
- This is an artificial intelligence and applied machine learning proof of concept. Think from first principles about the problem statement, its decomposition, the solution approach, the training approach, and data preparation.
- Connect technical decisions to the worker's problem and the intended demo behavior. Reassess assumptions instead of defaulting to a familiar architecture or training recipe.
- Stay anchored to `PROBLEM_DESCRIPTION.md` and the aligned proof-of-concept goals in `demo_poc_solution.md`. Evaluate proposed work against reliable warehouse interactions on Zebra devices, noisy conditions, intermittent connectivity, and the specific journeys being demonstrated.
- Welcome creative and unconventional ideas when they directly improve the problem being solved or the intended demo. Explain the connection and supporting evidence before proposing a change; novelty alone is not a reason to expand scope.
- Revisit the approach when evidence invalidates a key assumption, the current approach cannot meet an agreed outcome, or a proposed change materially alters scope, architecture, training, or data preparation. Explain the evidence, options, and tradeoffs, then align with the user before implementing the revised approach.
- For training and data preparation, consider what behavior must be learned, what evidence or data supports it, how examples represent the intended journeys, and how quality will be evaluated. Do not assume training is necessary without examining the problem.
- Favor a focused, high-quality demo with depth, reliable behavior, and coherent end-to-end journeys over broad, shallow feature coverage.
- Keep development moving. Do not introduce production infrastructure, hardening, or process that does not materially improve the proof of concept. Preserve the correctness and safeguards needed for a trustworthy demo.
- Follow sound coding practices: use clear names, cohesive modules, explicit interfaces, and focused changes. Avoid unnecessary abstractions, duplicated logic, speculative features, and low-value generated code.

## Documentation

- Keep each Markdown file at or below 500 lines. Split longer material into focused, linked documents instead of accumulating repetitive text.
- Check whether changes make the README, documentation, or other Markdown files stale. Identify the affected files and proposed updates, and confirm them with the user before editing. An explicit request naming the documentation changes is already confirmation for that scope.
- Keep approved documentation consistent with the actual implementation and distinguish planned behavior from implemented behavior.

## Sensitive information and model artifacts

- Maintain `.gitignore` rules for secrets, API keys, credentials, sensitive information, files containing customer or user names, model binaries, and model checkpoints. Use anonymized examples in tracked code and documentation.
- Ignore rules cannot detect sensitive content inside ordinary source files or remove already tracked files. Check intended changes before staging or committing; do not rely on `.gitignore` alone.
- If sensitive information or model artifacts are already tracked, flag the issue and align remediation with the user before removing files or rewriting history.

## Shared workspace

- The user works on this project across multiple tabs. Preserve concurrent work and unrelated changes; keep edits scoped to the current task.

