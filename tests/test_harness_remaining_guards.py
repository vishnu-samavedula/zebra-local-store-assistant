import unittest
from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store
from warehouse_harness.grounding import withdrawn_fields


class RemainingGuardsTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(self.store.close)
        self.harness = Harness(self.store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))

    def test_replenishment_does_not_use_reported_stock_count(self):
        text = "There are twenty Cloudweight Hoodies in ink, size medium here. Request five more."
        raw = '<|tool_call_start|>[request_replenishment(product_name="Cloudweight Hoodie", variant={"color":"Ink","size":"M"}, quantity=20)]<|tool_call_end|>'
        self.assertEqual("failed", self.harness.handle(text, raw)["kind"])
        self.assertEqual([], self.store.tickets())
        self.assertEqual("preview", self.harness.handle(text, raw.replace("quantity=20", "quantity=5"))["kind"])
        self.assertEqual(5, self.harness.active.arguments["quantity"])

    def test_skin_contact_does_not_depend_on_embedding_ranking(self):
        result = self.harness.handle("A perfume bottle is leaking and the liquid got on my arm.",
                                     "Where did this happen?")
        self.assertIn("CARE-02", [item["id"] for item in result["safety"]])
        self.assertTrue(result["safety_guidance_before_report"])
        self.harness.new_request()
        negated = self.harness.handle("No perfume or chemical got on my arm.", "What would you like to do?")
        self.assertNotIn("safety", negated)

    def damage_preview(self):
        output = '<|tool_call_start|>[report_product_damage(product_name="Cloudweight Hoodie", variant={"color":"Ink","size":"M"}, damage_description="Torn sleeves", location="near the clothing racks", quantity=3)]<|tool_call_end|>'
        self.assertEqual("preview", self.harness.handle(
            "Three Cloudweight Hoodies in ink, medium, have torn sleeves near the clothing racks.", output)["kind"])
        return output

    def test_native_withdrawal_removes_optional_quantity_and_requires_fresh_preview(self):
        self.damage_preview()
        previous = self.harness.active.preview.copy()
        text = "I do not know the quantity after all."
        output = self.harness.withdrawal_output(text)
        self.assertEqual('<|tool_call_start|>[report_product_damage()]<|tool_call_end|>', output)
        result = self.harness.handle(text, output)
        self.assertEqual("preview", result["kind"])
        self.assertNotIn("quantity", result["preview"]["arguments"])
        self.assertGreater(result["preview"]["revision"], previous["revision"])
        stale = self.harness.handle("Yes, submit it.", {"schema_version": "1.0", "action": "confirm", "calls": []},
                                    confirmed_preview=previous)
        self.assertFalse(stale["saved"])
        self.assertEqual([], self.store.tickets())
        saved = self.harness.handle("Yes, submit it.", {"schema_version": "1.0", "action": "confirm", "calls": []})
        self.assertTrue(saved["saved"])
        self.assertNotIn("quantity", saved["ticket"]["arguments"])

    def test_required_quantity_withdrawal_returns_clarification(self):
        call = '<|tool_call_start|>[request_replenishment(product_name="Cloudweight Hoodie", variant={"color":"Ink","size":"M"}, quantity=10)]<|tool_call_end|>'
        self.harness.handle("Request ten Cloudweight Hoodies in ink, medium.", call)
        text = "Forget the quantity."
        result = self.harness.handle(text, self.harness.withdrawal_output(text))
        self.assertEqual("clarification", result["kind"])
        self.assertIn("quantity", result["missing"])
        self.assertNotIn("quantity", self.harness.active.arguments)
        self.assertIsNone(self.harness.active.preview)
        self.assertFalse(self.harness.handle("Yes.", {"schema_version": "1.0", "action": "confirm", "calls": []})["saved"])

    def test_repeated_withdrawn_field_and_truncation_invalidate_old_preview(self):
        for finish in ("stop", "length"):
            with self.subTest(finish=finish):
                self.harness.new_request()
                output = self.damage_preview()
                result = self.harness.handle("I don't know the quantity after all.", output, finish_reason=finish)
                self.assertEqual("failed", result["kind"])
                self.assertIsNone(self.harness.active.preview)
                self.assertFalse(self.harness.handle("Yes.", {"schema_version": "1.0", "action": "confirm", "calls": []})["saved"])
        self.assertEqual([], self.store.tickets())

    def test_named_variant_withdrawal_and_nonwithdrawals(self):
        self.damage_preview()
        text = "I'm not sure about the size."
        result = self.harness.handle(text, self.harness.withdrawal_output(text))
        self.assertEqual("clarification", result["kind"])
        self.assertEqual({"color": "Ink"}, self.harness.active.arguments["variant"])
        args = {"quantity": 3, "observed_count": 10, "location": "shelves"}
        for text in ("I don't know what to do.", "Don't remove the quantity.",
                     "I don't know the count.", "Remove the quantity and request ten instead.",
                     "I don't know the location of the quantity."):
            self.assertEqual([], withdrawn_fields(text, args), text)


if __name__ == "__main__":
    unittest.main()
