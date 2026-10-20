# Demo proof of concept

Updated 2026-10-20. The goal is a focused, responsive assistant for warehouse floor workers on Zebra devices, with local inference and retrieval after provisioning. See [problem](PROBLEM_DESCRIPTION.md), [worker experience](contracts/worker_experience_v2.md), and [architecture](docs/ARCHITECTURE.md).

## Worker journeys

General mode answers stock/location requests for one product with optional explicit variants, or up to two product names without variants/category filters. It also summarizes assigned tasks and prepares replenishment requests. Reads are one-shot, with grounded candidates or a catalog fallback when necessary.

Replenishment resolves product, required variant dimensions and positive additional quantity. Allow at most two short clarification replies after the opening request, then offer a form. A complete request shows a readable preview and separate Submit. Confirming creates one local ticket and increments only the resolved stock variant once.

SOP Advisor retrieves approved cards from typed text or recognized speech without generation. Guidance alone creates no ticket. The current guidance response offers Create a report? Yes prepares an original-wording preview; No dismisses the offer. Submit is still required. A direct explicit report request requires a valid model-selected `create_report()` proposal before preview.

## App behavior

Separate General and SOP Advisor feeds share the same worker interface. Mode switches do not print chat messages. Visible feeds are not model memory. Clear resets the selected feed/pending context; Stop cancels long processing and pending context while retaining the feed. Saved tickets persist.

My Tickets shows real confirmed local harness tickets. Task View shows the configured synthetic worker snapshot; chat uses count/first two/freshness. Product catalog and Operating Procedures provide browsable local views. No task creation or cross-request conversational memory is part of the demo.

Model Lab separates generation latency from the grey post-recording processing time shown under replies. Latency charts use milliseconds, mode filters, percentile views, Refresh and Reset. Reset opens a new measurement window without erasing diagnostics.

## Runtime and public/private boundary

The app uses one app-owned llama.cpp CPU generation server for voice and text and a separate local ColBERT encoder for procedure retrieval. The checkpoint and its development process are private. Public source includes the app, harness, contracts and synthetic demo seed data; required inference artifacts are provisioned separately.

Acceleration experiments are isolated from the app. The tested neural processing unit prefill-to-CPU decode handoff was functional, but short worker requests did not show a reliable speed advantage. CPU remains selected.

## Current acceptance limits

The latest source snapshot passed 179 local Python tests (including private training tests), 56 Android unit tests and the debug build. Supplied-call harness checks, raw device model generation, transcript recognition and complete app experience must be assessed separately. Real worker/noise reliability, sustained/tail latency and broader model accuracy remain unverified. No general deployment-readiness claim is made.

Historical training and profiling learnings are retained privately. Obsolete local checkpoint weights, superseded corpus payloads and October 11 APK archives were removed during cleanup; current artifacts and experiment evidence were preserved.
