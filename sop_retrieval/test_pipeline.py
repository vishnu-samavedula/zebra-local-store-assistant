"""Integrity checks for local SOP bundles, independent of model inference."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

SPEC = importlib.util.spec_from_file_location("sop_embeddings", Path(__file__).resolve().parents[1] / "scripts/sop_embeddings.py")
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)


class IndexIntegrityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.index = self.root / "index"
        self.index.mkdir()
        self.cards = [dict(id=f"SOP-{i}", title=f"Card {i}", type="incident", status="draft", version=1,
                           guidance="Demo guidance", escalation="Demo escalation", embedding_text=f"Demo {i}") for i in (1, 2)]
        self.source = self.root / "cards.jsonl"
        self.source.write_text("\n".join(json.dumps(c) for c in self.cards))
        self.policy = self.root / "policy.json"
        self.policy.write_text('{"rules": ["demo"]}')
        pipeline.write_json(self.index / "cards.json", self.cards)
        pipeline.write_json(self.index / "policy.json", {"rules": ["demo"]})
        vectors = np.zeros((2, 384), dtype="<f4")
        vectors[0, 0] = 1
        vectors[1, 1] = 1
        vectors.tofile(self.index / "vectors.f32")
        self.manifest = {"schema_version": 1, "dimension": 384, "normalized": True,
                         "query_prefix": pipeline.QUERY_PREFIX, "cards": self.cards,
                         "source": {"cards_sha256": pipeline.sha256(self.source), "policy_sha256": pipeline.sha256(self.policy)},
                         "files": {name: pipeline.sha256(self.index / name) for name in ("cards.json", "policy.json", "vectors.f32")}}
        self.save_manifest()

    def save_manifest(self):
        pipeline.write_json(self.index / "manifest.json", self.manifest)

    def load(self):
        return pipeline.load_index(self.index, self.source, self.policy)

    def test_vectors_return_the_correct_full_record(self):
        _, cards, vectors = self.load()
        result = pipeline.rank(cards, vectors, vectors[1], 1)
        self.assertEqual(result[0]["card"], self.cards[1])
        self.assertAlmostEqual(result[0]["score"], 1)

    def test_changed_source_requires_rebuild(self):
        self.source.write_text(self.source.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "SOP source changed"):
            self.load()

    def test_changed_policy_requires_rebuild(self):
        self.policy.write_text('{"rules": ["updated"]}')
        with self.assertRaisesRegex(ValueError, "SOP policy changed"):
            self.load()

    def test_corrupted_vectors_are_rejected(self):
        (self.index / "vectors.f32").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "Index file changed"):
            self.load()

    def test_card_order_cannot_change_silently(self):
        pipeline.write_json(self.index / "cards.json", self.cards[::-1])
        self.manifest["files"]["cards.json"] = pipeline.sha256(self.index / "cards.json")
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, "Card/vector order mismatch"):
            self.load()

    def test_duplicate_ids_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate SOP IDs"):
            pipeline.validate_cards([self.cards[0], self.cards[0]])

    def test_overlength_input_is_rejected_before_encoding(self):
        class Tokenizer:
            def __call__(self, texts, **kwargs):
                return {"input_ids": [[0] * 513 for _ in texts]}
        class Model:
            tokenizer = Tokenizer()
            max_seq_length = 512
            def encode(self, *args, **kwargs):
                raise AssertionError("Overlength input must not reach inference")
        with self.assertRaisesRegex(ValueError, "Input exceeds 512"):
            pipeline.encode(Model(), ["too long"])


if __name__ == "__main__":
    unittest.main()
