"""Computer gateway: LD6004 serial / standard BLE HR / diagnostic discovery.

No guessed Da Fit commands, firmware writes, radar configuration or diagnosis.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
from pathlib import Path
import queue
import sqlite3
import sys
import threading
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT/'runtime'/'python'))
from ld6004 import Decoder


def stamp():
    return datetime.now(timezone.utc).isoformat()


class Outbox:
    def __init__(self, path):
        self.path = str(path)
        with self.connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS pending (id TEXT PRIMARY KEY, data TEXT NOT NULL)')
        self.thread = None

    def connect(self):
        # Native SQLite contexts commit/rollback but do not close automatically.
        from contextlib import contextmanager
        @contextmanager
        def connection():
            db = sqlite3.connect(self.path, timeout=15)
            try:
                with db:
                    yield db
            finally:
                db.close()
        return connection()

    def add(self, sample):
        with self.connect() as db:
            db.execute('INSERT OR IGNORE INTO pending VALUES (?,?)', (sample['id'], json.dumps(sample, allow_nan=False)))

    def upload(self, base, token):
        with self.connect() as db:
            rows = db.execute('SELECT id,data FROM pending ORDER BY rowid LIMIT 50').fetchall()
        if not rows:
            return 0
        request = Request(base.rstrip('/')+'/v1/live/samples', data=json.dumps({'samples': [json.loads(r[1]) for r in rows]}).encode(),
                          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer '+token})
        with urlopen(request, timeout=8) as response:
            accepted = set(json.load(response)['accepted_ids'])
        with self.connect() as db:
            for key, _ in rows:
                if key in accepted:
                    db.execute('DELETE FROM pending WHERE id=?', (key,))
        return len(accepted)


def envelope(device_id, sensor, payload, sequence=None):
    return {'schema_version': 1, 'id': str(uuid.uuid4()), 'device_id': device_id,
            'sensor_type': sensor, 'source': 'live_device', 'quality': 'unknown',
            'time_basis': 'pc_receive', 'observed_at': stamp(), 'sequence': sequence, 'payload': payload}


def radar_samples(frames):
    for frame in frames:
        sensor = {'area_occupancy': 'radar_area', 'targets': 'radar_targets', 'point_cloud': 'radar_point_cloud'}.get(frame['kind'])
        if sensor:
            payload = {key: frame[key] for key in ('areas', 'targets') if key in frame}
            payload.update(protocol=frame['protocol'], protocol_type=frame['type'])
            yield envelope('ld6004-local', sensor, payload, frame['sequence'])


def decode_standard_hr(data):
    """Bluetooth Heart Rate Measurement flags + uint8/uint16; never proprietary."""
    if len(data) < 2:
        raise ValueError('short HR measurement')
    flags = data[0]
    if flags & 1:
        if len(data) < 3:
            raise ValueError('short uint16 HR')
        return int.from_bytes(data[1:3], 'little')
    return data[1]


def uploader(outbox, base, token, stop):
    failures = 0
    while not stop.is_set():
        try:
            count = outbox.upload(base, token)
            if count:
                print(f'Uploaded {count} real records.', flush=True)
            failures = 0
        except Exception:
            failures += 1
            print('Upload failed; records remain in local outbox. Check server, token and network.', flush=True)
        stop.wait(min(30, 2**min(failures, 5)))


def collect_radar(args, outbox, stop):
    import serial
    while not stop.is_set():
        decoder = Decoder()
        try:
            with serial.Serial(args.port, baudrate=args.baud, timeout=1) as connection:
                print('Serial opened. Waiting for validated LD6004 frames; no commands are sent.', flush=True)
                while not stop.is_set():
                    raw = connection.read(4096)
                    if raw:
                        for item in radar_samples(decoder.feed(raw)):
                            outbox.add(item)
        except serial.SerialException:
            print('Serial unavailable/disconnected; retrying. No substitute samples generated.', flush=True)
            stop.wait(3)


async def ble_diagnostics(args):
    from bleak import BleakScanner, BleakClient
    if args.command == 'ble-scan':
        devices = await BleakScanner.discover(timeout=8, return_adv=True)
        for device, adv in devices.values():
            print(json.dumps({'address': device.address, 'name': device.name, 'services': adv.service_uuids}, ensure_ascii=False))
        return
    device = await BleakScanner.find_device_by_address(args.address, timeout=15)
    if device is None:
        raise RuntimeError('Requested watch not found')
    async with BleakClient(device) as client:
        services = [{'uuid': s.uuid, 'characteristics': [{'uuid': c.uuid, 'properties': c.properties} for c in s.characteristics]} for s in client.services]
        if args.command == 'ble-inspect':
            print(json.dumps(services, ensure_ascii=False, indent=2))
            return
        characteristic = client.services.get_characteristic('00002a37-0000-1000-8000-00805f9b34fb')
        if characteristic is None or not any(p in characteristic.properties for p in ('notify', 'indicate')):
            raise RuntimeError('Standard 2A37 HR service unavailable. No proprietary Da Fit command was guessed.')
        def received(_, raw):
            try:
                value = decode_standard_hr(raw)
                args.outbox.add(envelope('watch-standard-hr', 'watch_hr', {'value': value, 'unit': 'bpm', 'protocol': 'Bluetooth-2A37'}))
            except ValueError:
                print('Invalid HR notification ignored.', flush=True)
        await client.start_notify(characteristic, received)
        print('Subscribed to standard Bluetooth HR; verify actual values before interpreting.', flush=True)
        while client.is_connected and not args.stop.is_set():
            await asyncio.sleep(.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('ports')
    sub.add_parser('ble-scan')
    inspect = sub.add_parser('ble-inspect')
    inspect.add_argument('--address', required=True)
    for name in ('radar', 'ble-hr'):
        p = sub.add_parser(name)
        p.add_argument('--api', default='http://127.0.0.1:8765')
        p.add_argument('--token-file', default=str(ROOT/'runtime'/'collector-token.txt'))
        if name == 'radar':
            p.add_argument('--port', required=True)
            p.add_argument('--baud', type=int, required=True, help='Confirm on your board; do not guess')
        else:
            p.add_argument('--address', required=True)
    args = parser.parse_args()
    if args.command == 'ports':
        from serial.tools.list_ports import comports
        for port in comports():
            print(json.dumps({'port': port.device, 'description': port.description}, ensure_ascii=False))
        return
    if args.command in ('ble-scan', 'ble-inspect'):
        asyncio.run(ble_diagnostics(args))
        return
    parsed = urlsplit(args.api)
    if parsed.scheme != 'https' and not (parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost', '::1')):
        parser.error('Upload needs HTTPS or loopback HTTP')
    token = Path(args.token_file).read_text(encoding='utf-8').strip()
    runtime = ROOT/'runtime'
    runtime.mkdir(exist_ok=True)
    outbox = Outbox(runtime/'gateway.sqlite3')
    stop = threading.Event()
    worker = threading.Thread(target=uploader, args=(outbox,args.api,token,stop), daemon=True)
    worker.start()
    try:
        if args.command == 'radar':
            collect_radar(args, outbox, stop)
        else:
            args.outbox, args.stop = outbox, stop
            asyncio.run(ble_diagnostics(args))
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        worker.join(timeout=10)


if __name__ == '__main__':
    main()
