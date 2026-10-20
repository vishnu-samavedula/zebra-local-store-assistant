"""Prepare frozen warehouse voice cases for existing native binaries; no exports."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent / 'zebra_npu_test'
DEST = ROOT / 'output/warehouse-hybrid-screen-20261019-203043'
sys.path.insert(0, str(REFERENCE / 'qnn_experiment/audio_model'))
import common
import numpy as np
import soundfile as sf
import torch
from transformers import AutoTokenizer


def main():
    common.SOURCE = REFERENCE / 'models/warehouse-v2-continue-20261019a'
    common.ROOT = REFERENCE / 'build/qnn/warehouse-v2-continue-20261019a'
    tokenizer = AutoTokenizer.from_pretrained(common.SOURCE / 'hf', local_files_only=True)
    selection = json.loads((DEST / 'selection.json').read_text())
    for case in selection['cases']:
        source = DEST / 'audio' / (case['screen_id'] + '.wav')
        assert hashlib.sha256(source.read_bytes()).hexdigest() == case['recording']['sha256']
        wave, rate = sf.read(source, dtype='float32')
        assert rate == 16000 and wave.ndim == 1 and len(wave) <= 69600
        invocation = case['invocation']
        messages = invocation['messages'][:-1] + [dict(role='user', content='AUDIO_SLOT_MARKER')]
        prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        before, after = prompt.split('AUDIO_SLOT_MARKER')
        prefix = tokenizer.encode(before, add_special_tokens=False)
        suffix = tokenizer.encode(after, add_special_tokens=False)
        case['expected_ids'] = tokenizer.encode(invocation['assistant_target'] + '<|im_end|>', add_special_tokens=False)
        case['inputs'] = {}
        for padded in [False, True]:
            name = case['slot'] + ('' if padded else '-original')
            folder = DEST / ('group' + str(case['group'])) / 'cases' / name
            folder.mkdir(parents=True, exist_ok=False)
            pcm = np.pad(wave, (0, 69600-len(wave))) if padded else wave
            sf.write(folder / 'input.wav', pcm, rate, subtype='PCM_16')
            pcm.astype('<f4').tofile(folder / 'pcm.raw')
            torch.manual_seed(0)
            mel, _ = common.mel_input(folder / 'input.wav')
            # Native center-padded frontend keeps floor(samples/hop)+1 frames.
            # The Hugging Face frontend drops that final frame; only original
            # CPU cases must reserve the native encoder's actual token count.
            native_frames = len(pcm) // 160 + 1
            count = ((mel.shape[-1] if padded else native_frames) + 7) // 8
            if padded:
                assert mel.shape[-1] == 435 and count == 55
            ids = prefix + [0] * count + suffix
            assert len(ids) + 64 <= 256, (case['screen_id'], len(ids))
            np.asarray(ids, dtype='<i4').tofile(folder / 'prompt.i32')
            (folder / 'audio-offset.txt').write_text(f'{len(prefix)} {count}\n')
            mel.numpy().astype('<f4').tofile(folder / 'mel.raw')
            case['inputs']['padded' if padded else 'original'] = dict(name=name, prompt_tokens=len(ids), audio_tokens=count, frames=mel.shape[-1])
        print(case['screen_id'], case['inputs'], flush=True)
    (DEST / 'manifest.json').write_text(json.dumps(selection, indent=2) + '\n')


if __name__ == '__main__':
    main()
