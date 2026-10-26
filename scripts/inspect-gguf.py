#!/usr/bin/env python3
"""Inspect GGUF metadata and tensor types without loading tensor data."""
import argparse
import collections
import hashlib
import json
import struct
from pathlib import Path

SCALARS = {0: 'B', 1: 'b', 2: 'H', 3: 'h', 4: 'I', 5: 'i',
           6: 'f', 7: '?', 10: 'Q', 11: 'q', 12: 'd'}


class Reader:
    def __init__(self, stream):
        self.stream = stream

    def read(self, size):
        if size < 0 or self.stream.tell() + size > 128 * 1024 * 1024:
            raise ValueError('Metadata exceeds inspection limit')
        data = self.stream.read(size)
        if len(data) != size:
            raise ValueError('Truncated GGUF metadata')
        return data

    def number(self, fmt):
        return struct.unpack('<' + fmt, self.read(struct.calcsize('<' + fmt)))[0]

    def string(self):
        return self.read(self.number('Q')).decode('utf-8')

    def value(self, kind, depth=0):
        if depth > 2:
            raise ValueError('Invalid nested GGUF array')
        if kind in SCALARS:
            return self.number(SCALARS[kind])
        if kind == 8:
            return self.string()
        if kind == 9:
            element, count = self.number('I'), self.number('Q')
            if count > 2_000_000:
                raise ValueError('Array exceeds inspection limit')
            return [self.value(element, depth + 1) for _ in range(count)]
        raise ValueError(f'Unknown GGUF metadata type {kind}')


def inspect(path):
    with path.open('rb') as stream:
        reader = Reader(stream)
        if reader.read(4) != b'GGUF':
            raise ValueError('Not a little-endian GGUF file')
        version = reader.number('I')
        if version not in (2, 3):
            raise ValueError(f'Unsupported GGUF version {version}')
        tensor_count, count = reader.number('Q'), reader.number('Q')
        if max(tensor_count, count) > 100_000:
            raise ValueError('Header count exceeds inspection limit')
        metadata = {}
        for _ in range(count):
            key = reader.string()
            if key in metadata:
                raise ValueError(f'Duplicate metadata key: {key}')
            metadata[key] = reader.value(reader.number('I'))
        types = collections.Counter()
        for _ in range(tensor_count):
            reader.string()
            dimensions = reader.number('I')
            if dimensions > 4:
                raise ValueError('Invalid tensor dimensions')
            for _ in range(dimensions):
                reader.number('Q')
            types[reader.number('I')] += 1
            reader.number('Q')
        architecture = metadata.get('general.architecture')
        summary = {key: value for key, value in metadata.items()
                   if key.startswith(('general.', str(architecture) + '.'))
                   and isinstance(value, (str, int, float, bool))}
        hashes = {}
        for key in ('tokenizer.chat_template', 'tokenizer.ggml.tokens',
                    'tokenizer.ggml.merges'):
            if key in metadata:
                value = metadata[key]
                encoded = (value if isinstance(value, str) else
                           json.dumps(value, ensure_ascii=False, separators=(',', ':'))).encode()
                hashes[key] = hashlib.sha256(encoded).hexdigest()
        special = {key: value for key, value in metadata.items()
                   if key.startswith('tokenizer.') and not isinstance(value, (list, str))}
        return dict(version=version, tensor_count=tensor_count,
                    tensor_type_counts=dict(sorted(types.items())), metadata=summary,
                    tokenizer_hashes=hashes, special_tokens=special,
                    descriptor_end=stream.tell(), tensor_data_verified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('file', type=Path)
    args = parser.parse_args()
    print(json.dumps(inspect(args.file), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
