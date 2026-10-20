"""Compact-v1 generation context. Workflow authority stays in the full harness state."""
from copy import deepcopy
import json


def reduced_report_calls(calls):
    result = deepcopy(calls)
    for call in result:
        if call['tool'] == 'report_general_concern':
            call['arguments'] = {}
        elif call['tool'] == 'report_product_damage':
            call['arguments'] = {key: value for key, value in call['arguments'].items()
                                 if key in {'product_name', 'variant', 'quantity'}}
    return result


def compact_state(context):
    state = {'phase': context['phase']}
    focused = context.get('focused_call')
    if focused:
        focused = deepcopy(focused)
        focused['arguments'].pop('report_text', None)
        for name in ('observation', 'damage_description', 'location', 'on_behalf_of_coworker'):
            if focused['tool'] in {'report_general_concern', 'report_product_damage'}:
                focused['arguments'].pop(name, None)
        state['focused_call'] = focused
    if context.get('latest_question'):
        state['latest_question'] = context['latest_question']
    if context.get('verified_choices'):
        state['verified_choices'] = context['verified_choices']
    return state


def compact_messages(context):
    history = deepcopy(context['model_history'])
    for message in history:
        if message['role'] != 'tool':
            continue
        try:
            value = json.loads(message['content'])
        except ValueError:
            continue
        if isinstance(value, dict):
            if isinstance(value.get('known_arguments'), dict):
                value['known_arguments'].pop('report_text', None)
            if isinstance(value.get('preview'), dict):
                value['preview'].get('arguments', {}).pop('report_text', None)
            message['content'] = json.dumps(value, ensure_ascii=False, separators=(',', ':'))
    history.append({'role': 'tool', 'content': json.dumps({'harness_state': compact_state(context)},
                    ensure_ascii=False, separators=(',', ':'))})
    return history[-24:]
