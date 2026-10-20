"""One-shot worker profile. Legacy Harness remains available for frozen evidence."""
from copy import deepcopy
import re
from uuid import uuid4

from .engine import Harness, SCOPE_REPLIES
from .contracts import decode
from .inventory import lookup, public
from .grounding import check_fields, product_names
from .model_context import compact_state


def reporting_intent(text):
    text = text.lower().replace('’', "'").strip()
    return bool(re.match(r"^(?:(?:hey|please|okay)[, ]+)*(?:(?:i (?:want|need|would like) to|can you|could you) )?(?:report|log|file)\b", text))


class FloorHarness(Harness):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.report_text_owned = True
        self.replenishment_updates_stock = True
        self.mode = 'general'
        self.store.report_tool_name = 'create_report'
        self.read_candidates = {}
        self.report_offer = None

    def new_request(self):
        super().new_request()
        self.read_candidates = {}
        self.report_offer = None

    def set_mode(self, mode):
        if mode not in {'general', 'sop'}:
            raise ValueError('Unknown worker mode')
        if mode != self.mode:
            self.new_request()
            self.mode = mode

    def context(self, transcript=None):
        full = super().context(transcript)
        state = compact_state(full)
        focused = state.get('focused_call')
        if focused and focused['tool'] == 'report_general_concern':
            focused['tool'] = 'create_report'
            focused['arguments'] = {}
        return {**state, 'missing_fields': list(self.active.missing) if self.active else [],
                'model_history': []}

    def advise(self, text):
        cards = self.procedures.retrieve(text, None)
        safety = self.procedures.emergency(text)
        self.report_offer = {'id': uuid4().hex, 'text': text}
        return {'kind': 'guidance', 'message': '' if cards['cards'] or safety else
                'No applicable operating procedure was found on this device.',
                'procedures': cards, 'safety': safety, 'saved': False, 'tools': ['sop_retrieval'],
                'report_offer': {'id': self.report_offer['id']}}

    def choose_report(self, offer_id, accepted):
        offer = self.report_offer
        if self.mode != 'sop' or not offer or offer['id'] != offer_id:
            return {'kind': 'result', 'message': 'That report offer is no longer active.', 'saved': False, 'tools': []}
        self.report_offer = None
        if not accepted:
            return {'kind': 'result', 'message': 'Okay, no report created.', 'saved': False, 'tools': []}
        # Explicit worker approval of this exact guidance turn is trusted intent.
        # Preserve the original words; do not prepend invented report content.
        result = super().handle(offer['text'], {'schema_version': '1.0', 'action': 'propose',
            'calls': [{'tool': 'report_general_concern', 'arguments': {}}]})
        if result.get('preview'):
            result['preview']['tool'] = 'create_report'
        result['tools'] = ['create_report']
        return result

    def handle(self, transcript, model_output, **kwargs):
        self.read_candidates = {}
        self.report_offer = None
        control = isinstance(model_output, dict) and model_output.get('action') in {'confirm', 'cancel', 'show_more'}
        if self.mode == 'sop' and not control and not self.active and not reporting_intent(transcript):
            return self.advise(transcript)
        if self.mode == 'general' and isinstance(model_output, str) and model_output.strip() in SCOPE_REPLIES:
            return super().handle(transcript, model_output, **kwargs)
        if not control:
            try:
                envelope = decode(model_output)
                if envelope['action'] in {'confirm', 'cancel', 'show_more'}:
                    raise ValueError('Model output cannot authorize workflow controls')
                calls = envelope['calls']
                allowed = {'inventory_lookup', 'request_replenishment', 'task_lookup'} if self.mode == 'general' else {
                    'create_report', 'report_general_concern', 'report_product_damage'}
                if not calls or any(call['tool'] not in allowed for call in calls):
                    raise ValueError('Tool is not permitted in the selected mode')
                if len(calls) > 2:
                    raise ValueError('At most two named product lookups are supported')
                if len(calls) > 1 and any(c['tool'] != 'inventory_lookup' for c in calls):
                    raise ValueError('Only named product lookups can be combined')
                for call in calls:
                    if call['tool'] == 'inventory_lookup':
                        args = call['arguments']
                        if args.get('operation') not in {'count_stock', 'find_location'}:
                            raise ValueError('Use product-name stock or location lookup')
                        permitted = {'operation', 'product_name'} | ({'variant'} if len(calls) == 1 else set())
                        if set(args) - permitted or not args.get('product_name'):
                            raise ValueError('Two-product lookups use names only; single-product lookups may include variants')
                envelope = deepcopy(envelope)
                for call in envelope['calls']:
                    if self.mode == 'sop':
                        if not self.active and not reporting_intent(transcript):
                            raise ValueError('Reporting intent is required')
                        call['tool'], call['arguments'] = 'report_general_concern', {}
                model_output = envelope
            except ValueError as error:
                if self.active:
                    self.active.preview = None
                    self.preview_visible = False
                result = {'kind': 'failed', 'message': 'No valid action was found for this mode. Restate your request or use the form.',
                          'error': str(error), 'saved': False, 'tools': []}
                if self.mode == 'sop':
                    result.update(procedures=self.procedures.retrieve(transcript, None), safety=self.procedures.emergency(transcript))
                return result
        result = super().handle(transcript, model_output, **kwargs)
        # Public unified report name; persisted legacy report rows remain readable.
        result = deepcopy(result)
        if 'tools' in result:
            result['tools'] = list(dict.fromkeys('create_report' if t == 'report_general_concern' else t for t in result['tools']))
        if result.get('preview', {}).get('tool') == 'report_general_concern':
            result['preview']['tool'] = 'create_report'
        if result.get('preview', {}).get('tool') == 'request_replenishment':
            result['message'] = 'Review these details. Submit creates the ticket and adds this quantity to local demo stock. Correct or cancel to make no change.'
        if result.get('next_request'):
            result['next_request']['message'] = 'Another request is pending. Review it separately.'
        return result

    def _results(self, result):
        result = super()._results(result)
        rows = result.get('items', [])
        if 'matching_colors' in result:
            total = result['matched_variants']
            colors = ', '.join(result['matching_colors'])
            result['message'] = f"{total} matching variants."
            if colors:
                result['message'] += f" Colors: {colors}."
            if result.get('has_more'):
                result['message'] += f" Showing {len(rows)}; open Product catalog for all."
        self.read_candidates = {row['product_id']: deepcopy(row) for row in rows if 'product_id' in row}
        if len(self.read_candidates) > 1:
            result['choices'] = list(self.read_candidates.values())
        return result

    def select_read(self, product_id):
        if product_id not in self.read_candidates:
            raise ValueError('Selection is not among the displayed matches')
        row = self.read_candidates[product_id]
        self.read_candidates = {}
        return {'kind': 'result', 'items': [row], 'message': 'Selected catalog record.', 'saved': False, 'tools': ['inventory_lookup']}

    def _ask(self, request, missing, mode):
        if request.tool in {'inventory_lookup', 'task_lookup'}:
            return {'kind': 'result', 'items': [], 'message': 'No sufficiently specific match. Use the product catalog or enter a complete lookup.', 'saved': False}
        if request.questions >= 2:
            request.missing = missing
            return {'kind': 'paused', 'message': 'Complete the remaining fields in the form, or cancel.',
                    'missing': missing, 'known_arguments': deepcopy(request.arguments), 'saved': False, 'form_required': True}
        return super()._ask(request, missing, mode)

    def _merge(self, request, call, text, action):
        if request.tool == 'request_replenishment':
            call = deepcopy(call)
            args = call['arguments']
            old = request.arguments
            product_changed = ('product_name' in args and
                               product_names(self.store, args['product_name']) !=
                               product_names(self.store, old.get('product_name', '')))
            # Retain already-grounded slots carried from a pending request. Only
            # discard unsupported, newly filled variant slots; product and quantity
            # still pass through the normal strict validation below.
            known_variant = {} if product_changed else old.get('variant', {})
            variant = {key: value for key, value in args.get('variant', {}).items()
                       if key not in known_variant}
            evidence = ' '.join(request.turns + [text]) if not old else text
            focus = request.missing[0] if request.waiting and request.missing else None
            invalid = check_fields({'variant': variant}, evidence, self.store,
                                   tool=request.tool, clarification_field=focus)
            for field in invalid:
                args['variant'].pop(field.split('.')[1], None)
            if args.get('variant') == {}:
                args.pop('variant')
        super()._merge(request, call, text, action)

    def _process(self, request, call, text, action, mode):
        if request.tool == 'report_general_concern':
            evidence = ' '.join(request.turns + [text])
            remainder = re.sub(r"(?i)\b(i want to|i need to|please|report|log|file|a concern|an issue|a problem)\b", '', evidence)
            if not remainder.strip(' .,!?'):
                self._merge(request, call, text, action)
                return self._ask(request, ['observation'], mode)
        if request.tool == 'inventory_lookup':
            self._merge(request, call, text, action)
            args = request.arguments
            result = lookup(self.store, args)
            if result['kind'] == 'clarification':
                # Return bounded candidates rather than retaining a read conversation.
                rows = []
                if any(key in args for key in ('product_name', 'category', 'section', 'bin')):
                    names = product_names(self.store, args['product_name']) if args.get('product_name') else [None]
                    for name in names:
                        rows.extend(self.store.query(args, name))
                # Keep every match for truthful pagination, and interleave colors so
                # a bounded preview doesn't suggest the first color is the only one.
                groups = {}
                for row in rows:
                    groups.setdefault((row['name'], row['color']), []).append(row)
                ordered = []
                for index in range(max((len(group) for group in groups.values()), default=0)):
                    ordered.extend(group[index] for group in groups.values() if index < len(group))
                result = {'kind': 'result', 'items': [public(row) for row in ordered],
                          'message': 'No sufficiently specific match. Use Product catalog or enter a complete lookup.', 'saved': False}
                if rows:
                    result.update(matched_variants=len(rows),
                                  matching_colors=sorted({row['color'] for row in rows if row['color']}))
            return self._results(result)
        return super()._process(request, call, text, action, mode)

    def select_product(self, product_id):
        request = self.active
        if not request or request.tool != 'request_replenishment':
            raise ValueError('No replenishment request to complete')
        product = next((p for p in self.store.products if p['product_id'] == product_id), None)
        if product is None:
            raise ValueError('Unknown catalog product')
        return product

    def complete_form(self, product_id, quantity, event_id):
        if self.mode == 'sop':
            if not self.active or self.active.tool != 'report_general_concern' or not product_id.strip():
                raise ValueError('No report awaiting details')
            return self.handle(product_id, {'schema_version': '1.0', 'action': 'propose',
                'calls': [{'tool': 'create_report', 'arguments': {}}]}, input_mode='form', event_id=event_id)
        product = self.select_product(product_id)
        if type(quantity) is not int or quantity <= 0:
            raise ValueError('A positive whole additional quantity is required')
        variant = {key: product[key] for key in ('color', 'size') if product[key]}
        text = f"Request {quantity} {product['name']} " + ' '.join(f'{key} {value}' for key, value in variant.items()) + '.'
        return self.handle(text, {'schema_version': '1.0', 'action': 'propose', 'calls': [
            {'tool': 'request_replenishment', 'arguments': {'product_name': product['name'], 'variant': variant, 'quantity': quantity}}]},
            input_mode='form', event_id=event_id)
