"""Read-only LD6004 decoder prototype, based on supplied September 2025 PDF.

No serial IO, firmware changes, physiological interpretation or device commands.
"""
import math
import struct
from functools import reduce
from operator import xor


def checksum(data):
    return reduce(xor, data, 0) ^ 255


def encode(kind, payload=b'', sequence=1):
    head = struct.pack('>BHHH', 1, sequence, len(payload), kind)
    return head + bytes([checksum(head)]) + (payload + bytes([checksum(payload)]) if payload else b'')


class Decoder:
    def __init__(self):
        self.buffer = bytearray()
        self.rejected = 0

    def feed(self, chunk):
        self.buffer.extend(chunk)
        result = []
        while self.buffer:
            if self.buffer[0] != 1:
                del self.buffer[0]
                continue
            if len(self.buffer) < 8:
                break
            _, sequence, size, kind = struct.unpack('>BHHH', self.buffer[:7])
            total = 8 + size + bool(size)
            if total > 1024 or checksum(self.buffer[:7]) != self.buffer[7]:
                self.rejected += 1
                del self.buffer[0]
                continue
            if len(self.buffer) < total:
                break
            payload = bytes(self.buffer[8:8+size])
            if size and checksum(payload) != self.buffer[8+size]:
                self.rejected += 1
                del self.buffer[0]
                continue
            del self.buffer[:total]
            try:
                result.append(self.decode(kind, sequence, payload))
            except (ValueError, struct.error):
                self.rejected += 1
        return result

    @staticmethod
    def decode(kind, sequence, payload):
        result = {'protocol': 'LD6004-2025-09', 'type': f'0x{kind:04X}',
                  'sequence': sequence, 'raw_payload_hex': payload.hex()}
        if kind in (0x0A04, 0x0A08):
            if len(payload) < 4:
                raise ValueError('missing count')
            count, = struct.unpack_from('<i', payload)
            if count < 0 or len(payload) != 4 + count * 20:
                raise ValueError('invalid target count or length')
            targets = []
            for i in range(count):
                x, y, z, dop_idx, cluster_id = struct.unpack_from('<fffii', payload, 4+i*20)
                if not all(map(math.isfinite, (x, y, z))):
                    raise ValueError('nonfinite coordinate')
                targets.append(dict(x=x, y=y, z=z, unit='m', dop_idx=dop_idx, cluster_id=cluster_id))
            result.update(kind='targets' if kind == 0x0A04 else 'point_cloud', targets=targets)
        elif kind == 0x0A0A:
            if len(payload) != 16:
                raise ValueError('invalid occupancy payload')
            areas = struct.unpack('<IIII', payload)
            if any(v not in (0, 1) for v in areas):
                raise ValueError('unknown occupancy value')
            result.update(kind='area_occupancy', areas=list(areas))
        else:
            result['kind'] = 'unsupported_message'
        return result
