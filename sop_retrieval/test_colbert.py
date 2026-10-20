"""Check MaxSim and the variable-length ColBERT bundle without loading weights."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import sop_colbert as colbert
import sop_embeddings as shared


class MaxSimTest(unittest.TestCase):
    def test_best_document_match_is_summed_for_each_query_token(self):
        query = np.eye(2, dtype=np.float32)
        self.assertAlmostEqual(colbert.maxsim(query, np.eye(2, dtype=np.float32)), 2)
        self.assertAlmostEqual(colbert.maxsim(query, np.array([[1, 0]], dtype=np.float32)), 1)

    def test_negative_matches_are_not_replaced_by_zero_padding(self):
        query = np.array([[1, 0]], dtype=np.float32)
        document = np.array([[-1, 0]], dtype=np.float32)
        self.assertEqual(colbert.maxsim(query, document), -1)

    def test_long_query_fails_before_silent_truncation(self):
        class Processor:
            def __call__(self, *args, **kwargs):
                return {"input_ids": [[0] * 33]}
        class Module:
            processor = Processor()
        class Model:
            prompts = {"query": "[Q] ", "document": "[D] "}
            def __getitem__(self, index):
                return Module()
            def encode_query(self, *args, **kwargs):
                raise AssertionError("Overlength queries must not reach encoding")
        with self.assertRaisesRegex(ValueError, "query input exceeds 32"):
            colbert.encode(Model(), ["long input"], query=True)


class ColbertBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.index = root / "index"
        self.index.mkdir()
        self.cards = [dict(id=f"SOP-{i}", title=f"Card {i}", type="incident", status="draft", version=1,
                           guidance="Demo", escalation="Demo", embedding_text=f"Demo {i}") for i in (1, 2)]
        self.source = root / "cards.jsonl"
        self.source.write_text("\n".join(json.dumps(c) for c in self.cards))
        self.policy = root / "policy.json"
        self.policy.write_text('{"rules": []}')
        shared.write_json(self.index / "cards.json", self.cards)
        shared.write_json(self.index / "policy.json", {"rules": []})
        vectors = np.zeros((5, 128), dtype="<f4")
        vectors[:2, 0] = 1
        vectors[2:, 1] = 1
        vectors.tofile(self.index / "token_vectors.f32")
        self.manifest = {"schema_version": 1, "retriever": "colbert", "dimension": 128,
                         "normalized": True, "similarity": "sum_maxsim",
                         "cards": [{"id": "SOP-1", "vector_offset": 0, "vector_count": 2},
                                   {"id": "SOP-2", "vector_offset": 2, "vector_count": 3}],
                         "source": {"cards_sha256": shared.sha256(self.source), "policy_sha256": shared.sha256(self.policy)},
                         "files": {name: shared.sha256(self.index / name) for name in
                                   ("cards.json", "policy.json", "token_vectors.f32")}}
        self.save()

    def save(self):
        shared.write_json(self.index / "manifest.json", self.manifest)

    def load(self):
        return colbert.load_index(self.index, self.source, self.policy)

    def test_variable_length_vectors_return_the_correct_card(self):
        _, cards, vectors = self.load()
        self.assertEqual([len(v) for v in vectors], [2, 3])
        self.assertEqual(colbert.rank(cards, vectors, vectors[1][:1], 1)[0]["card"], self.cards[1])

    def test_invalid_offsets_cannot_misassign_vectors(self):
        self.manifest["cards"][1]["vector_offset"] = 1
        self.save()
        with self.assertRaisesRegex(ValueError, "Invalid token-vector offsets"):
            self.load()

    def test_unreferenced_vectors_are_rejected(self):
        self.manifest["cards"][1]["vector_count"] = 2
        self.save()
        with self.assertRaisesRegex(ValueError, "Unreferenced token vectors"):
            self.load()

    def test_corrupted_vectors_are_rejected(self):
        (self.index / "token_vectors.f32").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "Index file changed"):
            self.load()

    def test_changed_source_requires_rebuild(self):
        self.source.write_text(self.source.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "SOP source changed"):
            self.load()


if __name__ == "__main__":
    unittest.main()
