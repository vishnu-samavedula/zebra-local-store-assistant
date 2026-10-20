"""Parse Liquid-marked calls as data, with JSON-valued keyword arguments."""

import json
import re

from scripts.validate_tool_contracts import parse

START = "<|tool_call_start|>"
END = "<|tool_call_end|>"
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


class CallReader:
    def __init__(self, text):
        self.text = text
        self.cursor = 0

    def whitespace(self):
        while self.cursor < len(self.text) and self.text[self.cursor].isspace():
            self.cursor += 1

    def consume(self, token):
        self.whitespace()
        if not self.text.startswith(token, self.cursor):
            raise ValueError(f"Expected {token!r} in tool-call block")
        self.cursor += len(token)

    def next_is(self, token):
        self.whitespace()
        return self.text.startswith(token, self.cursor)

    def identifier(self):
        self.whitespace()
        match = IDENTIFIER.match(self.text, self.cursor)
        if match is None:
            raise ValueError("Expected a tool or argument name")
        self.cursor = match.end()
        return match.group()

    def value(self):
        self.whitespace()
        start = self.cursor
        try:
            _, end = json.JSONDecoder().raw_decode(self.text, start)
        except ValueError as error:
            raise ValueError("Tool argument must be a JSON literal") from error
        self.cursor = end
        # Reuse the strict JSON parser: duplicate nested keys and NaN are rejected.
        return parse(self.text[start:end])

    def call(self):
        name = self.identifier()
        self.consume("(")
        arguments = {}
        if not self.next_is(")"):
            while True:
                key = self.identifier()
                if key in arguments:
                    raise ValueError(f"Duplicate tool argument: {key}")
                self.consume("=")
                arguments[key] = self.value()
                if self.next_is(")"):
                    break
                self.consume(",")
        self.consume(")")
        return {"tool": name, "arguments": arguments}


def parse_marked_calls(raw):
    text = raw.strip()
    if not text.startswith(START) or not text.endswith(END):
        raise ValueError("Expected one complete Liquid tool-call block without surrounding prose")
    body = text[len(START):-len(END)]
    if START in body or END in body:
        raise ValueError("Multiple or nested tool-call blocks are not accepted")
    reader = CallReader(body)
    reader.consume("[")
    calls = []
    if not reader.next_is("]"):
        while True:
            calls.append(reader.call())
            if len(calls) > 3:
                raise ValueError("At most three tool calls are accepted")
            if reader.next_is("]"):
                break
            reader.consume(",")
    reader.consume("]")
    reader.whitespace()
    if reader.cursor != len(body):
        raise ValueError("Unexpected content after tool-call list")
    return {"schema_version": "1.0", "action": "propose", "calls": calls}


def render_marked_calls(calls):
    def render(call):
        arguments = ", ".join(
            f"{key}={json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':'))}"
            for key, value in call["arguments"].items()
        )
        return f"{call['tool']}({arguments})"
    return START + "[" + ", ".join(render(call) for call in calls) + "]" + END
