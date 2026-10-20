"""Repeat the existing shell-only encoder experiment without touching app state."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import statistics
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT.parent / 'zebra_npu_test'
DEVICE_SERIAL = os.environ.get('ZEBRA_SERIAL') or os.environ.get('ANDROID_SERIAL')
ADB = ['adb'] + (['-s', DEVICE_SERIAL] if DEVICE_SERIAL else [])
REMOTE = '/data/local/tmp/qnn-llama-ghost-20261019'
PACKAGE = 'com.example.zebralocalai'
MODEL = 'files/models/warehouse-test/warehouse-v2-continue-20261019a/'


def shell(command):
    return subprocess.check_output(ADB + ['shell', command], text=True, stderr=subprocess.STDOUT, timeout=30).strip()


def app_state(include_models=False):
    files = ['files/warehouse-tickets.db', 'files/warehouse-tickets.db-wal',
             'files/warehouse-tickets.db-shm']
    if include_models:
        files += [MODEL + name for name in ['model-Q8_0.gguf', 'projector-Q8_0.gguf', 'manifest.json']]
    script = 'for f in ' + ' '.join(files) + '; do if [ -f "$f" ]; then sha256sum "$f"; fi; done'
    hashes = shell('run-as ' + PACKAGE + ' sh -c ' + shlex.quote(script))
    preferences = ET.fromstring(shell('run-as ' + PACKAGE + ' cat shared_prefs/liquid-industrial-chats.xml'))
    values = {}
    for entry in preferences:
        value = entry.text if entry.tag == 'string' else entry.attrib.get('value')
        if entry.tag == 'string':
            try: value = json.loads(value)
            except (TypeError, json.JSONDecodeError): pass
        values[entry.attrib['name']] = (entry.tag, value)
    preference_sha = hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()
    processes = shell('ps -A -o PID,ARGS').splitlines()
    servers = [line.strip() for line in processes if 'libllama-audio-cpu-server.so' in line]
    return dict(app_pid=shell('pidof ' + PACKAGE), servers=servers, file_hashes=hashes, logical_preferences_sha256=preference_sha)


def main():
    name = time.strftime('zebra-isolated-audio-acceleration-%Y%m%d-%H%M%S')
    destination = ROOT / 'output' / name
    destination.mkdir(parents=True, exist_ok=False)
    remote_output = '/data/local/tmp/' + name
    before = app_state(True)
    if not before['app_pid']:
        raise RuntimeError('App must already be running; no app lifecycle commands are issued')
    runtime = REFERENCE / 'build/qnn/ghost-audio'
    binary_sha = hashlib.sha256((runtime / 'ghost').read_bytes()).hexdigest()
    if shell('sha256sum ' + REMOTE + '/ghost').split()[0] != binary_sha:
        raise RuntimeError('Existing native runner differs from the reviewed host binary')
    manifest = json.loads((ROOT / 'models/warehouse-tests/warehouse-v2-continue-20261019a/manifest.json').read_text())
    for filename, item in manifest['artifacts'].items():
        expected = item['sha256']
        if shell('sha256sum ' + REMOTE + '/' + filename).split()[0] != expected:
            raise RuntimeError('Isolated model differs from current checkpoint')
        if expected not in before['file_hashes']:
            raise RuntimeError('Active model differs from isolated model')
    case = json.loads((runtime / 'cases/case01/case.json').read_text())
    baseline = app_state()
    (destination / 'before.json').write_text(json.dumps(before, indent=2) + '\n')
    shell('mkdir ' + remote_output)
    blocks = []
    try:
        for index, mode in enumerate(['cpu', 'npu-burst', 'cpu']):
            if app_state() != baseline:
                raise RuntimeError('App state changed; stopping without modifying the app')
            prefix = remote_output + '/block-' + str(index)
            command = ('cd ' + REMOTE + ' && LD_LIBRARY_PATH=' + REMOTE + ' ADSP_LIBRARY_PATH=' + REMOTE
                       + ' taskset f0 ' + REMOTE + '/ghost ' + REMOTE + ' case01 ' + mode + ' 6 ' + prefix + ' 4')
            process = subprocess.Popen(ADB + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            events, monitors, native = [], [], {}

            def consume():
                with (destination / f'block-{index}-{mode}.log').open('w') as log:
                    for line in process.stdout:
                        log.write(line)
                        log.flush()
                        if line.strip().startswith('{'):
                            event = json.loads(line)
                            events.append(event)
                            if event.get('event') == 'start':
                                native['pid'] = event['pid']
                            if event.get('event') == 'result':
                                print(json.dumps(dict(block=index, mode=mode, trial=event['trial'],
                                                      total_ms=event['total_ms'])), flush=True)

            reader = threading.Thread(target=consume)
            reader.start()
            start = time.monotonic()
            try:
                while process.poll() is None:
                    state = app_state()
                    memory = int(next(line.split()[1] for line in shell('cat /proc/meminfo').splitlines()
                                      if line.startswith('MemAvailable:')))
                    monitors.append(dict(seconds=time.monotonic()-start, available_kib=memory))
                    if state != baseline or memory < 2 * 1024 * 1024 or time.monotonic()-start > 180:
                        (destination / 'guard-detail.json').write_text(json.dumps(dict(expected=baseline, actual=state,
                            available_kib=memory, elapsed=time.monotonic()-start), indent=2)+'\n')
                        raise RuntimeError('App-state, available-memory or experiment-time guard triggered')
                    time.sleep(2)
                if process.returncode:
                    raise RuntimeError('Standalone runner failed; inspect its log')
            finally:
                if process.poll() is None:
                    pid = native.get('pid')
                    if pid and shell(f'cat /proc/{pid}/cmdline').startswith(REMOTE + '/ghost'):
                        shell('kill -TERM ' + str(pid))
                    process.terminate()
                process.wait(timeout=15)
                reader.join(timeout=15)
                (destination / f'block-{index}-events.json').write_text(json.dumps(events, indent=2) + '\n')
                (destination / f'block-{index}-monitor.json').write_text(json.dumps(monitors, indent=2) + '\n')
            rows = [event for event in events if event.get('event') == 'result']
            if len(rows) != 6 or any(row['generated_ids'] != case['reference_ids'] for row in rows):
                raise RuntimeError('Incomplete or output-inconsistent trial; no speedup acceptance')
            blocks.append(dict(mode=mode, rows=rows, initialization=next(e for e in events if e['event']=='ready')))
    finally:
        after = app_state(True)
        (destination / 'preservation.json').write_text(json.dumps(dict(passed=before == after, before=before, after=after), indent=2)+'\n')
    if before != after:
        raise RuntimeError('App preservation check failed; inspect snapshots')
    groups = {}
    for mode in ['cpu', 'npu-burst']:
        rows = [r for block in blocks if block['mode']==mode for r in block['rows'][1:]]
        groups[mode] = dict(warm_samples=len(rows), median_ms={key: statistics.median(r[key] for r in rows)
            for key in ['audio_ms','prefill_ms','first_token_ms','decode_ms','total_ms']},
            minimum_total_ms=min(r['total_ms'] for r in rows), maximum_total_ms=max(r['total_ms'] for r in rows),
            peak_rss_kib=max(r['peak_rss_kib'] for r in rows))
    summary = dict(checkpoint='warehouse-v2-continue-20261019a', native_sha256=binary_sha,
        exact_trials=18, total_trials=18, preserved=True, groups=groups, blocks=blocks,
        expected_target=case['expected_target'], remote_output=remote_output,
        scope='One existing 4.35-second development recording. Encoder-only QNN acceleration; language remains llama.cpp CPU.',
        limitations=['No app integration or tool execution; app/database/model hashes and existing server PIDs preserved.',
            'Synthetic calibration fixture; no held-out quality validation.',
            'Precomputed mel features for NPU; recording, frontend, tokenization, initialization, transcription and retrieval excluded from request timings.',
            'First request excluded per process; no other samples excluded. CPU affinity applies only to experiment.',
            'Previously measured 42.17% acoustic embedding relative error remains unresolved.'])
    (destination / 'summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(dict(output=str(destination), groups=groups, exact_trials=18, preserved=True), indent=2))


if __name__ == '__main__':
    main()
