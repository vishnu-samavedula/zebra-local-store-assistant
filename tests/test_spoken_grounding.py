import unittest

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT
from warehouse_harness.native_tools import render_marked_calls
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


def output(tool, **arguments):
    return render_marked_calls([{"tool": tool, "arguments": arguments}])


class SpokenGroundingTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(self.store.close)
        self.h = Harness(self.store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))

    def pace(self, size=None, quantity=None):
        variant = {"color": "White / Blue"}
        if size is not None:
            variant["size"] = size
        args = {"product_name": "Pace Runner", "variant": variant}
        if quantity is not None:
            args["quantity"] = quantity
        return output("request_replenishment", **args)

    def test_spoken_color_and_size_create_correct_preview_without_saving(self):
        reply = self.h.handle("Request ten Pace Runner in white and blue, size eight.", self.pace("8", 10))
        self.assertEqual("preview", reply["kind"])
        self.assertEqual({"color": "White / Blue", "size": "8"}, reply["preview"]["arguments"]["variant"])
        self.assertEqual([], self.store.tickets())

    def test_standalone_number_answers_size_question_only(self):
        question = self.h.handle("Request ten Pace Runner in white and blue.", self.pace(quantity=10))
        self.assertEqual("Which size?", question["message"])
        self.assertEqual("preview", self.h.handle("Eight.", self.pace("8", 10))["kind"])
        self.h.new_request()
        question = self.h.handle("Request more Pace Runner in white and blue.", self.pace())
        self.assertIn("quantity", question["missing"])
        self.assertEqual("failed", self.h.handle("Ten.", self.pace("10"))["kind"])
        self.assertNotIn("size", self.h.active.arguments["variant"])

    def test_requesting_uses_additional_quantity_not_existing_stock(self):
        text = "There are twenty Cloudweight Hoodies in ink, medium on hand. I'm requesting five more."
        args = {"product_name": "Cloudweight Hoodie", "variant": {"color": "Ink", "size": "M"}}
        wrong = self.h.handle(text, output("request_replenishment", **args, quantity=20))
        self.assertEqual("failed", wrong["kind"])
        correct = self.h.handle(text, output("request_replenishment", **args, quantity=5))
        self.assertEqual("preview", correct["kind"])
        self.assertEqual(5, correct["preview"]["arguments"]["quantity"])
        self.assertEqual([], self.store.tickets())

    def test_negated_requests_never_supply_quantity(self):
        for text in ["Do not request five Cloudweight Hoodies in ink, medium.",
                     "I'm not requesting five Cloudweight Hoodies in ink, medium."]:
            with self.subTest(text=text):
                self.h.new_request()
                reply = self.h.handle(text, output("request_replenishment", product_name="Cloudweight Hoodie",
                    variant={"color": "Ink", "size": "M"}, quantity=5))
                self.assertEqual("failed", reply["kind"])
                self.assertIsNone(self.h.active)
        self.assertEqual([], self.store.tickets())

    def test_quantity_cannot_be_used_as_shoe_size(self):
        text = "Request eight Pace Runner in white and blue."
        self.assertEqual("failed", self.h.handle(text, self.pace("8", 8))["kind"])
        question = self.h.handle(text, self.pace(quantity=8))
        self.assertEqual("clarification", question["kind"])
        self.assertIn("variant.size", question["missing"])

    def test_variant_comma_does_not_detach_requested_quantity(self):
        args = {'product_name': 'Court Low', 'variant': {'color': 'White', 'size': '9'}}
        text = 'Can you replenish Court Low in color White, size 9 by 20 units?'
        correct = self.h.handle(text, output('request_replenishment', **args, quantity=20))
        self.assertEqual('preview', correct['kind'])
        self.assertEqual(20, correct['preview']['arguments']['quantity'])
        self.h.new_request()
        wrong = self.h.handle(text, output('request_replenishment', **args, quantity=9))
        self.assertEqual('failed', wrong['kind'])
        self.assertEqual([], self.store.tickets())

    def test_variant_comma_preserves_existing_quantity_and_negation_guards(self):
        args = {'product_name': 'Court Low', 'variant': {'color': 'White', 'size': '9'}}
        for text in ('We have 20 Court Low in color White, size 9, request 5 more.',
                     'Do not replenish Court Low in color White, size 9 by 20 units.'):
            self.h.new_request()
            reply = self.h.handle(text, output('request_replenishment', **args, quantity=20))
            self.assertEqual('failed', reply['kind'])
        self.assertEqual([], self.store.tickets())

    def test_separate_color_mentions_do_not_form_catalog_color(self):
        text = "Request ten Pace Runner, white sneakers and blue cartons, size eight."
        self.assertEqual("failed", self.h.handle(text, self.pace("8", 10))["kind"])
        self.assertEqual([], self.store.tickets())

    def test_exact_product_location_clarifies_variant_then_resets(self):
        first = self.h.handle("Where is Cloudweight Hoodie?",
            output("inventory_lookup", operation="find_location", product_name="Cloudweight Hoodie"))
        self.assertEqual("clarification", first["kind"])
        self.assertIn("variant.color", first["missing"])
        final = self.h.handle("Ink, medium.", output("inventory_lookup", operation="find_location",
            product_name="Cloudweight Hoodie", variant={"color": "Ink", "size": "M"}))
        self.assertEqual("result", final["kind"])
        self.assertEqual(("A1", 3), (final["items"][0]["section"], final["items"][0]["bin"]))
        self.assertEqual([], self.h.context()["model_history"])

    def test_category_and_single_variant_locations_do_not_overclarify(self):
        for text, args in [("Where are fragrances?", {"category": "Fragrance"}),
                           ("Where is North one?", {"product_name": "North one"})]:
            with self.subTest(text=text):
                self.assertEqual("result", self.h.handle(text,
                    output("inventory_lookup", operation="find_location", **args))["kind"])

    def test_spoken_volume_stays_a_size_without_unit_conversion(self):
        for text in ["How many North one, fifty milliliters?", "How many North one, 50ml?"]:
            with self.subTest(text=text):
                reply = self.h.handle(text, output("inventory_lookup", operation="count_stock",
                    product_name="North one", variant={"size": "50 ml"}))
                self.assertEqual("result", reply["kind"])
        reply = self.h.handle("How many North one, five milliliters?",
            output("inventory_lookup", operation="count_stock", product_name="North one", variant={"size": "50 ml"}))
        self.assertEqual("failed", reply["kind"])


if __name__ == "__main__":
    unittest.main()
