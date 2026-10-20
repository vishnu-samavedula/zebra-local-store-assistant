"""Candidate retrieval with an explicit, conservative demo applicability gate."""

import re

from .store import records

# These are demo evidence rules, not a calibrated or complete safety classifier.
CUES = {
    "SOP-01": r"\b(torn|stained|damaged|broken)\b",
    "SOP-02": r"\b(empty bin|missing stock|wrong count|discrepancy|fewer|wrong size)\b",
    "SOP-03": r"\b(perfume|fragrance|bottles?)\b.*\b(cracked|broken|damage|damaged|leak|leaking|spill)\b|\b(cracked|broken|damage|damaged|leak|leaking|spill)\b.*\b(perfume|fragrance|bottles?)\b",
    "SOP-04": r"\bglass shards\b|\b(glass|shards)\b.*\b(floor|broken|cracked|cut)\b|\b(broken|cracked)\b.*\b(glass|bottle)\b",
    "SOP-05": r"\b(pallet|stack|load|carton|box)\b.*\b(damaged|split|unstable|leaning|falling|crushed)\b|\b(unstable|leaning|crushed)\b.*\b(load|stack|carton|box)\b",
    "SOP-06": r"\b(smell|odor|odour|fumes)\b",
    "SOP-07": r"\b(too hot|too cold|cooling|heating|ventilation|airflow|heat illness)\b",
    "SOP-08": r"\b(dock|trailer|restraint)\b.*\b(fault|damaged|unsafe|gap|moving|blocked)\b|\b(fault|damaged|unsafe|blocked)\b.*\b(dock|trailer|restraint)\b",
    "SOP-09": r"\b(smoke|fire|flames|burning smell)\b",
    "SOP-10": r"\b(gas leak|gas smell|smell of gas|smell gas|suspected gas)\b",
    "CARE-01": r"\b(dirty hands|wash my hands|wash hands)\b",
    "CARE-03": r"\b(cut|bleeding|embedded glass)\b",
    "CARE-04": r"\b(first.aid kit|medical box|wash station|eyewash|first.aider)\b",
    "CARE-05": r"\b(coughing|cough|fever|feverish|sick|unwell|cold symptoms)\b",
    "HELP-01": r"\b(coworker|teammate|colleague|someone)\b.*\b(cut|collapsed|unwell|dizzy|coughing|fever|bleeding)\b",
    "HELP-02": r"\b(911|ambulance|emergency help|emergency number)\b",
    "HELP-03": r"\b(exit|evacuate|evacuation|assembly point)\b",
}


def positive_evidence(text):
    clauses = re.split(r"[.;!?]|\bbut\b", text.lower())
    kept = []
    for clause in clauses:
        # Equipment "not working" describes a fault, rather than denying one.
        clause = re.sub(r"\b(cooling|heating|ventilation|airflow)\s+(?:is\s+)?not working\b",
                        r"\1 failure", clause)
        # Conservative abstention for negated/resolved clauses, not a general negation parser.
        if re.search(r"\b(no|nobody|neither|not|never|stopped|anymore|resolved|cancel)\b", clause):
            continue
        kept.append(clause)
    return ". ".join(kept)


def substance_contact(evidence, sites):
    # Match either word order within the same positive clause, including plurals.
    return any(re.search(r"\b(perfumes?|fragrances?|chemicals?)\b", clause)
               and re.search(sites, clause)
               for clause in re.split(r"[.;!?]", evidence))


class Procedures:
    def __init__(self, cards_path, retriever=None):
        self.cards = {c["id"]: c for c in records(cards_path)}
        self.retriever = retriever

    def applicable(self, card_id, text, tool):
        if card_id == "SOP-11":
            return tool == "request_replenishment"
        evidence = positive_evidence(text)
        if card_id == "CARE-02":
            return substance_contact(evidence, r"\b(skin|eyes?|splashed|contact)\b")
        if card_id == "SOP-01" and re.search(r"\b(perfume|fragrance|glass|bottle)\b", evidence):
            return False
        return bool(re.search(CUES.get(card_id, r"(?!)"), evidence))

    def emergency(self, text):
        evidence = positive_evidence(text)
        ids = []
        for card_id in ("SOP-09", "SOP-10"):
            if self.applicable(card_id, evidence, None):
                ids.append(card_id)
        if re.search(r"\b(trouble breathing|breathing difficulty|cannot breathe|chest pain|collapsed|uncontrolled bleeding|heavy bleeding)\b", evidence):
            ids.append("HELP-02")
        # Time-critical exposure help also bypasses similarity and tool parsing.
        if substance_contact(evidence, r"\b(eyes?|skin|arms?|hands?|face|neck)\b"):
            ids.append("CARE-02")
        return [self.present(self.cards[i]) for i in ids if i in self.cards]

    @staticmethod
    def present(card):
        return {"id": card["id"], "title": card["title"], "status": card["status"],
                "guidance": card.get("short_response", card["guidance"]),
                "worker_safety": card.get("worker_safety", ""), "escalation": card["escalation"]}

    def retrieve(self, text, tool):
        required = [self.present(self.cards["SOP-11"])] if tool == "request_replenishment" and "SOP-11" in self.cards else []
        if self.retriever is None:
            return {"state": "matched" if required else "unavailable", "cards": required,
                    "candidate_retrieval_state": "unavailable", "message": "Procedure retrieval is not connected."}
        try:
            candidates = self.retriever.retrieve(text, 3)
            selected = list(required)
            for candidate in candidates:
                card_id = candidate["id"]
                # Use trusted source records; never display a retriever-supplied replacement card.
                if card_id in self.cards and self.applicable(card_id, text, tool):
                    if card_id not in {c["id"] for c in selected}:
                        selected.append(self.present(self.cards[card_id]))
            return {"state": "matched" if selected else "no_applicable_guidance", "cards": selected,
                    "candidate_ids": [c["id"] for c in candidates], "applicability": "demo_evidence_rules"}
        except (ValueError, RuntimeError, OSError) as error:
            return {"state": "failed", "cards": required, "message": "Procedure retrieval could not be completed.", "error": str(error)}
