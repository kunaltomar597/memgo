"""Phone number normalisation, hashing and encryption.

`phone_hash` is the lowercase hex SHA-256 of the E.164 string including the
`+`, unsalted, so the cloud can hash a STOP sender the same way
(docs/API_CONTRACT.md). It is a lookup key and is brute-forceable, which
docs/SECURITY.md states. `phone_enc` is AES-GCM with a key derived from the
shop key and is readable only on the hub.
"""

from __future__ import annotations

import hashlib
import re

from remu_hub.keys import INFO_PHONE, Sealer


class PhoneError(ValueError):
    pass


def normalise(raw: str) -> str:
    """Return E.164. Accepts 10-digit Indian mobiles and +91 / 91 / 0 prefixes."""
    s = raw.strip()
    plus = s.startswith("+")
    digits = re.sub(r"\D", "", s)
    if plus:
        if not 8 <= len(digits) <= 15:
            raise PhoneError("invalid phone number")
        if digits.startswith("91") and not _indian_mobile(digits[2:]):
            raise PhoneError("invalid Indian mobile number")
        return "+" + digits
    if len(digits) == 11 and digits.startswith("0"):
        digits = digits[1:]
    if len(digits) == 12 and digits.startswith("91"):
        digits = digits[2:]
    if len(digits) == 10 and _indian_mobile(digits):
        return "+91" + digits
    raise PhoneError("enter a 10-digit mobile number")


def _indian_mobile(ten: str) -> bool:
    return len(ten) == 10 and ten[0] in "6789"


def phone_hash(e164: str) -> str:
    return hashlib.sha256(e164.encode()).hexdigest()


def mask(e164: str) -> str:
    return e164[:3] + "*" * max(0, len(e164) - 7) + e164[-4:]


class PhoneVault:
    def __init__(self, shop_key: bytes) -> None:
        self._sealer = Sealer(shop_key, INFO_PHONE)

    def encrypt(self, e164: str) -> bytes:
        return self._sealer.seal(e164.encode(), b"phone")

    def decrypt(self, blob: bytes) -> str:
        return self._sealer.open(blob, b"phone").decode()
