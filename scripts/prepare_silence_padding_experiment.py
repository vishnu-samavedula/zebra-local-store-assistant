"""Prepare original/padded WAV pairs for the already-built native runner."""
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent/'zebra_npu_test'
sys.path.insert(0,str(REFERENCE/'qnn_experiment/audio_model'))
import common
import numpy as np
import soundfile as sf
import torch
from transformers import AutoTokenizer


def main():
    common.SOURCE = REFERENCE/'models/warehouse-v2-continue-20261019a'
    common.ROOT = REFERENCE/'build/qnn/warehouse-v2-continue-20261019a'
    destination = ROOT/'output'/time.strftime('audio-silence-padding-%Y%m%d-%H%M%S')
    destination.mkdir()
    tokenizer = AutoTokenizer.from_pretrained(common.SOURCE/'hf',local_files_only=True)
    source = ROOT/'models/warehouse-tests/warehouse-iteration-20261013g'
    fixtures = json.loads((source/'device-inputs.json').read_text())['cases']
    state = json.loads((ROOT/'output/warehouse-v2-repaired-20261018/evaluation-invocations.jsonl').read_text().splitlines()[0])['messages'][0]
    cases = []
    for fixture in fixtures:
        if fixture.get('audio_file') not in ['I01-1-0.wav','T01-1-0.wav']:
            continue
        waveform,rate = sf.read(source/fixture['audio_file'],dtype='float32')
        if rate != 16000 or waveform.ndim!=1 or len(waveform)>69600:
            raise ValueError('Only shorter mono 16kHz recordings are eligible; never truncate')
        for padding in [False,True]:
            pcm = np.pad(waveform,(0,69600-len(waveform))) if padding else waveform
            name = fixture['id'].split(':')[0]+('-padded'if padding else '-original')
            folder = destination/'cases'/name
            folder.mkdir(parents=True)
            sf.write(folder/'input.wav',pcm,rate,subtype='PCM_16')
            pcm.astype('<f4').tofile(folder/'pcm.raw')
            torch.manual_seed(0)
            mel,_ = common.mel_input(folder/'input.wav')
            count = (mel.shape[-1]+7)//8
            if padding and mel.shape[-1]!=435:
                raise ValueError('Padded waveform did not produce the existing graph shape')
            messages = [state,dict(role='user',content='AUDIO_SLOT_MARKER')]
            prompt = tokenizer.apply_chat_template(messages,tokenize=False,add_generation_prompt=True)
            before,after = prompt.split('AUDIO_SLOT_MARKER')
            prefix = tokenizer.encode(before,add_special_tokens=False)
            ids = prefix+[0]*count+tokenizer.encode(after,add_special_tokens=False)
            np.asarray(ids,dtype='<i4').tofile(folder/'prompt.i32')
            (folder/'audio-offset.txt').write_text(f'{len(prefix)} {count}\n')
            mel.numpy().astype('<f4').tofile(folder/'mel.raw')
            case = dict(name=name,padded=padding,original_seconds=len(waveform)/rate,
                input_seconds=len(pcm)/rate,frames=mel.shape[-1],audio_tokens=count,
                expected_calls=fixture['expected_calls'],expected_target=fixture['expected_target'],
                expected_ids=tokenizer.encode(fixture['expected_target']+'<|im_end|>',add_special_tokens=False),
                source_wave_sha256=common.sha(source/fixture['audio_file']),input_wave_sha256=common.sha(folder/'input.wav'))
            (folder/'case.json').write_text(json.dumps(case,indent=2)+'\n')
            cases.append(case)
    manifest = dict(checkpoint='warehouse-v2-continue-20261019a',cases=cases,built_artifacts_reused=True,
        app_changes=False,scope='Short recorded inventory/task requests; trailing silence only, no speech truncation.')
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(dict(destination=str(destination),cases=[{k:c[k]for k in ['name','input_seconds','frames','audio_tokens']}for c in cases]),indent=2))


if __name__=='__main__':
    main()
