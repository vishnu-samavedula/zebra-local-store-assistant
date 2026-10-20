"""Use only the prebuilt runner/graphs, copied cases, and read-only app checks."""
import json
from pathlib import Path
import shlex
import statistics
import subprocess
import threading
import time

from benchmark_isolated_audio_acceleration import ROOT, REMOTE, ADB, app_state, shell


def protected(state):
    return {k:v for k,v in state.items() if k!='logical_preferences_sha256'}


def main():
    destination = max((ROOT/'output').glob('audio-silence-padding-*'),key=lambda p:p.name)
    manifest = json.loads((destination/'manifest.json').read_text())
    if (destination/'runs').exists():
        raise FileExistsError('Existing runs preserved')
    (destination/'runs').mkdir()
    before = app_state(True)
    baseline = app_state()
    if not before['app_pid']:
        raise RuntimeError('App must already be running; no app lifecycle calls permitted')
    remote = '/data/local/tmp/'+destination.name
    shell('mkdir '+remote)
    for name in ['model-Q8_0.gguf','projector-Q8_0.gguf','audio']:
        shell('ln -s '+REMOTE+'/'+name+' '+remote+'/'+name)
    subprocess.run(ADB+['push',str(destination/'cases'),remote+'/cases'],check=True)
    blocks=[]
    plan=[(c['name'],mode)for c in manifest['cases']for mode in (['cpu','npu-burst']if c['padded']else['cpu'])]
    try:
        for index,(case,mode) in enumerate(plan):
            if protected(app_state())!=protected(baseline):
                raise RuntimeError('App state changed before block; leaving the app untouched')
            events=[]
            native={}
            log_path=destination/'runs'/f'{index}-{case}-{mode}.log'
            command=f'cd {REMOTE} && LD_LIBRARY_PATH={REMOTE} ADSP_LIBRARY_PATH={REMOTE} taskset f0 {REMOTE}/ghost {remote} {case} {mode} 3 {remote}/result-{index} 4'
            process=subprocess.Popen(ADB+['shell',command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)

            def consume():
                with log_path.open('w')as log:
                    for line in process.stdout:
                        log.write(line);log.flush()
                        if line.strip().startswith('{'):
                            event=json.loads(line);events.append(event)
                            if event.get('event')=='start':native['pid']=event['pid']
                            if event.get('event')=='result':print(json.dumps(dict(case=case,mode=mode,trial=event['trial'],total_ms=event['total_ms'])),flush=True)
            reader=threading.Thread(target=consume);reader.start();start=time.monotonic()
            monitors=[]
            try:
                while process.poll()is None:
                    state=app_state()
                    available=int(next(line.split()[1]for line in shell('cat /proc/meminfo').splitlines()if line.startswith('MemAvailable:')))
                    monitors.append(dict(seconds=time.monotonic()-start,available_kib=available))
                    if protected(state)!=protected(baseline) or available<2*1024*1024 or time.monotonic()-start>180:
                        (destination/'guard-detail.json').write_text(json.dumps(dict(expected=baseline,actual=state,available_kib=available),indent=2)+'\n')
                        raise RuntimeError('App-state, memory or duration guard triggered')
                    time.sleep(2)
                if process.returncode:
                    raise RuntimeError('Existing native runner failed; inspect block log')
            finally:
                if process.poll()is None:
                    pid=native.get('pid')
                    if pid and shell(f'cat /proc/{pid}/cmdline').startswith(REMOTE+'/ghost'):
                        shell('kill -TERM '+str(pid))
                    process.terminate()
                process.wait(timeout=15);reader.join(timeout=15)
                (destination/'runs'/f'{index}-events.json').write_text(json.dumps(events,indent=2)+'\n')
                (destination/'runs'/f'{index}-monitor.json').write_text(json.dumps(monitors,indent=2)+'\n')
            expected=next(c for c in manifest['cases']if c['name']==case)
            rows=[e for e in events if e.get('event')=='result']
            if len(rows)!=3:raise RuntimeError('Incomplete block')
            warm=rows[1:]
            block=dict(case=case,mode=mode,exact_calls=sum(r['generated_ids']==expected['expected_ids']for r in rows),
                trials=3,warm_samples=2,median_ms={key:statistics.median(r[key]for r in warm)for key in
                    ['audio_ms','prefill_ms','first_token_ms','decode_ms','total_ms']},events=events)
            blocks.append(block)
            (destination/'progress.json').write_text(json.dumps(blocks,indent=2)+'\n')
    finally:
        after=app_state(True)
        (destination/'preservation.json').write_text(json.dumps(dict(passed=protected(before)==protected(after),logical_preferences_unchanged=before['logical_preferences_sha256']==after['logical_preferences_sha256'],before=before,after=after),indent=2)+'\n')
    report=dict(completed=True,preserved=protected(before)==protected(after),logical_preferences_unchanged=before['logical_preferences_sha256']==after['logical_preferences_sha256'],blocks=blocks,builds_performed=False,app_changes=False,
        exact_outputs=sum(b['exact_calls']for b in blocks),total_outputs=sum(b['trials']for b in blocks),
        limitations=['Two existing synthetic development recordings; not a generalization test.',
            'Only two warm samples per condition; no stable tail-latency claim.',
            'NPU uses existing host mel frontend, CPU uses native frontend; both consume the same waveform.',
            'Timings exclude recording, frontend, tokenization, initialization, transcription, retrieval and UI.',
            'Existing quantization drift remains unresolved. No audio longer than 4.35 seconds is truncated.',
            'App preferences are observed only; their changes do not abort the test. Database/model/process changes still abort.'])
    (destination/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items()if k!='blocks'},indent=2))


if __name__=='__main__':
    main()
