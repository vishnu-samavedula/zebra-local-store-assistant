import json
import unittest

from warehouse_harness import Harness
from warehouse_harness.contracts import ROOT, decode, encode_proposal
from warehouse_harness.native_tools import START, END
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store


def marked(body):
    return START + body + END


class NativeWarehouseToolsTest(unittest.TestCase):
    def test_existing_journey_proposals_round_trip_without_losing_arguments(self):
        fixtures = [json.loads(line) for line in
                    (ROOT / "contracts/reference_journeys.jsonl").read_text().splitlines()]
        for fixture in fixtures:
            for turn in fixture["turns"]:
                envelope = turn["expected_model_output"]
                with self.subTest(journey=fixture["id"], action=envelope["action"]):
                    if envelope["action"] == "propose":
                        self.assertEqual(decode(envelope), decode(encode_proposal(envelope)))
                    else:
                        with self.assertRaises(ValueError):
                            encode_proposal(envelope)
                        self.assertEqual(envelope, decode(json.dumps(envelope)))

    def test_multiple_calls_and_json_boolean_types(self):
        parsed = decode(marked('[inventory_lookup(operation="count_stock", category="Sneakers"), '
                               'report_general_concern(observation="Coworker is unwell", '
                               'on_behalf_of_coworker=true)]'))
        self.assertEqual(2, len(parsed["calls"]))
        self.assertIs(True, parsed["calls"][1]["arguments"]["on_behalf_of_coworker"])

    def test_strings_with_punctuation_escapes_and_unicode_are_data(self):
        observation = 'Broken [bottle], beside (door): "look here" — no diagnosis'
        envelope = {"schema_version": "1.0", "action": "propose", "calls": [
            {"tool": "report_general_concern", "arguments": {"observation": observation}}
        ]}
        self.assertEqual(envelope, decode(encode_proposal(envelope)))

    def test_malformed_unsafe_or_out_of_contract_calls_are_rejected(self):
        cases = [
            '[unknown_tool()]',
            '[inventory_lookup(operation="drop_table")]',
            '[inventory_lookup(operation="count_stock", operation="find_location")]',
            '[inventory_lookup(variant={"size":"M","size":"L"})]',
            '[inventory_lookup(bin=true)]',
            '[inventory_lookup(bin=1.0)]',
            '[inventory_lookup(bin=null)]',
            '[inventory_lookup(stock_basis="on_hand")]',
            '[inventory_lookup(variant={"size":"M","invented":"yes"})]',
            '[inventory_lookup(**{"operation":"count_stock"})]',
            '[inventory_lookup("count_stock")]',
            '[inventory_lookup(operation=__import__("os").getcwd())]',
            '[inventory_lookup(operation="count_stock" + "extra")]',
            '[inventory_lookup(bin=NaN)]',
            '[inventory_lookup(bin=Infinity)]',
            "[report_general_concern(on_behalf_of_coworker=True)]",
            '[inventory_lookup(operation=\'count_stock\')]',
            '[inventory_lookup(operation="count_stock",)]',
            '[inventory_lookup(operation="count_stock"),]',
            '[inventory_lookup(operation="count_stock") inventory_lookup()]',
            '[inventory_lookup()]; inventory_lookup()',
            '[]',
            '[' + ','.join(['inventory_lookup()'] * 4) + ']',
        ]
        for body in cases:
            with self.subTest(body=body), self.assertRaises(ValueError):
                decode(marked(body))
        block = marked('[inventory_lookup(operation="count_stock")]')
        for raw in ("Here you go: " + block, block + " done", block + block,
                    block[:-len(END)], START + block + END, "```" + block + "```"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                decode(raw)

    def test_existing_json_can_contain_literal_marker_text(self):
        envelope = {"schema_version": "1.0", "action": "propose", "calls": [
            {"tool": "report_general_concern", "arguments": {"observation": START}}
        ]}
        self.assertEqual(envelope, decode(json.dumps(envelope)))

    def test_native_calls_use_existing_grounding_preview_and_confirmation(self):
        store = Store(ROOT / "seed_data/warehouse_catalog.jsonl")
        self.addCleanup(store.close)
        harness = Harness(store, Procedures(ROOT / "seed_data/sop_cards.jsonl"))
        raw = marked('[request_replenishment(product_name="Cloudweight Hoodie", '
                     'variant={"color":"Ink","size":"M"}, quantity=10)]')
        preview = harness.handle("Request ten Cloudweight Hoodies in ink, size medium.", raw)
        self.assertEqual("preview", preview["kind"])
        self.assertEqual([], store.tickets())
        confirm = {"schema_version": "1.0", "action": "confirm", "calls": []}
        rejected = harness.handle("No, do not save it.", confirm)
        self.assertNotEqual("saved", rejected["kind"])
        self.assertEqual([], store.tickets())
        saved = harness.handle("Yes, submit it.", confirm)
        self.assertEqual("saved", saved["kind"])
        self.assertEqual(10, saved["ticket"]["arguments"]["quantity"])
        # A valid syntax does not bypass grounding checks.
        harness.new_request()
        wrong = raw.replace("quantity=10", "quantity=11")
        self.assertEqual("failed", harness.handle(
            "Request ten Cloudweight Hoodies in ink, size medium.", wrong)["kind"])
        self.assertEqual(1, len(store.tickets()))


if __name__ == "__main__":
    unittest.main()
