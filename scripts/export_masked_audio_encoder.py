"""Export length-aware acoustic graphs for an opt-in same-app experiment."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent / 'zebra_npu_test'
sys.path.insert(0, str(REFERENCE / 'qnn_experiment/audio_model'))
import common
import numpy as np
import onnxruntime as ort
import torch
from torch import nn

DESTINATION = ROOT/'training_kitchen/exports/masked-audio-435-v2'
FRAMES = 435
TOKENS = (FRAMES+7)//8


class Subsample(nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.module = encoder.pre_encode
        self.position = encoder.pos_enc

    def forward(self, mel, length):
        hidden, _ = self.module(mel.transpose(1, 2), length.to(torch.int64))
        return self.position(hidden)[0]


class Block(nn.Module):
    def __init__(self, layer, position):
        super().__init__()
        self.layer = layer
        self.register_buffer('position', position)

    def forward(self, hidden, padding, attention):
        return self.layer(hidden, att_mask=attention > .5, pos_emb=self.position, pad_mask=padding > .5)


def main():
    if DESTINATION.exists():
        raise FileExistsError('Existing export preserved; choose a new version')
    common.SOURCE = REFERENCE/'models/warehouse-v2-continue-20261019a'
    common.ROOT = REFERENCE/'build/qnn/warehouse-v2-continue-20261019a'
    torch.set_num_threads(4)
    encoder, adapter = common.load_encoder()
    mel, _ = common.mel_input()
    encoder.update_max_seq_length(FRAMES, torch.device('cpu'))
    _, position = encoder.pos_enc(torch.zeros(1, TOKENS, 512))
    modules = [('subsample', Subsample(encoder), ['mel','length']),
               *[(f'block{i:02}', Block(layer, position), ['hidden','padding','attention'])
                 for i, layer in enumerate(encoder.layers)], ('adapter', adapter, ['hidden'])]
    DESTINATION.mkdir(parents=True)
    manifest = dict(checkpoint='warehouse-v2-continue-20261019a', source_export_sha256=common.sha(common.SOURCE/'hf/model.safetensors'),
        frames=FRAMES, tokens=TOKENS, graphs=[], tests=[], deployment_approved=False,
        limits='Only <=435 actual mel frames; larger inputs require unchanged CPU fallback. No truncation/resampling to fit.')
    sessions = {}

    def capture(name, module, names, inputs, sample):
        module.eval()
        folder = DESTINATION/name
        folder.mkdir(exist_ok=True)
        output = module(*inputs)
        if name not in sessions:
            path = folder/'model.onnx'
            torch.onnx.export(module, inputs, path, input_names=names, output_names=['output'], opset_version=13, dynamo=False)
            sessions[name] = ort.InferenceSession(str(path), providers=['CPUExecutionProvider'])
            manifest['graphs'].append(dict(name=name, inputs=names, shapes={n:list(t.shape)for n,t in zip(names, inputs)},
                onnx_sha256=common.sha(path)))
        got = sessions[name].run(None, {n:t.numpy()for n,t in zip(names,inputs)})[0]
        error = float(np.linalg.norm(got-output.numpy())/max(np.linalg.norm(output.numpy()),1e-20))
        if error > 1e-4:
            raise RuntimeError(f'Export parity failed: {name} sample {sample}: {error}')
        path = folder/f'sample-{sample:02}'
        path.mkdir()
        entries = []
        for n,t in zip(names,inputs):
            data = path/(n+'.raw')
            t.numpy().astype(np.float32).tofile(data)
            entries.append(n+':=/workspace/'+str(data.relative_to(ROOT)))
        output.numpy().tofile(path/'expected-output.raw')
        with (folder/'calibration.txt').open('a') as file:
            file.write(' '.join(entries)+'\n')
        return output

    with torch.no_grad():
        for sample,frames in enumerate([100,200,300,435]):
            length = torch.tensor([float(frames)])
            valid = (frames+7)//8
            padded = torch.nn.functional.pad(mel[:,:,:frames], (0,FRAMES-frames))
            padding, attention = encoder._create_masks(att_context_size=encoder.att_context_size,
                padding_length=torch.tensor([valid]), max_audio_length=TOKENS, offset=None, device=torch.device('cpu'))
            hidden = capture(*modules[0], (padded,length), sample)
            for name,module,names in modules[1:-1]:
                hidden = capture(name,module,names,(hidden,padding.float(),attention.float()),sample)
            adapted = capture(*modules[-1], (hidden,), sample)
            reference,actual_length = encoder(audio_signal=mel[:,:,:frames],length=torch.tensor([frames]))
            expected = adapter(reference.transpose(1,2))[:,:valid]
            error = float(torch.linalg.vector_norm(adapted[:,:valid]-expected)/torch.linalg.vector_norm(expected))
            if error > 1e-4 or int(actual_length[0]) != valid:
                raise RuntimeError(f'Masked graph decomposition differs from original encoder: {error}')
            manifest['tests'].append(dict(frames=frames, valid_tokens=valid, relative_error=error, passed=True))
            print(json.dumps(manifest['tests'][-1]),flush=True)
    manifest['host_export_parity_passed'] = True
    (DESTINATION/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(str(DESTINATION),flush=True)


if __name__ == '__main__':
    main()
