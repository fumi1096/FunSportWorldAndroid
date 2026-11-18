"""RSA helpers for the Android build (public-key operations only).

The protocol uses RSA for two purposes:

* encrypting the one-time AES key with the app's public key (PKCS#1 v1.5),
* recovering the signed MD5 digest sent by the server (raw public operation).

Both only need modular exponentiation, which Python's big integers handle
efficiently for 1024-bit keys, so pycryptodome is not required.
"""

import random

PUBLIC_MODULUS = int(
    "b9a6a953cec18d664d51c611f83b8c7724d4c357b15b547eba98f7d6e6fd6d9c8"
    "cb8b72ef1bfc0c6306e199ff4efb2dc252567983800c7762f2e176d6525a06f"
    "cef3ed5448627c75d9abcdb97c8f61198b43a1a55098237f35fb3674a9adf3d5"
    "c3ae1b65dd26713ba4ee98e09342dfb9a2a8c5d89a37a3562d900c86ce36899f",
    16,
)
PUBLIC_EXPONENT = 65537


class PublicKey:
    """Minimal public-key container compatible with the desktop call sites."""

    def __init__(self, modulus, exponent=PUBLIC_EXPONENT):
        self.n = int(modulus)
        self.e = int(exponent)


def encrypt_pkcs1_v15(data: bytes, modulus: int = PUBLIC_MODULUS,
                      exponent: int = PUBLIC_EXPONENT) -> bytes:
    """RSAES-PKCS1-v1_5 encryption with a random non-zero padding string."""
    data = bytes(data)
    size = (modulus.bit_length() + 7) // 8
    if len(data) > size - 11:
        raise ValueError("RSA plaintext too long")
    padding_length = size - len(data) - 3
    rng = random.SystemRandom()
    padding = bytes(rng.randrange(1, 256) for _ in range(padding_length))
    encoded = b"\x00\x02" + padding + b"\x00" + data
    number = int.from_bytes(encoded, "big")
    return pow(number, exponent, modulus).to_bytes(size, "big")


def recover_digest(signature: bytes, modulus: int = PUBLIC_MODULUS,
                   exponent: int = PUBLIC_EXPONENT) -> str:
    """Recover the server's 32-byte lowercase-hex MD5 digest from `s`."""
    signature = bytes(signature)
    size = (modulus.bit_length() + 7) // 8
    if len(signature) != size:
        raise ValueError("s length mismatch: {} != {}".format(len(signature), size))
    number = int.from_bytes(signature, "big")
    encoded = pow(number, exponent, modulus).to_bytes(size, "big")
    if encoded[0] != 0 or encoded[1] != 1:
        raise ValueError("s is not a PKCS#1 type-1 block")
    separator = encoded.find(b"\x00", 2)
    if separator < 10 or any(byte != 0xFF for byte in encoded[2:separator]):
        raise ValueError("PKCS#1 padding is invalid")
    digest = encoded[separator + 1:]
    if len(digest) != 32 or not all(
        48 <= byte <= 57 or 97 <= byte <= 102 for byte in digest
    ):
        raise ValueError("trailing bytes are not a 32-byte lowercase hex digest")
    return digest.decode()
