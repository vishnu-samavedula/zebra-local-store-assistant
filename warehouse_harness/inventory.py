"""Inventory resolution and deterministic result formatting."""

from .grounding import product_names


def public(row):
    return {k: row[k] for k in ("product_id", "name", "category", "color", "size", "section", "bin", "available", "unit")}


def resolve(store, args, exact=False):
    if "product_name" not in args and exact:
        return [], ["product_name"], None
    name = None
    if "product_name" in args:
        names = product_names(store, args["product_name"])
        if not names:
            return [], [], "product_not_in_catalog"
        if len(names) != 1:
            return [], ["product_name"], None
        name = names[0]
    rows = store.query(args, name)
    if not rows:
        reason = "variant_not_in_catalog" if args.get("variant") and name else "no_matching_records"
        return [], [], reason
    if exact and len(rows) != 1:
        missing = ["variant." + k for k in ("color", "size") if len({r[k] for r in rows}) > 1 and k not in args.get("variant", {})]
        return rows, missing or ["variant"], None
    return rows, [], None


def lookup(store, args):
    operation = args.get("operation")
    if not operation:
        return {"kind": "clarification", "missing": ["operation_or_product"]}
    missing = []
    if operation in {"list_section_contents", "list_bin_contents"} and "section" not in args:
        missing.append("section")
    if operation == "list_bin_contents" and "bin" not in args:
        missing.append("bin")
    if operation == "find_variant" and "product_name" not in args:
        missing.append("product_name")
    if not any(k in args for k in ("product_name", "category", "section", "bin")):
        missing.append("product_name_or_category")
    if missing:
        return {"kind": "clarification", "missing": missing}
    rows, missing, reason = resolve(store, args, exact=operation == "find_location" and "product_name" in args)
    if missing:
        return {"kind": "clarification", "missing": missing}
    if reason:
        messages = {"product_not_in_catalog": "I couldn't find that product in the catalog.",
                    "variant_not_in_catalog": "That variant isn't in the catalog.",
                    "no_matching_records": "No matching catalog records were found."}
        return {"kind": "result", "reason": reason, "items": [], "message": messages[reason]}
    items = [public(r) for r in rows]
    totals = []
    for unit in sorted({r["unit"] for r in rows}):
        total = sum(r["available"] for r in rows if r["unit"] == unit)
        totals.append({"unit": unit, "available": total})
    if operation == "count_stock":
        scope = args.get("product_name") or args.get("category") or args.get("section", "matching stock")
        message = f"Recorded available stock for {scope}: " + "; ".join(f"{t['available']} {t['unit']}" for t in totals) + "."
        return {"kind": "result", "items": items if len(items) == 1 else [], "totals": totals,
                "scope": dict(args), "matched_variants": len(items), "message": message}
    if operation == "find_product":
        groups = {}
        for row in items:
            groups.setdefault(row["name"], {"name": row["name"], "variants": []})["variants"].append(row)
        items = list(groups.values())
    return {"kind": "result", "items": items, "message": "Recorded catalog matches."}
