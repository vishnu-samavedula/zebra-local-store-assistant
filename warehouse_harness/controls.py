"""Exact worker control phrases, shared by Android routing and final write checks."""
import re


def is_confirmation(text):
    return bool(re.fullmatch(
        r"\s*(?:yes|confirm|submit|create|save|go ahead|i confirm)"
        r"(?:[, ]+(?:i confirm|confirm|please|submit|create|save|that|it|the|this|report|request|ticket|replenishment|yes|go ahead))*[.!]?\s*",
        text.lower()))
