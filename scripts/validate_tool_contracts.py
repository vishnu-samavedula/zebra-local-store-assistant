#!/usr/bin/env python3
"""Check this project's schema subset and reference fixtures, not Android behavior."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse(text):
    def invalid_constant(value):
        raise ValueError(f"Non-JSON numeric constant: {value}")
    return json.loads(text, object_pairs_hook=unique_object, parse_constant=invalid_constant)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate(value, rule, root, path="$"):
    supported = {"$schema", "$id", "title", "description", "$defs", "$ref",
                 "oneOf", "const", "enum", "type", "properties", "required",
                 "additionalProperties", "minProperties", "minLength",
                 "minimum", "items", "minItems", "maxItems", "uniqueItems"}
    require(not set(rule) - supported, f"Unsupported schema keywords at {path}")
    if "$ref" in rule:
        target = root
        require(rule["$ref"].startswith("#/"), "Only local schema references supported")
        for key in rule["$ref"][2:].split("/"):
            target = target[key]
        validate(value, target, root, path)
    if "oneOf" in rule:
        matches = 0
        for choice in rule["oneOf"]:
            try:
                validate(value, choice, root, path)
                matches += 1
            except ValueError:
                pass
        require(matches == 1, f"{path}: expected exactly one matching schema branch")
    if "const" in rule:
        require(type(value) is type(rule["const"]) and value == rule["const"], f"{path}: wrong constant")
    if "enum" in rule:
        require(any(type(value) is type(x) and value == x for x in rule["enum"]), f"{path}: unsupported value")
    types = {"object": dict, "array": list, "string": str, "integer": int, "boolean": bool}
    if "type" in rule:
        require(type(value) is types[rule["type"]], f"{path}: expected {rule['type']}")
    if isinstance(value, dict):
        properties = rule.get("properties", {})
        require(set(rule.get("required", [])) <= set(value), f"{path}: missing envelope fields")
        if rule.get("additionalProperties") is False:
            require(not set(value) - set(properties), f"{path}: unexpected fields")
        require(len(value) >= rule.get("minProperties", 0), f"{path}: empty object")
        for key, item in value.items():
            if key in properties:
                validate(item, properties[key], root, f"{path}.{key}")
    if isinstance(value, list):
        require(len(value) >= rule.get("minItems", 0), f"{path}: too few items")
        require(len(value) <= rule.get("maxItems", len(value)), f"{path}: too many items")
        if rule.get("uniqueItems"):
            require(len({json.dumps(x, sort_keys=True) for x in value}) == len(value), f"{path}: duplicate items")
        if "items" in rule:
            for index, item in enumerate(value):
                validate(item, rule["items"], root, f"{path}[{index}]")
    if isinstance(value, str):
        require(len(value) >= rule.get("minLength", 0), f"{path}: short string")
        if rule.get("minLength", 0):
            require(bool(value.strip()), f"{path}: whitespace-only field")
    if type(value) is int and "minimum" in rule:
        require(value >= rule["minimum"], f"{path}: below minimum")


def check_rejections(schema):
    base = {"schema_version": "1.0", "action": "propose", "calls": []}
    bad_calls = [
        {"tool": "inventory_lookup", "arguments": {"bin": 0}},
        {"tool": "inventory_lookup", "arguments": {"bin": True}},
        {"tool": "inventory_lookup", "arguments": {"stock_basis": "on_hand"}},
        {"tool": "request_replenishment", "arguments": {"quantity": "about ten"}},
        {"tool": "request_replenishment", "arguments": {"quantity": 0}},
        {"tool": "request_replenishment", "arguments": {"destination": "A1"}},
        {"tool": "task_lookup", "arguments": {"operation": "get_task_status"}},
        {"tool": "report_general_concern", "arguments": {"location": None}},
        {"tool": "report_general_concern", "arguments": {"location": "  "}},
        {"tool": "report_product_damage", "arguments": {"confirmed": True}},
        {"tool": "execute_sql", "arguments": {}},
    ]
    invalid = [{**base, "calls": [call]} for call in bad_calls]
    invalid += [base, {**base, "action": "confirm", "calls": [bad_calls[0]]},
                {"schema_version": "1.0", "action": "cancel", "calls": [], "answer": "Done"},
                {**base, "calls": [{"tool": "task_lookup", "arguments": {}}] * 4}]
    for index, output in enumerate(invalid):
        try:
            validate(output, schema, schema)
        except ValueError:
            continue
        raise ValueError(f"Invalid-output case {index} was accepted")
    invalid_json = ['{"action":"confirm","action":"cancel"}', '{} trailing prose', '{"quantity":NaN}']
    for raw in invalid_json:
        try:
            parse(raw)
        except ValueError:
            continue
        raise ValueError("Invalid JSON was accepted")
    return len(invalid) + len(invalid_json)


def main():
    schema = parse((ROOT / "contracts/model_output.schema.json").read_text())
    raw_catalog = (ROOT / "seed_data/warehouse_catalog.jsonl").read_bytes()
    catalog = [parse(line) for line in raw_catalog.decode().splitlines() if line.strip()]
    digest = hashlib.sha256(raw_catalog).hexdigest()
    cards = {parse(x)["id"] for x in (ROOT / "seed_data/sop_cards.jsonl").read_text().splitlines() if x.strip()}
    journeys = [parse(x) for x in (ROOT / "contracts/reference_journeys.jsonl").read_text().splitlines() if x.strip()]
    require(len({x["id"] for x in journeys}) == len(journeys), "Duplicate journey IDs")
    turns = 0
    for journey in journeys:
        require(journey["catalog_sha256"] == digest, f"Catalog changed: review fixture {journey['id']}")
        require(isinstance(journey["initial_context"], dict) and journey["turns"], "Missing context/turns")
        for turn in journey["turns"]:
            turns += 1
            require(bool(turn["worker_text"].strip()), "Empty worker turn")
            validate(turn["expected_model_output"], schema, schema)
            result = turn["expected_harness"]
            require(result["kind"] in {"result", "clarification", "preview", "saved", "cancelled", "paused", "unsupported", "failed"}, "Unknown outcome")
            require(set(result.get("procedure_ids", [])) <= cards, "Unknown procedure card")
            if result["kind"] == "saved":
                require(turn["expected_model_output"]["action"] == "confirm", "Save without confirmation")
                require(result["ticket_state"] == "created_locally", "Wrong ticket state")
            if "catalog_result" in result:
                expected = result["catalog_result"]
                p = next(x for x in catalog if x["product_id"] == expected["product_id"])
                actual = {"product_id": p["product_id"], "available": p["on_hand"] - p["reserved"],
                          "section": p["section"], "bin": p["bin"], "unit": p["unit"]}
                require(expected == actual, f"Wrong catalog expectation: {journey['id']}")
            for expected in result.get("category_results", []):
                rows = [p for p in catalog if p["category"] == expected["category"]]
                require({p["unit"] for p in rows} == {expected["unit"]}, "Incompatible category units")
                require(sum(p["on_hand"] - p["reserved"] for p in rows) == expected["available"], "Wrong category total")
    negatives = check_rejections(schema)
    print(f"Validated {len(journeys)} reference journeys / {turns} turns and {negatives} rejection cases.")
    print("Catalog/procedure expectations checked. Android execution and model accuracy were not tested.")


if __name__ == "__main__":
    main()
