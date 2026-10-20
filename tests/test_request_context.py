import unittest

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT
from warehouse_harness.native_tools import render_marked_calls
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


def proposal(tool, **arguments):
    return render_marked_calls([{"tool": tool, "arguments": arguments}])


class RequestContextTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(self.store.close)
        self.harness = Harness(self.store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))

    def assert_fresh(self):
        state = self.harness.context()
        self.assertEqual("idle", state["phase"])
        self.assertIsNone(state["focused_call"])
        for key in ("model_history", "full_request_turns", "verified_choices"):
            self.assertEqual([], state[key], key)

    def preview(self):
        return self.harness.handle("Request ten Cloudweight Hoodies in ink, medium.",
            proposal("request_replenishment", product_name="Cloudweight Hoodie",
                     variant={"color": "Ink", "size": "M"}, quantity=10))

    def test_contracted_independent_location_lookup_has_fresh_context_and_preserves_report(self):
        self.preview()
        original = self.harness.context()
        for text in ("Where's Irish paper located?", "Where’s Irish paper located?", "Where're the hoodies?"):
            context = self.harness.context(text)
            self.assertEqual("idle", context["phase"])
            self.assertEqual([], context["model_history"])
            self.assertIsNone(context["focused_call"])
            self.assertEqual(original, self.harness.context())
        self.assertEqual(original, self.harness.context("Where's the damaged hoodie I am reporting?"))

    def test_completed_reads_do_not_supply_an_implicit_product_to_next_request(self):
        result = self.harness.handle("How many Cloudweight Hoodies in ink, medium?",
            proposal("inventory_lookup", operation="count_stock", product_name="Cloudweight Hoodie",
                     variant={"color": "Ink", "size": "M"}))
        self.assertEqual("result", result["kind"])
        self.assert_fresh()
        result = self.harness.handle("Actually, small.",
            proposal("inventory_lookup", operation="count_stock", product_name="Cloudweight Hoodie",
                     variant={"color": "Ink", "size": "S"}))
        self.assertEqual("failed", result["kind"])
        self.assert_fresh()

    def test_bin_clarification_keeps_request_evidence_then_resets(self):
        first = self.harness.handle("What's in bin three?",
            proposal("inventory_lookup", operation="list_bin_contents", bin=3))
        self.assertEqual("clarification", first["kind"])
        state = self.harness.context()
        self.assertEqual(["What's in bin three?"], state["full_request_turns"])
        self.assertEqual(3, len(state["model_history"]))
        final = self.harness.handle("Section A1.",
            proposal("inventory_lookup", operation="list_bin_contents", bin=3, section="A1"))
        self.assertEqual("result", final["kind"])
        self.assertEqual("cloud-hoodie-ink-m", final["items"][0]["product_id"])
        self.assert_fresh()

    def test_saved_request_resets_context_but_keeps_ticket_retry_protection(self):
        self.assertEqual("preview", self.preview()["kind"])
        self.assertTrue(self.harness.context()["model_history"])
        confirm = {"schema_version": "1.0", "action": "confirm", "calls": []}
        saved = self.harness.handle("Yes, submit it.", confirm, event_id="save-event")
        self.assertTrue(saved["saved"])
        self.assert_fresh()
        duplicate = self.harness.handle("Yes, submit it.", confirm, event_id="save-event")
        self.assertEqual(saved["ticket"]["reference"], duplicate["ticket"]["reference"])
        self.assertEqual(1, len(self.store.tickets()))
        self.assert_fresh()

    def test_cancellation_resets_model_context_without_saving(self):
        self.preview()
        result = self.harness.handle("Cancel the request.",
            {"schema_version": "1.0", "action": "cancel", "calls": []})
        self.assertEqual("cancelled", result["kind"])
        self.assert_fresh()
        self.assertEqual([], self.store.tickets())

    def test_unknown_product_and_known_zero_both_complete_without_history(self):
        unknown = self.harness.handle("How many cars do we have?",
            proposal("inventory_lookup", operation="count_stock", product_name="cars"))
        self.assertEqual("product_not_in_catalog", unknown["reason"])
        self.assert_fresh()
        zero = self.harness.handle("How many irish paper, 50 ml?",
            proposal("inventory_lookup", operation="count_stock", product_name="irish paper",
                     variant={"size": "50 ml"}))
        self.assertEqual(0, zero["totals"][0]["available"])
        self.assert_fresh()

    def test_terminal_scope_reply_clears_unresolved_intent_question(self):
        self.harness.handle("Can you help?", "Which warehouse task do you need?")
        self.assertTrue(self.harness.context()["model_history"])
        result = self.harness.handle("Who won the football match?", "I can help with warehouse tasks.")
        self.assertEqual("conversation", result["kind"])
        self.assert_fresh()

    def test_clarification_without_question_mark_retains_current_request(self):
        self.harness.handle("Help me with Cloudweight Hoodie.", "Please specify stock count or location.")
        self.assertTrue(self.harness.context()["model_history"])
        result = self.harness.handle("The stock count.",
            proposal("inventory_lookup", operation="count_stock", product_name="Cloudweight Hoodie"))
        self.assertEqual("result", result["kind"])
        self.assert_fresh()

    def test_unrelated_read_has_fresh_input_and_does_not_pollute_preserved_draft(self):
        self.preview()
        before = self.harness.context()
        fresh = self.harness.context("Where is irish paper?")
        self.assertEqual("idle", fresh["phase"])
        self.assertEqual([], fresh["model_history"])
        self.assertEqual([], fresh["full_request_turns"])
        self.assertIsNone(fresh["focused_call"])
        self.assertEqual(before, self.harness.context("Actually, make it fifteen."))
        self.harness.handle("Where is irish paper?",
            proposal("inventory_lookup", operation="find_location", product_name="irish paper"))
        self.assertEqual(before, self.harness.context())
        self.assertEqual([], self.store.tickets())

    def test_recoverable_failure_preserves_original_and_new_safety_observations(self):
        self.harness.handle("Report a strange smell near receiving.",
            proposal("report_general_concern", observation="strange smell", location="near receiving"))
        failed = self.harness.handle("There is smoke now.", "<|tool_call_start|>broken")
        self.assertEqual("failed", failed["kind"])
        state = self.harness.context()
        self.assertIn("Report a strange smell near receiving.", state["full_request_turns"])
        self.assertIn("There is smoke now.", state["full_request_turns"])
        self.assertIn("SOP-09", [c["id"] for c in failed["safety"]])
        self.assertEqual([], self.store.tickets())

    def test_pagination_is_harness_state_not_report_history(self):
        self.harness.handle("Find Cloudweight Hoodie.",
            proposal("inventory_lookup", operation="find_variant", product_name="Cloudweight Hoodie"))
        self.assert_fresh()
        self.preview()
        before = self.harness.context()
        result = self.harness.handle("Show more.",
            {"schema_version": "1.0", "action": "show_more", "calls": []})
        self.assertEqual("result", result["kind"])
        self.assertEqual(before, self.harness.context())


if __name__ == "__main__":
    unittest.main()
