"""Compare existing binaries on frozen voice inputs without app lifecycle changes."""
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import threading
import time
import sys

from benchmark_isolated_audio_acceleration import ROOT, REFERENCE, ADB, REMOTE, shell, PACKAGE, MODEL

DEST = ROOT / 'output/warehouse-hybrid-screen-20261019-203043'
DEVICE = '/data/local/tmp/' + DEST.name
BRIDGE = '/data/local/tmp/qnn-llama-bridge-20261019'
GRAPHS = '/data/local/tmp/qnn-warehouse-v2-continue-20261019a'


def state(full=False):
    files = ['files/warehouse-tickets.db', 'files/warehouse-tickets.db-wal', 'files/warehouse-tickets.db-shm', 'shared_prefs/liquid-industrial-chats.xml']
    if full:
        files += [MODEL + x for x in ['model-Q8_0.gguf', 'projector-Q8_0.gguf', 'manifest.json']]
    script = 'for f in ' + ' '.join(files) + '; do if [ -f "$f" ]; then sha256sum "$f"; fi; done'
    return dict(pid=shell('pidof ' + PACKAGE + ' || true'), hashes=shell('run-as ' + PACKAGE + ' sh -c ' + shlex.quote(script)))


def run_block(index, group, mode, case=None):
    root = DEVICE + '/group' + str(group)
    if mode == 'cpu':
        executable = REMOTE + '/ghost'
        command = f'LD_LIBRARY_PATH={REMOTE} ADSP_LIBRARY_PATH={REMOTE} taskset f0 {executable} {root} {case} cpu 3 {DEVICE}/result-{index} 4'
    else:
        executable = BRIDGE + '/llama-bridge'
        shell(f'mkdir {DEVICE}/result-{index}')
        command = f'LD_LIBRARY_PATH={BRIDGE}:{GRAPHS} ADSP_LIBRARY_PATH={GRAPHS} taskset f0 {executable} {root} case00,case01,case02 {mode} 3 {DEVICE}/result-{index} 64 {REMOTE}/model-Q8_0.gguf'
    events, monitors, native = [], [], {}
    process = subprocess.Popen(ADB + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')

    def consume():
        with (DEST / 'runs' / f'{index}-{mode}.log').open('w') as log:
            for line in process.stdout:
                log.write(line)
                log.flush()
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not isinstance(event, dict):
                    continue
                events.append(event)
                if event.get('event') == 'start':
                    native['pid'] = event['pid']
                if event.get('event') == 'result':
                    print(json.dumps(dict(block=index, group=group, case=event.get('case', case), mode=mode, trial=event['trial'], ms=event.get('request_ms', event.get('total_ms')))), flush=True)
    reader = threading.Thread(target=consume)
    reader.start()
    started = time.monotonic()
    try:
        while process.poll() is None:
            memory = int(next(x.split()[1] for x in shell('cat /proc/meminfo').splitlines() if x.startswith('MemAvailable:')))
            pid = shell('pidof ' + PACKAGE + ' || true')
            monitors.append(dict(seconds=time.monotonic()-started, available_kib=memory))
            if pid != BASELINE['pid'] or memory < 2*1024*1024 or time.monotonic()-started > 600:
                raise RuntimeError('App lifecycle, memory or time guard triggered')
            time.sleep(2)
        if process.returncode:
            raise RuntimeError(f'Native runner failed, block {index}')
    finally:
        if process.poll() is None:
            pid = native.get('pid')
            if pid and shell(f'cat /proc/{pid}/cmdline').startswith(executable):
                shell('kill -TERM ' + str(pid))
            process.terminate()
        process.wait(timeout=15)
        reader.join(timeout=15)
        block = dict(index=index, group=group, mode=mode, case=case, events=events, monitors=monitors)
        (DEST / 'runs' / f'{index}-events.json').write_text(json.dumps(block, indent=2)+'\n')
    rows = [e for e in events if e.get('event') == 'result']
    assert len(rows) == (3 if mode == 'cpu' else 9), (index, len(rows))
    if mode != 'cpu':
        assert all(r['state_roundtrip_exact'] for r in rows)
    return block


def main():
    global BASELINE
    manifest = json.loads((DEST / 'manifest.json').read_text())
    resume = '--resume' in sys.argv
    (DEST / 'runs').mkdir(exist_ok=resume)
    before = state(True)
    if resume:
        assert before == json.loads((DEST / 'before.json').read_text())
    BASELINE = state()
    (DEST / 'before.json').write_text(json.dumps(before, indent=2)+'\n')
    for folder, name, host in [(REMOTE, 'ghost', REFERENCE/'build/qnn/ghost-audio/ghost'), (BRIDGE, 'llama-bridge', REFERENCE/'build/qnn/llama-bridge/llama-bridge')]:
        assert shell('sha256sum ' + folder + '/' + name).split()[0] == hashlib.sha256(host.read_bytes()).hexdigest()
    for name, expected in [('model-Q8_0.gguf', '9bc63e89eaf2cb8cfbaf175fbb2f204d7f5cc0c2998cbd486744ab7858fee598'), ('projector-Q8_0.gguf', '171841e9e0485d08a4af4bcb999d9b7335feac5182990c4e794c7439e137ee2a')]:
        assert shell('sha256sum ' + REMOTE + '/' + name).split()[0] == expected
        assert expected in before['hashes']
    if not resume:
        shell('mkdir ' + DEVICE)
    for group in range(4):
        root = DEVICE + '/group' + str(group)
        if not resume:
            shell('mkdir ' + root)
        local_cases = DEST / ('group'+str(group)) / 'cases'
        if resume:
            for path in local_cases.glob('*/*'):
                subprocess.run(ADB + ['push', str(path), root+'/cases/'+path.parent.name+'/'+path.name], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        else:
            subprocess.run(ADB + ['push', str(local_cases), root+'/cases'], check=True, stdout=subprocess.PIPE)
        for name in ['audio', 'prefill']:
            if not resume:
                shell(f'ln -s {GRAPHS}/{name} {root}/{name}')
        for name in ['model-Q8_0.gguf', 'projector-Q8_0.gguf']:
            if not resume:
                shell(f'ln -s {REMOTE}/{name} {root}/{name}')
    blocks = json.loads((DEST/'progress.json').read_text()) if resume else []
    completed = len(blocks)
    index = 0
    orders = [['cpu','llama-burst','hybrid-burst'], ['hybrid-burst','llama-burst','cpu'], ['cpu','hybrid-burst','llama-burst'], ['llama-burst','hybrid-burst','cpu']]
    try:
        for group, order in enumerate(orders):
            for mode in order:
                cases = [c['inputs']['original']['name'] for c in manifest['cases'] if c['group'] == group] if mode == 'cpu' else [None]
                for case in cases:
                    if index < completed:
                        index += 1
                        continue
                    assert state() == BASELINE, 'Protected app files changed'
                    blocks.append(run_block(len(blocks), group, mode, case))
                    (DEST / 'progress.json').write_text(json.dumps(blocks, indent=2)+'\n')
                    index += 1
    finally:
        after = state(True)
        (DEST / 'preservation.json').write_text(json.dumps(dict(passed=before==after,before=before,after=after), indent=2)+'\n')
    assert before == after
    print('Completed 108 outputs; app preserved.', flush=True)


if __name__ == '__main__':
    main()
