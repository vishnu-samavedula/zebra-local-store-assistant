import unittest

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


class HarnessConversationTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(self.store.close)
        self.harness = Harness(self.store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))

    def test_text_only_clarification_executes_nothing_and_keeps_history(self):
        question = "Do you want the stock count or the product location?"
        result = self.harness.handle("Help me with Cloudweight Hoodie.", question)
        self.assertEqual("conversation", result["kind"])
        self.assertEqual(question, result["message"])
        self.assertFalse(result["saved"])
        self.assertIsNone(self.harness.active)
        context = self.harness.context()
        self.assertEqual("Help me with Cloudweight Hoodie.", context["model_history"][0]["content"])
        self.assertEqual(question, context["model_history"][1]["content"])
        # Returned context is a snapshot, not a mutable reference to trusted state.
        context["model_history"].clear()
        self.assertEqual(2, len(self.harness.context()["model_history"]))
        output = '<|tool_call_start|>[inventory_lookup(operation="count_stock", product_name="Cloudweight Hoodie")]<|tool_call_end|>'
        self.assertEqual("result", self.harness.handle("The stock count.", output)["kind"])
        self.assertEqual([], self.store.tickets())

    def test_text_cannot_confirm_or_execute_a_pending_write(self):
        proposal = '<|tool_call_start|>[request_replenishment(product_name="Cloudweight Hoodie", variant={"color":"Ink","size":"M"}, quantity=10)]<|tool_call_end|>'
        self.assertEqual("preview", self.harness.handle(
            "Request ten Cloudweight Hoodies in ink, size medium.", proposal)["kind"])
        result = self.harness.handle("Yes.", "Saved your request!")
        self.assertEqual("failed", result["kind"])
        self.assertEqual([], self.store.tickets())
        self.assertEqual(10, self.harness.active.arguments["quantity"])
        self.assertIsNone(self.harness.active.preview)
        confirm = {"schema_version": "1.0", "action": "confirm", "calls": []}
        self.assertNotEqual("saved", self.harness.handle("Yes, submit it.", confirm)["kind"])
        self.assertEqual([], self.store.tickets())

    def test_invalid_calls_and_truncation_cannot_become_text(self):
        outputs = ['<|tool_call_start|>[inventory_lookup()]',
                   'inventory_lookup(operation="count_stock")',
                   '{"action":"confirm"}', '[inventory_lookup()]', '```bad```']
        for output in outputs:
            with self.subTest(output=output):
                self.assertEqual("failed", self.harness.handle("Help.", output)["kind"])
        self.assertEqual("failed", self.harness.handle("Help.", "Which product?", finish_reason="length")["kind"])
        self.assertEqual([], self.store.tickets())

    def test_urgent_guidance_is_preserved_with_text_only_reply(self):
        result = self.harness.handle("Perfume splashed into my eyes near the shelves.", "Which product?")
        self.assertTrue(result["safety_guidance_before_report"])
        self.assertIn("CARE-02", [card["id"] for card in result["safety"]])
        self.assertEqual([], self.store.tickets())

    def test_new_request_clears_conversation_context(self):
        self.harness.handle("Help with this.", "What would you like to do?")
        self.harness.new_request()
        self.assertEqual([], self.harness.context()["model_history"])
        self.assertEqual([], self.harness.context()["full_request_turns"])


if __name__ == "__main__":
    unittest.main()
