"""Summarize a small paired quantization probe; never train or write tickets."""
import json
import math
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from warehouse_harness.contracts import decode
from warehouse_harness.engine import Harness
from warehouse_harness.procedures import Procedures
from warehouse_harness.store import Store

ROOT = Path(__file__).resolve().parents[1]


def owned(calls):
    calls = json.loads(json.dumps(calls))
    for call in calls:
        if call['tool'] == 'report_general_concern':
            call['arguments'] = {}
        elif call['tool'] == 'report_product_damage':
            call['arguments'] = {key: value for key, value in call['arguments'].items()
                                 if key in {'product_name', 'variant', 'quantity'}}
    return calls


def quantile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def summarize(path):
    report = json.loads(Path(path).read_text())
    for row in report['cases']:
        try:
            calls = decode(row['output'])['calls']
            row['exact_owned_call'] = owned(calls) == owned(row['expected_calls'])
            row['correct_tool'] = [c['tool'] for c in calls] == [c['tool'] for c in row['expected_calls']]
        except ValueError as error:
            row['exact_owned_call'] = row['correct_tool'] = False
            row['parse_error'] = str(error)
        store = Store(ROOT / 'seed_data/warehouse_catalog.jsonl')
        try:
            harness = Harness(store, Procedures(ROOT / 'seed_data/sop_cards.jsonl'), report_text_owned=True)
            # Non-audio source text is retained in the canonical fixture file.
            fixtures = json.loads((ROOT / 'models/warehouse-tests/warehouse-iteration-20261013g/device-inputs.json').read_text())['cases']
            text = next((f['messages'][-1]['content'] for f in fixtures if f['id'] == row['id'] and f['mode'] == 'text'), None)
            text = text or {'replenishment': 'Request ten Cloudweight Hoodies in ink, medium.',
                'concern': 'The scanner is flickering near receiving.',
                'damage': 'Five Cloudweight Hoodies in ink, medium. Fabric is torn at the hem around the corner from the break room.',
                'family-stock': 'How many Transit Wallets do we have?'}[row['id']]
            result = harness.handle(text, row['output'], input_mode=row['mode'], finish_reason=row.get('finish_reason', 'stop'))
            row['harness_kind'] = result['kind']
            row['harness_error'] = result.get('error')
            assert not store.tickets()
        finally:
            store.close()
    summary = {}
    for quant in ('Q8_0', 'Q4_K_M'):
        rows = [row for row in report['cases'] if row['quant'] == quant]
        group = {'requests': len(rows), 'exact_owned_calls': sum(r['exact_owned_call'] for r in rows),
                 'correct_tools': sum(r['correct_tool'] for r in rows)}
        for mode in ('all', 'text', 'audio'):
            selection = [r for r in rows if mode == 'all' or r['mode'] == mode]
            group[mode] = {'n': len(selection)}
            for key in ('elapsed_ms', 'first_text_ms'):
                values = [r[key] for r in selection if r.get(key) is not None]
                group[mode][key] = {'p50': quantile(values, .5), 'p95': quantile(values, .95),
                                   'mean': statistics.mean(values) if values else None}
            rates = [r['timings']['predicted_per_second'] for r in selection if r.get('timings', {}).get('predicted_per_second')]
            group[mode]['decode_tokens_per_second_median'] = statistics.median(rates) if rates else None
        memory = [int(r['memory']['VmHWM'].split()[0]) for r in rows if 'VmHWM' in r.get('memory', {})]
        group['peak_rss_kib'] = max(memory) if memory else None
        group['failures'] = [{'id': r['id'], 'mode': r['mode'], 'block': r['block'], 'output': r['output'],
                              'harness_error': r['harness_error']} for r in rows if not r['exact_owned_call']]
        summary[quant] = group
    destination = Path(path).with_name('comparison-scored.json')
    destination.write_text(json.dumps({'summary': summary, 'report': report}, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    summarize(sys.argv[1])
