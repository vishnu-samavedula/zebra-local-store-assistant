"""Summarize matched device runtime comparisons, not warehouse accuracy."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from warehouse_harness.native_tools import parse_marked_calls


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    raw = json.loads(args.results.read_text())
    if not raw.get("completed"):
        raise ValueError("The device comparison has not completed")
    groups = defaultdict(list)
    matched = defaultdict(dict)
    wrong = []
    for result in raw["results"]:
        key = (result["context_capacity"], result["scenario"], result["cache_prompt"])
        groups[key].append(result)
        matched[(key[0], key[1], result["repetition"])][key[2]] = result
        expected_volume = 40 if result["scenario"] in {"repeat_text", "repeat_audio", "audio_followup_changed_state"} else 35
        try:
            calls = parse_marked_calls(result["output"])["calls"]
        except ValueError:
            calls = []
        if calls != [{"tool": "set_radio", "arguments": {"volume": expected_volume}}]:
            wrong.append({"context": key[0], "scenario": key[1], "cache": key[2],
                          "repetition": result["repetition"], "output": result["output"]})

    def median(rows, getter):
        values = [value for row in rows if (value := getter(row)) is not None]
        return statistics.median(values) if values else None

    summaries = []
    for (capacity, scenario, cached), rows in sorted(groups.items()):
        summaries.append({"context_capacity": capacity, "scenario": scenario, "cache_prompt": cached,
                          "repetitions": len(rows),
                          "median_input_tokens": median(rows, lambda row: row.get("usage", {}).get("prompt_tokens")),
                          "median_output_tokens": median(rows, lambda row: row.get("usage", {}).get("completion_tokens")),
                          "median_cached_tokens": median(rows, lambda row: row.get("usage", {}).get("prompt_tokens_details", {}).get("cached_tokens")),
                          "median_first_text_ms": median(rows, lambda row: row.get("first_text_ms")),
                          "median_total_ms": median(rows, lambda row: row.get("elapsed_ms")),
                          "median_prefill_ms": median(rows, lambda row: row.get("timings", {}).get("prompt_ms")),
                          "median_decode_tps": median(rows, lambda row: row.get("timings", {}).get("predicted_per_second")),
                          "distinct_outputs": sorted({row["output"] for row in rows})})
    mismatches = [{"context_capacity": capacity, "scenario": scenario, "repetition": repetition,
                   "uncached": pair[False]["output"], "cached": pair[True]["output"]}
                  for (capacity, scenario, repetition), pair in matched.items()
                  if set(pair) != {False, True} or pair[False]["output"] != pair[True]["output"]]
    memory = defaultdict(list)
    for row in raw["results"]:
        hwm = row.get("memory", {}).get("VmHWM")
        if hwm:
            memory[(row["context_capacity"], row["cache_prompt"])].append(int(hwm.split()[0]))
    report = {"checkpoint": raw["checkpoint"], "requests": len(raw["results"]),
              "request_failures": raw["request_failures"], "paired_output_mismatches": mismatches,
              "reference_command_failures": wrong, "summaries": summaries,
              "process_high_water_kib": [{"context_capacity": capacity, "cache_prompt": cache, "VmHWM_kib": max(values)}
                                          for (capacity, cache), values in sorted(memory.items())],
              "limitations": ["Three repetitions; no tail-latency or sustained thermal conclusion.",
                              "Excavator commands test cache consistency, not warehouse tool accuracy.",
                              "Recorded fixture only; Android recognition and retrieval are not included.",
                              "Process resident-memory high-water excludes shared/system memory.",
                              "64-token benchmark output ceiling differs from the app's 384-token ceiling.",
                              "History/state shape follows the app; supplied tool names/state values are synthetic reference inputs."]}
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "summaries"}, indent=2))


if __name__ == "__main__":
    main()
