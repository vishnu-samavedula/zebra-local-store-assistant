import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT, decode
from warehouse_harness.engine import Request
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


def envelope(action="propose", tool=None, **arguments):
    return {"schema_version": "1.0", "action": action,
            "calls": [{"tool": tool, "arguments": arguments}] if tool else []}


class Candidates:
    def __init__(self, ids=()):
        self.ids = list(ids)

    def retrieve(self, text, k):
        return [{"id": card, "score": 100 - i} for i, card in enumerate(self.ids[:k])]


class HarnessTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl", tasks=ROOT / "warehouse_harness/demo_tasks.jsonl")
        self.candidates = Candidates()
        self.h = Harness(self.store, Procedures(ROOT / "seed_data/sop_cards.jsonl", self.candidates), snapshot_time="2026-10-12T12:00:00Z")
        self.addCleanup(self.store.close)

    def request(self, quantity=10):
        return self.h.handle(f"Request {quantity} Cloudweight Hoodies in ink, size medium.",
                             envelope(tool="request_replenishment", product_name="Cloudweight Hoodie", variant={"color": "Ink", "size": "M"}, quantity=quantity))

    def test_inventory_count_units_and_all_six_operations(self):
        cases = [
            ("How many sneakers?", {"operation": "count_stock", "category": "Sneakers"}),
            ("Where is North one?", {"operation": "find_location", "product_name": "North one"}),
            ("Find Cloudweight Hoodie.", {"operation": "find_product", "product_name": "Cloudweight Hoodie"}),
            ("List variants of Cloudweight Hoodie.", {"operation": "find_variant", "product_name": "Cloudweight Hoodie"}),
            ("List section A1.", {"operation": "list_section_contents", "section": "A1"}),
            ("List section A1 bin two.", {"operation": "list_bin_contents", "section": "A1", "bin": 2}),
        ]
        for text, arguments in cases:
            with self.subTest(arguments=arguments):
                self.h.new_request()
                result = self.h.handle(text, envelope(tool="inventory_lookup", **arguments))
                self.assertEqual("result", result["kind"])
        self.h.new_request()
        result = self.h.handle("How many sneakers?", envelope(tool="inventory_lookup", operation="count_stock", category="Sneakers"))
        self.assertEqual([{"unit": "pair", "available": 2156}], result["totals"])

    def test_pagination_is_harness_owned(self):
        result = self.h.handle("List variants of Cloudweight Hoodie.", envelope(tool="inventory_lookup", operation="find_variant", product_name="Cloudweight Hoodie"))
        self.assertEqual(3, len(result["items"]))
        ids = {r["product_id"] for r in result["items"]}
        more = self.h.handle("Show more.", envelope("show_more"))
        self.assertEqual(3, len(more["items"]))
        self.assertFalse(ids & {r["product_id"] for r in more["items"]})

    def test_unsupported_schema_and_contradictory_corrections(self):
        malformed = [envelope(tool="task_lookup", operation="get_task_status"),
                     envelope(tool="request_replenishment", quantity=0),
                     envelope(tool="inventory_lookup", bin=True)]
        conflict = envelope("correct", "request_replenishment", quantity=10)
        conflict["calls"][0]["clear_fields"] = ["quantity"]
        malformed += [conflict, '{"action":"confirm","action":"cancel"}']
        for value in malformed:
            with self.assertRaises(ValueError):
                decode(value)
        self.assertEqual([], self.store.tickets())

    def test_unstated_and_approximate_quantities_never_save(self):
        for text in ["Request about ten Cloudweight Hoodies in ink, medium.", "Request Cloudweight Hoodies in ink, medium."]:
            self.h.new_request()
            result = self.h.handle(text, envelope(tool="request_replenishment", product_name="Cloudweight Hoodie", variant={"color": "Ink", "size": "M"}, quantity=10))
            self.assertEqual("failed", result["kind"])
            self.h.handle("Yes.", envelope("confirm"))
        self.assertEqual([], self.store.tickets())

    def test_approximate_correction_cannot_reuse_old_exact_quantity(self):
        self.request()
        result = self.h.handle("Actually about ten.", envelope("correct", "request_replenishment", quantity=10))
        self.assertEqual("failed", result["kind"])
        self.h.handle("Yes, submit it.", envelope("confirm"))
        self.assertEqual([], self.store.tickets())

    def test_name_number_is_not_an_affected_quantity(self):
        result = self.h.handle("North one has cracked glass near the shelves.", envelope(tool="report_product_damage", product_name="North one", damage_description="Cracked glass", location="near the shelves", quantity=1))
        self.assertEqual("failed", result["kind"])

    def test_location_prefix_and_invented_variant_do_not_match(self):
        first = self.h.handle("List section A10.", envelope(tool="inventory_lookup", operation="list_section_contents", section="A1"))
        self.assertEqual("failed", first["kind"])
        second = self.h.handle("How many Cloudweight Hoodies?", envelope(tool="inventory_lookup", operation="count_stock", product_name="Cloudweight Hoodie", variant={"color": "Ink"}))
        self.assertEqual("failed", second["kind"])

    def test_confirm_requires_preview_and_real_worker_acceptance(self):
        self.assertEqual("clarification", self.h.handle("Yes.", envelope("confirm"))["kind"])
        self.request()
        self.assertEqual("preview", self.h.handle("No, do not save it.", envelope("confirm"))["kind"])
        self.assertEqual([], self.store.tickets())
        saved = self.h.handle("Yes, submit it.", envelope("confirm"))
        self.assertEqual("saved", saved["kind"])
        self.assertEqual("created_locally", saved["ticket"]["status"])
        self.assertEqual(10, saved["ticket"]["arguments"]["quantity"])
        self.h.handle("Yes, submit it.", envelope("confirm"))
        self.assertEqual(1, len(self.store.tickets()))

    def test_correction_requires_new_preview_confirmation(self):
        self.request()
        result = self.h.handle("Make it fifteen and go ahead.", envelope("correct", "request_replenishment", quantity=15))
        self.assertEqual("preview", result["kind"])
        self.assertEqual([], self.store.tickets())
        saved = self.h.handle("Yes, submit it.", envelope("confirm"))
        self.assertEqual(15, saved["ticket"]["arguments"]["quantity"])

    def test_product_change_clears_variant(self):
        self.request()
        result = self.h.handle("Actually request Pace Runner.", envelope("correct", "request_replenishment", product_name="Pace Runner"))
        self.assertEqual("clarification", result["kind"])
        self.assertNotIn("variant", self.h.active.arguments)
        self.assertIsNone(self.h.active.preview)

    def test_inventory_interruption_preserves_report_and_requires_explicit_target(self):
        self.h.handle("Glass pieces on the floor near the perfume shelves.", envelope(tool="report_general_concern", observation="Glass pieces on the floor", location="near the perfume shelves"))
        key, draft = self.h.active.key, self.h.active.arguments.copy()
        result = self.h.handle("How many wallets?", envelope(tool="inventory_lookup", operation="count_stock", category="Wallets"))
        self.assertTrue(result["pending_report_preserved"])
        self.assertEqual(key, self.h.active.key)
        self.assertEqual(draft, self.h.active.arguments)
        self.assertEqual("preview", self.h.handle("Yes.", envelope("confirm"))["kind"])
        self.assertEqual([], self.store.tickets())
        self.assertEqual("saved", self.h.handle("Yes, save the report.", envelope("confirm"))["kind"])

    def test_incomplete_interrupting_lookup_retains_its_fields(self):
        self.request()
        self.h.handle("What is in bin two?", envelope("clarify", "inventory_lookup", operation="list_bin_contents", bin=2))
        result = self.h.handle("Section A1.", envelope(tool="inventory_lookup", operation="list_bin_contents", section="A1", bin=2))
        self.assertEqual("result", result["kind"])
        self.assertEqual("request_replenishment", self.h.active.tool)

    def test_multiple_incomplete_reads_are_not_merged_or_asked_together(self):
        output = envelope("propose", "inventory_lookup", operation="list_bin_contents", bin=2)
        output["calls"].append({"tool": "inventory_lookup", "arguments": {"operation": "list_bin_contents", "bin": 3}})
        first = self.h.handle("What is in bin two and bin three?", output)
        self.assertEqual(["clarification", "queued"], [r["kind"] for r in first["results"]])
        second = self.h.handle("Section A1.", envelope(tool="inventory_lookup", operation="list_bin_contents", section="A1", bin=2))
        self.assertEqual(2, second["items"][0]["bin"])
        self.assertEqual("clarification", second["next_request"]["kind"])
        self.assertEqual(3, self.h.active.arguments["bin"])

    def test_text_after_limit_keeps_fields_and_never_asks_sixth_question(self):
        self.h.active = Request("inventory_lookup", {"operation": "list_bin_contents", "bin": 2}, questions=5)
        paused = self.h.handle("I do not know.", envelope("clarify", "inventory_lookup", operation="list_bin_contents", bin=2))
        self.assertEqual("paused", paused["kind"])
        result = self.h.handle("Section A1.", envelope(tool="inventory_lookup", operation="list_bin_contents", section="A1", bin=2), input_mode="text")
        self.assertEqual("result", result["kind"])
        self.assertEqual(2, result["items"][0]["bin"])

    def test_general_reports_accept_unknown_products_but_require_location(self):
        result = self.h.handle("Unknown Widget has a scratch.", envelope(tool="report_general_concern", observation="Scratch", product_name="Unknown Widget"))
        self.assertEqual("clarification", result["kind"])
        self.assertIn("location", result["missing"])
        preview = self.h.handle("Near the doorway.", envelope(tool="report_general_concern", location="near the doorway"))
        self.assertEqual("preview", preview["kind"])
        self.assertEqual("saved", self.h.handle("Yes, save it.", envelope("confirm"))["kind"])

    def test_candidate_similarity_does_not_authorize_negated_guidance(self):
        self.candidates.ids = ["SOP-03", "SOP-11", "CARE-05"]
        result = self.h.handle("Order ten North one. There is no spill or damage.", envelope(tool="request_replenishment", product_name="North one", quantity=10))
        self.assertEqual(["SOP-11"], [c["id"] for c in result["procedures"]["cards"]])
        self.h.new_request()
        result = self.h.handle("The teammate is not sick anymore and stopped coughing near the door.", envelope(tool="report_general_concern", observation="Teammate stopped coughing", location="near the door"))
        self.assertEqual([], result["procedures"]["cards"])

    def test_urgent_observations_survive_bad_model_output(self):
        result = self.h.handle("Perfume splashed into my eyes near the shelves.", "<|tool_call_start|>invalid")
        self.assertEqual("failed", result["kind"])
        self.assertIn("CARE-02", [c["id"] for c in result["safety"]])
        self.assertEqual([], self.store.tickets())

    def test_task_list_is_scoped_and_read_only(self):
        result = self.h.handle("Show my tasks.", envelope(tool="task_lookup", operation="list_assigned_tasks"))
        self.assertEqual(3, len(result["tasks"]))
        self.assertTrue(all(t["assigned_worker"] == "demo-worker" for t in result["tasks"]))
        self.assertEqual([], self.store.tickets())

    def test_multiple_writes_get_separate_confirmation(self):
        output = envelope(tool="report_general_concern", observation="Glass pieces on the floor", location="near the door")
        output["calls"].append({"tool": "report_general_concern", "arguments": {"observation": "Bad smell", "location": "near the dock"}})
        result = self.h.handle("Glass pieces on the floor near the door. Bad smell near the dock.", output)
        self.assertEqual("batch", result["kind"])
        first = self.h.handle("Yes, save the report.", envelope("confirm"))
        self.assertEqual(1, len(self.store.tickets()))
        self.assertEqual("preview", first["next_request"]["kind"])
        self.h.handle("Yes, save the report.", envelope("confirm"))
        self.assertEqual(2, len(self.store.tickets()))

    def test_transport_retry_does_not_confirm_next_queued_ticket(self):
        output = envelope(tool="report_general_concern", observation="Glass pieces on the floor", location="near the door")
        output["calls"].append({"tool": "report_general_concern", "arguments": {"observation": "Bad smell", "location": "near the dock"}})
        self.h.handle("Glass pieces on the floor near the door. Bad smell near the dock.", output)
        first = self.h.handle("Yes, save the report.", envelope("confirm"), event_id="confirmation-1")
        repeated = self.h.handle("Yes, save the report.", envelope("confirm"), event_id="confirmation-1")
        self.assertEqual(first, repeated)
        self.assertEqual(1, len(self.store.tickets()))

    def test_stale_preview_revision_cannot_save_corrected_request(self):
        first = self.request()["preview"]
        self.h.handle("Make it fifteen.", envelope("correct", "request_replenishment", quantity=15))
        result = self.h.handle("Yes, submit it.", envelope("confirm"), confirmed_preview={"request_key": first["request_key"], "revision": first["revision"]})
        self.assertEqual("preview", result["kind"])
        self.assertEqual([], self.store.tickets())

    def test_tickets_persist_and_request_keys_are_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            db = Path(folder) / "tickets.sqlite"
            store = Store(ROOT / "seed_data/warehouse_catalog.jsonl", db)
            first = store.save("same-key", "report_general_concern", {"observation": "Example", "location": "demo area"}, "Example", "General concern")
            again = store.save("same-key", "report_general_concern", {}, "", "General concern")
            self.assertEqual(first, again)
            store.close()
            reopened = Store(ROOT / "seed_data/warehouse_catalog.jsonl", db)
            self.assertEqual(first, reopened.get("same-key"))
            self.assertEqual(1, len(reopened.tickets()))
            reopened.close()

    def test_retrieval_and_save_failures_do_not_claim_success(self):
        self.request()
        with patch.object(self.store, "save", side_effect=RuntimeError("Simulated disk failure")):
            result = self.h.handle("Yes, submit it.", envelope("confirm"))
        self.assertEqual("failed", result["kind"])
        self.assertEqual([], self.store.tickets())
        self.assertIsNotNone(self.h.active.preview)
        with patch.object(self.candidates, "retrieve", side_effect=ValueError("Overlength query")):
            self.h.new_request()
            result = self.h.handle("Glass on the floor near the door.", envelope(tool="report_general_concern", observation="Glass on the floor", location="near the door"))
        self.assertEqual("preview", result["kind"])
        self.assertEqual("failed", result["procedures"]["state"])
        self.assertEqual([], self.store.tickets())

    def test_reference_journeys_execute_and_saved_fields_match(self):
        fixtures = [json.loads(x) for x in (ROOT / "contracts/reference_journeys.jsonl").read_text().splitlines()]
        for fixture in fixtures:
            with self.subTest(journey=fixture["id"]):
                self.h.new_request()
                context = fixture["initial_context"]
                if context.get("focused_call"):
                    call = context["focused_call"]
                    self.h.active = Request(call["tool"], call["arguments"].copy(), questions=context.get("clarification_questions_asked", 0))
                for turn in fixture["turns"]:
                    expected = turn["expected_harness"]
                    self.candidates.ids = expected.get("procedure_ids", [])
                    result = self.h.handle(turn["worker_text"], turn["expected_model_output"])
                    # A multi-read produces a batch of independently grounded results.
                    expected_kind = "batch" if expected.get("category_results") else expected["kind"]
                    self.assertEqual(expected_kind, result["kind"], result)
                    if "catalog_result" in expected:
                        self.assertTrue(any(all(row[k] == v for k, v in expected["catalog_result"].items()) for row in result["items"]))
                    if "saved_fields" in expected:
                        self.assertEqual(expected["saved_fields"], result["ticket"]["arguments"])
                    if expected.get("no_save") or expected.get("no_new_save"):
                        self.assertFalse(result["saved"])
                    if "procedure_ids" in expected:
                        actual = {card["id"] for card in result.get("procedures", {}).get("cards", [])}
                        actual |= {card["id"] for card in result.get("safety", [])}
                        self.assertTrue(set(expected["procedure_ids"]) <= actual)


if __name__ == "__main__":
    unittest.main()
