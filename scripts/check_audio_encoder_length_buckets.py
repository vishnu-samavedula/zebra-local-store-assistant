"""Check masked length buckets against this checkpoint's unpadded encoder."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent / 'zebra_npu_test'
sys.path.insert(0, str(REFERENCE / 'qnn_experiment/audio_model'))
import common
import torch


def main():
    common.SOURCE = REFERENCE / 'models/warehouse-v2-continue-20261019a'
    common.ROOT = REFERENCE / 'build/qnn/warehouse-v2-continue-20261019a'
    torch.set_num_threads(4)
    encoder, adapter = common.load_encoder()
    mel, _ = common.mel_input()
    results = []
    with torch.no_grad():
        for frames, bucket in [(100, 435), (200, 435), (300, 435), (435, 435), (435, 800)]:
            sample = mel[:, :, :frames]
            length = torch.tensor([frames])
            direct, direct_length = encoder(audio_signal=sample, length=length)
            padded = torch.nn.functional.pad(sample, (0, bucket-frames))
            fixed, fixed_length = encoder(audio_signal=padded, length=length)
            valid = int(direct_length[0])
            left = adapter(direct.transpose(1, 2))[:, :valid]
            right = adapter(fixed.transpose(1, 2))[:, :valid]
            error = float(torch.linalg.vector_norm(left-right)/torch.linalg.vector_norm(left))
            result = dict(frames=frames, bucket=bucket, valid_embeddings=valid,
                relative_error=error, max_absolute_error=float((left-right).abs().max()),
                passed=error < 1e-4 and torch.equal(direct_length, fixed_length))
            results.append(result)
            print(json.dumps(result), flush=True)
    destination = ROOT/'output/audio-encoder-length-buckets-20261019'
    destination.mkdir(parents=True, exist_ok=True)
    (destination/'verification.json').write_text(json.dumps(dict(passed=all(r['passed']for r in results),
        checkpoint='warehouse-v2-continue-20261019a', source_export_sha256=common.sha(common.SOURCE/'hf/model.safetensors'),
        tests=results, scope='Host floating-point padding/mask equivalence; not NPU or app integration.'),indent=2)+'\n')


if __name__ == '__main__':
    main()
