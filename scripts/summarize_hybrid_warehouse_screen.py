"""Score raw tools and arguments and summarize paired warm latency."""
import html
import json
from pathlib import Path
import statistics
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from warehouse_harness.contracts import decode
from transformers import AutoTokenizer

DEST = ROOT / 'output/warehouse-hybrid-screen-20261019-203043'


def median(rows, key):
    return statistics.median(r[key] for r in rows)


def main():
    tokenizer = AutoTokenizer.from_pretrained(ROOT.parent / 'zebra_npu_test/models/warehouse-v2-continue-20261019a/hf', local_files_only=True)
    manifest = json.loads((DEST / 'manifest.json').read_text())
    cases = manifest['cases']
    rows, initializations = [], []
    for path in sorted((DEST / 'runs').glob('*-events.json')):
        block = json.loads(path.read_text())
        for event in block['events']:
            if event.get('event') == 'ready':
                initializations.append(dict(block=block['index'], mode=block['mode'], **event))
            if event.get('event') != 'result':
                continue
            slot = event.get('case', block['case']).replace('-original', '')
            case = next(c for c in cases if c['group'] == block['group'] and c['slot'] == slot)
            raw = tokenizer.decode(event['generated_ids'], skip_special_tokens=False)
            text = raw.removesuffix('<|im_end|>').strip()
            error, calls = None, None
            try:
                calls = decode(text)['calls']
            except Exception as exc:
                error = str(exc)
            target = case['invocation']
            if not target['expected_calls'] and text == target['assistant_target']:
                calls, error = [], None
            correct = (calls == target['expected_calls']) if target['expected_calls'] else text == target['assistant_target']
            rows.append(dict(screen_id=case['screen_id'], scenario=target['scenario_family'], mode=block['mode'],
                block=block['index'], trial=event['trial'], warm=event['trial']>0, raw_output=raw,
                parsed_calls=calls, parse_error=error, expected_calls=target['expected_calls'], correct=correct,
                token_exact=event['generated_ids']==case['expected_ids'], output_tokens=len(event['generated_ids']),
                input_tokens=event.get('prompt_tokens', event.get('prompt_positions')),
                decode_tokens_per_second=(len(event['generated_ids'])-1)*1000/event['decode_ms'] if event['decode_ms'] else None,
                total_ms=event.get('request_ms', event.get('total_ms')),
                first_token_ms=event.get('request_first_token_ms', event.get('first_token_ms')),
                audio_ms=event['audio_ms'], prefill_ms=event['prefill_ms'], decode_ms=event['decode_ms'],
                handoff_ms=event.get('handoff_ms'), peak_rss_kib=event.get('peak_host_rss_kib', event.get('peak_rss_kib')),
                state_roundtrip_exact=event.get('state_roundtrip_exact'), native=event))
    modes = ['cpu', 'llama-burst', 'hybrid-burst']
    by_mode = {}
    for mode in modes:
        all_rows = [r for r in rows if r['mode']==mode]
        warm = [r for r in all_rows if r['warm']]
        if not warm:
            continue
        by_mode[mode] = dict(trials=len(all_rows), correct=sum(r['correct'] for r in all_rows),
            warm_trials=len(warm), warm_correct=sum(r['correct'] for r in warm),
            median_ms={key:median(warm,key) for key in ['total_ms','first_token_ms','audio_ms','prefill_ms','decode_ms']},
            median_decode_tokens_per_second=median(warm,'decode_tokens_per_second'),
            minimum_warm_total_ms=min(r['total_ms'] for r in warm), maximum_warm_total_ms=max(r['total_ms'] for r in warm),
            median_handoff_ms=median(warm,'handoff_ms') if mode!='cpu' else None,
            peak_rss_gib=max(r['peak_rss_kib'] for r in all_rows)/1024**2)
    comparisons = []
    per_case = []
    for case in cases:
        entries = {}
        for mode in modes:
            subset = [r for r in rows if r['screen_id']==case['screen_id'] and r['mode']==mode]
            warm = [r for r in subset if r['warm']]
            if warm:
                entries[mode] = dict(correct=sum(r['correct'] for r in subset),trials=len(subset),
                    total_ms=median(warm,'total_ms'),first_token_ms=median(warm,'first_token_ms'),
                    output_tokens=sorted(set(r['output_tokens'] for r in subset)),outputs=list(dict.fromkeys(r['raw_output'] for r in subset)))
        per_case.append(dict(screen_id=case['screen_id'], scenario=case['invocation']['scenario_family'],
            spoken_text=case['invocation']['messages'][-1]['content'],expected_target=case['invocation']['assistant_target'], modes=entries))
        for control in ['cpu','llama-burst']:
            a = [r for r in rows if r['screen_id']==case['screen_id'] and r['mode']==control and r['warm']]
            b = [r for r in rows if r['screen_id']==case['screen_id'] and r['mode']=='hybrid-burst' and r['warm']]
            if len(a)==len(b)==2:
                equivalent = all(r['correct'] for r in a+b) and len({r['raw_output'] for r in a+b})==1
                comparisons.append(dict(screen_id=case['screen_id'],control=control,correct_equivalent=equivalent,
                    control_ms=median(a,'total_ms'),hybrid_ms=median(b,'total_ms'),
                    hybrid_change_percent=(median(b,'total_ms')/median(a,'total_ms')-1)*100))
    preservation = json.loads((DEST/'preservation.json').read_text()) if (DEST/'preservation.json').exists() else None
    limitations = ['Twelve clean synthetic evaluation utterances, two reserved voices; no real-worker/noisy-audio study.',
        'Three repeats per case/runtime; first repeat excluded from warm timings. Not sufficient for reliable p95.',
        'CPU baseline consumes original audio; accelerated paths use trailing silence to 4.35 seconds. No truncation.',
        'Matched accelerated-encoder control isolates language prefill with the same padded audio and cache settings.',
        'CPU context 4096/default cache; bridge context 256/float32 cache/FlashAttention disabled. Four threads, cores 4–7.',
        'Clarifications use correct supplied pending state; this is not a sequential app/harness conversation test.',
        'Timings exclude recording, initialization, host input preparation, separate transcription, retrieval, harness and UI.',
        'App was closed throughout. No tools executed, tickets created, inventory writes or app integration. Memory is host process RSS, not total device allocation.',
        'No controlled thermal experiment; mode order varied by group. Earlier quantization drift remains unresolved.']
    summary = dict(checkpoint=manifest['checkpoint'], completed=len(rows)==108,total_outputs=len(rows),
        by_mode=by_mode, per_case=per_case, comparisons=comparisons, initializations=initializations,
        preservation=preservation, limitations=limitations, rows=rows, app_changes=False, builds=False)
    summary['preparation_corrections'] = json.loads((DEST/'preparation-correction.json').read_text())
    summary['failed_setup_attempts'] = [p.name for p in (DEST/'runs').glob('failed-*.log')]
    summary['failed_attempt_policy'] = 'Both setup attempts stopped before any generated output; logs retained. All completed generations retained.'
    summary['all_case_outputs_identical_across_modes'] = all(len({o for v in c['modes'].values() for o in v['outputs']})==1 for c in per_case)
    summary['paired_correct_comparisons'] = {}
    for control in ['cpu','llama-burst']:
        paired = [c for c in comparisons if c['control']==control and c['correct_equivalent']]
        if paired:
            summary['paired_correct_comparisons'][control] = dict(cases=len(paired),hybrid_faster_cases=sum(c['hybrid_ms']<c['control_ms'] for c in paired),median_case_change_percent=statistics.median(c['hybrid_change_percent'] for c in paired))
    summary['full_hybrid_state_checks'] = dict(tested=sum(r['mode']=='hybrid-burst' for r in rows),passed=sum(r['mode']=='hybrid-burst' and r['state_roundtrip_exact'] is True for r in rows))
    summary['recommendation'] = 'Keep the existing CPU app runtime. The hybrid state handoff is functional on this screen, but these short warehouse requests show no reliable latency benefit and substantially higher host memory. Do not integrate it as the default on this evidence.'
    (DEST/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    esc=lambda value: html.escape(str(value))
    labels={'cpu':'CPU original audio','llama-burst':'NPU encoder → CPU prefill/decode','hybrid-burst':'NPU encoder/prefill → CPU decode'}
    page=['<!doctype html><meta charset="utf-8"><title>Warehouse hybrid runtime screen</title><style>body{font:15px system-ui;margin:32px;max-width:1500px;color:#243044}table{border-collapse:collapse;width:100%;margin:18px 0}td,th{border:1px solid #ccd4de;padding:9px;text-align:left}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#eef1f5;padding:12px}.yes{color:#13734a}.no{color:#a62f35}</style><h1>Warehouse hybrid runtime screen — 2026-10-19</h1><p>Existing warehouse-v2-continue-20261019a checkpoint. Raw model tool calls and arguments, before harness execution.</p>']
    page.append('<p><strong>'+esc(summary['recommendation'])+'</strong></p>')
    page.append('<p>108 generations: 12 requests × 3 runtimes × 3 repeats. All outputs matched across runtimes, including two shared failures: omitted replenishment quantity (W05) and proposed replenishment despite negation (W12). All 36 full-hybrid state checks passed. These are small-screen results, not population accuracy estimates.</p>')
    page.append('<table><tr><th>Runtime</th><th>Correct trials</th><th>Warm total median</th><th>Warm first token</th><th>Host peak memory</th></tr>')
    for mode,item in by_mode.items():
        page.append(f'<tr><td>{esc(labels[mode])}</td><td>{item["correct"]}/{item["trials"]}</td><td>{item["median_ms"]["total_ms"]:.1f} ms</td><td>{item["median_ms"]["first_token_ms"]:.1f} ms</td><td>{item["peak_rss_gib"]:.2f} GiB</td></tr>')
    page.append('</table><p>Overall latency mixes correct and incorrect outputs; use equivalent correct pairs to assess speed.</p><table><tr><th>Case / request</th>'+''.join('<th>'+esc(labels[m])+'</th>' for m in modes)+'</tr>')
    for case in per_case:
        page.append('<tr><td>'+esc(case['screen_id']+' '+case['spoken_text'])+'</td>')
        for mode in modes:
            item=case['modes'].get(mode)
            page.append('<td>'+ (f'{item["correct"]}/{item["trials"]} correct<br>{item["total_ms"]:.1f} ms' if item else 'pending')+'</td>')
        page.append('</tr>')
    page.append('</table><h2>Limits and preservation</h2><ul>'+''.join('<li>'+esc(v)+'</li>' for v in limitations)+'</ul><p>App preservation: '+esc(preservation and preservation['passed'])+'</p><h2>Raw outputs</h2>')
    page.append('<p>Setup correction: W08 CPU audio placeholder count changed from 19 to 20 to match the native frontend. Two failed setup attempts produced no generations; logs retained. All completed generations, including slow samples, are included. Full per-trial tokens, stage timings, decode speed and startup timings are in summary.json.</p>')
    for case in per_case:
        page.append('<h3>'+esc(case['screen_id']+' '+case['scenario'])+'</h3><p>Expected</p><pre>'+esc(case['expected_target'])+'</pre>')
        for mode,item in case['modes'].items():
            page.append('<p>'+esc(labels[mode])+'</p><pre>'+esc('\n'.join(item['outputs']))+'</pre>')
    (DEST/'review.html').write_text('\n'.join(page))
    print(json.dumps(dict(completed=summary['completed'],by_mode=by_mode,correct_equivalent_pairs=[c for c in comparisons if c['correct_equivalent']]),indent=2))


if __name__=='__main__':
    main()
