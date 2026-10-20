"""Local ColBERT client; official encoder runs in a separate persistent process."""

import json
from pathlib import Path
import select
import subprocess

ROOT = Path(__file__).resolve().parents[1]


class ColbertRetriever:
    def __init__(self, python, model_dir, index_dir, timeout=90):
        self.timeout = timeout
        self.process = subprocess.Popen(
            [str(python), "-u", "-m", "warehouse_harness.colbert_worker", str(model_dir), str(index_dir)],
            cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, bufsize=1)
        try:
            self.metadata = self._read()
            if "error" in self.metadata:
                raise RuntimeError(self.metadata["error"])
        except Exception:
            self.close()
            raise

    def _read(self):
        ready, _, _ = select.select([self.process.stdout], [], [], self.timeout)
        if not ready:
            self.close()
            raise RuntimeError("Local ColBERT worker timed out")
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError("Local ColBERT worker stopped")
        return json.loads(line)

    def retrieve(self, text, k=3):
        if self.process.poll() is not None:
            raise RuntimeError("Local ColBERT worker is not running")
        self.process.stdin.write(json.dumps({"text": text, "k": k}) + "\n")
        self.process.stdin.flush()
        reply = self._read()
        if "error" in reply:
            raise ValueError(reply["error"])
        return reply["candidates"]

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        for stream in (self.process.stdin, self.process.stdout):
            if stream:
                stream.close()
