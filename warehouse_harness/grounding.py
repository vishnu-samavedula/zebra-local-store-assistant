"""Conservative checks against worker turns and catalog-backed choices."""

import re

NUMBERS = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
           "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11,
           "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
           "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19, "twenty": 20}
SIZES = {"XS": "extra small", "S": "small", "M": "medium", "L": "large", "XL": "extra large", "XXL": "double extra large"}


def withdrawn_fields(text, arguments):
    """Recognize only standalone, explicit withdrawals of named pending fields."""
    text = text.lower().strip().replace("’", "'")
    match = re.fullmatch(
        r"(?:actually[, ]+)?(?:i (?:don't know|do not know|am not sure about)|i'm not sure about|"
        r"(?:please )?(?:forget|remove|clear))\s+(?:the |that |my )?"
        r"(quantity|count|observed count|number|color|colour|size|location|section|bin|product|product name)"
        r"(?: after all| anymore| now)?[.!]?", text)
    if not match:
        return []
    field = match.group(1)
    candidates = {"count": ["quantity", "observed_count"],
                  "number": ["quantity", "observed_count"],
                  "observed count": ["observed_count"],
                  "color": ["variant.color"], "colour": ["variant.color"],
                  "size": ["variant.size"], "product": ["product_name"],
                  "product name": ["product_name"]}.get(field, [field])
    present = []
    for candidate in candidates:
        head, _, tail = candidate.partition(".")
        if head in arguments and (not tail or tail in arguments[head]):
            present.append(candidate)
    # "Count" is ambiguous when two distinct counts are pending.
    return present if len(present) == 1 else []


def words(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def contains(text, value):
    return bool(re.search(r"(?<![a-z0-9])" + re.escape(value.lower()) + r"(?![a-z0-9])", text.lower()))


def normalize(text):
    return " ".join(w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in words(text))


def quantity_grounded(value, text, *, replenishment=False):
    if replenishment:
        # Commas introducing explicit variant dimensions remain inside the
        # request: "replenish Court Low in white, size 9 by 20 units".
        # Other commas still separate existing-stock and request clauses.
        text = re.sub(r",\s*(?=(?:color|colour|size)\b)", " ", text, flags=re.IGNORECASE)
        clauses = re.split(r"[.;,!?]|\b(?:and|but)\b", text, flags=re.IGNORECASE)
        request_clauses = [clause for clause in clauses if re.search(
            r"\b(request(?:ing|ed|s)?|replenish(?:ment|ing|ed)?|order(?:ing|ed)?|"
            r"need(?:s|ed|ing)?|add(?:ing|ed)?|bring(?:ing)?|send(?:ing)?)\b", clause, re.IGNORECASE)]
        requested = [clause for clause in request_clauses if not re.search(
            r"\b(?:not|never|no|don't|do not)\s+(?:request\w*|replenish\w*|order\w*|"
            r"need\w*|add\w*|bring\w*|send\w*)\b", clause, re.IGNORECASE)]
        if request_clauses:
            if not requested:
                return False
            text = " ".join(requested)
        elif re.search(r"\b(have|stock|on.hand|reserved|available)\b", text, re.IGNORECASE):
            return False
    candidates = []
    for match in re.finditer(r"\b(?:\d+|" + "|".join(NUMBERS) + r")\b", text.lower()):
        token = match.group()
        number = int(token) if token.isdigit() else NUMBERS[token]
        before = text[max(0, match.start() - 18):match.start()].lower()
        after = text[match.end():match.end() + 18].lower()
        if re.search(r"(?:about|around|roughly|approximately|close to|nearly|near|somewhere near|maybe|at least|up to)\s*$", before):
            continue
        if re.search(r"(?:\bnot|instead of|rather than)\s*$", before):
            continue
        if re.search(r"(?:[-−]|\bminus|\bnegative)\s*$", before):
            continue
        if re.match(r"\s*(?:or so|ish)\b", after):
            continue
        if re.search(r"(?:bin|section|size)\s*$", before) or re.match(r"\s*(?:ml|milliliters?|liters?)\b", after):
            continue
        candidates.append(number)
    # Multiple distinct quantities without a trusted association require clarification.
    return value in candidates and (not replenishment or len(set(candidates)) == 1)


def product_names(store, value):
    target = normalize(value)
    names = {p["name"] for p in store.products if target == normalize(p["name"]) or target == normalize(p["sku"])
             or target in {normalize(a) for a in p.get("aliases", [])}}
    return sorted(names)


def variant_grounded(field, value, text, store, clarification_field=None):
    if field == "color":
        aliases = [value]
        catalog_colors = {p["color"] for p in store.products}
        if any(value.casefold() == color.casefold() for color in catalog_colors) and "/" in value:
            aliases.append(" and ".join(part.strip() for part in value.split("/")))
        return any(contains(normalize(text), normalize(alias)) for alias in aliases)
    if value.isdigit():
        aliases = [value] + [word for word, number in NUMBERS.items() if number == int(value)]
        for alias in aliases:
            number = re.escape(alias)
            if re.search(r"\b(?:size|sized)\s+(?:is\s+)?" + number + r"\b", text, re.IGNORECASE):
                return True
            if clarification_field == "variant.size" and re.fullmatch(
                r"\s*(?:(?:actually|sure|it is|it's|we need|that would be|going with)[, ]+)?" + number +
                r"(?:\s*,?\s*(?:please|works))?[.!?]?\s*", text, re.IGNORECASE):
                return True
        return False
    volume = re.fullmatch(r"(\d+)\s*(ml|l)", value.lower())
    if volume:
        number, unit = volume.groups()
        spellings = [number] + [word for word, n in NUMBERS.items() if n == int(number)]
        spellings += {50: ["fifty"], 100: ["one hundred"]}.get(int(number), [])
        units = r"(?:ml|milliliters?|millilitres?)" if unit == "ml" else r"(?:l|liters?|litres?)"
        return any(re.search(r"\b" + re.escape(word) + r"\s*" + units + r"\b", text, re.IGNORECASE) for word in spellings)
    return any(contains(text, alias) for alias in [value, SIZES.get(value.upper(), value)])


def check_fields(args, text, store, verified_choices=(), *, tool=None, clarification_field=None):
    """Return unsupported fields; missing/ambiguous values are handled by resolution."""
    invalid = []
    for field in ("product_name", "category", "section", "location"):
        if field not in args:
            continue
        value = args[field]
        evidence = normalize(text)
        if contains(evidence, normalize(value)):
            continue
        if field == "category" and contains(evidence, normalize(value).rstrip("s")):
            continue
        if field == "product_name":
            names = product_names(store, value)
            if len(names) == 1:
                aliases = {alias for p in store.products if p["name"] == names[0]
                           for alias in [p["name"], *p.get("aliases", [])]}
                if any(contains(evidence, normalize(a)) for a in aliases):
                    continue
                if value in verified_choices and re.search(r"\b(first|second|third|that|this)\b", text.lower()):
                    continue
        invalid.append(field)
    for field, value in args.get("variant", {}).items():
        if not variant_grounded(field, value, text, store, clarification_field):
            invalid.append("variant." + field)
    if "bin" in args:
        patterns = [str(args["bin"])] + [k for k, v in NUMBERS.items() if v == args["bin"]]
        if not any(re.search(r"\bbin\s+" + re.escape(v) + r"\b", text.lower()) for v in patterns):
            invalid.append("bin")
    quantity_text = text
    for product_name in {p["name"] for p in store.products}:
        quantity_text = re.sub(re.escape(product_name), "", quantity_text, flags=re.IGNORECASE)
    for field in ("quantity", "observed_count"):
        if field in args and not quantity_grounded(args[field], quantity_text,
                                                   replenishment=tool == "request_replenishment"):
            invalid.append(field)
    if args.get("on_behalf_of_coworker") and not re.search(r"\b(coworker|teammate|colleague|someone|worker)\b", text.lower()):
        invalid.append("on_behalf_of_coworker")
    for field in ("observation", "damage_description", "reason"):
        if field in args:
            terms = {w.rstrip("s") for w in words(args[field]) if w not in {"a", "an", "the", "on", "in", "at", "of"}}
            evidence = {w.rstrip("s") for w in words(text)}
            if not terms <= evidence:
                invalid.append(field)
    return invalid
