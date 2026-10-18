import json
import os
from pathlib import Path
import struct
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from ld6004 import Decoder, encode
from server import Store, make_handler, now
from csv_source import read_csv, TEMPLATE


class CsvTests(unittest.TestCase):
    def test_template_and_missing_acc(self):
        result = read_csv(TEMPLATE, origin_kind='synthetic')
        self.assertEqual(result['errors'], [])
        self.assertEqual(result['sensor_counts'], {'watch_hr': 1,'watch_spo2':1, 'watch_acc': 0, 'radar_area': 1})
        self.assertEqual(result['acc_status'], 'unavailable')
        self.assertTrue(all(s['source'] == 'replay' for s in result['samples']))

    def test_mapped_columns_explicit_timezone_and_epoch(self):
        text = '\ufeff时间,心率,备注\n2026-10-15 01:00:00,81,测试\n'
        options = {'column_map': {'timestamp': '时间', 'heart_rate': '心率'}}
        bad = read_csv(text, **options)
        self.assertIn('时区', bad['errors'][0]['error'])
        result = read_csv(text, timezone_offset='+08:00', **options)
        self.assertEqual(result['samples'][0]['observed_at'], '2026-10-14T17:00:00+00:00')
        self.assertEqual(result['ignored_columns'], ['备注'])
        epoch = read_csv('timestamp,heart_rate\n0,80\n', timestamp_format='unix_ms')
        self.assertEqual(epoch['samples'][0]['observed_at'], '1970-01-01T00:00:00+00:00')

    def test_acc_optional_but_complete_finite_and_unit_required(self):
        head = 'timestamp,acc_x,acc_y,acc_z,acc_unit\n'
        for row in ('2026-10-15T00:00:00Z,0,,1,g', '2026-10-15T00:00:00Z,0,0,1,', '2026-10-15T00:00:00Z,NaN,0,1,g'):
            self.assertTrue(read_csv(head+row)['errors'])
        result = read_csv(head+'2026-10-15T00:00:00Z,0,0,1,g\n')
        self.assertEqual(result['sensor_counts']['watch_acc'], 1)
        self.assertEqual(result['samples'][0]['payload']['z'], 1)

    def test_duplicate_headers_and_ids_stable(self):
        with self.assertRaises(ValueError):
            read_csv('timestamp,heart_rate,heart_rate\n2026-10-15T00:00:00Z,80,81\n')
        first = read_csv(TEMPLATE)
        second = read_csv(TEMPLATE)
        self.assertEqual(first['dataset_id'], second['dataset_id'])
        self.assertEqual(first['samples'][0]['id'], second['samples'][0]['id'])


class ProtocolTests(unittest.TestCase):
    def test_official_empty_target_frame(self):
        raw = bytes.fromhex('01 00 06 00 04 0A 08 FE 00 00 00 00 FF')
        self.assertEqual(Decoder().feed(raw)[0]['targets'], [])

    def test_fragmentation_noise_and_concatenation(self):
        payload = struct.pack('<i', 1) + struct.pack('<fffii', .25, 1.5, .8, -3, 9)
        raw = encode(0x0A04, payload)
        decoder = Decoder()
        self.assertEqual(decoder.feed(b'noise'+raw[:5]), [])
        decoded = decoder.feed(raw[5:]+encode(0x0A0A, struct.pack('<IIII', 1, 0, 0, 0)))
        self.assertEqual(len(decoded), 2)
        self.assertEqual(decoded[0]['targets'][0]['dop_idx'], -3)
        self.assertEqual(decoded[1]['areas'], [1, 0, 0, 0])

    def test_bad_checksum_recovers_and_count_is_checked(self):
        raw = bytearray(encode(0x0A0A, struct.pack('<IIII', 1, 0, 0, 0)))
        raw[-1] ^= 1
        decoder = Decoder()
        decoded = decoder.feed(raw+encode(0x0A04, struct.pack('<i', 99))+encode(0x0A14, b'\x01'))
        self.assertEqual(len(decoded), 1)
        self.assertEqual(decoded[0]['kind'], 'unsupported_message')
        self.assertGreater(decoder.rejected, 0)


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name)/'test.sqlite3'
        self.store = Store(self.path)
        self.http = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(self.store, 'test-secret'))
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.http.server_port}'

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def call(self, route, body=None, auth=True, origin=None):
        headers = {'Authorization': 'Bearer test-secret'} if auth else {}
        if body is not None:
            headers['Content-Type'] = 'application/json'
        if origin:
            headers['Origin'] = origin
        request = Request(self.url+route, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        with urlopen(request, timeout=3) as response:
            if route == '/':
                return response.read().decode()
            return json.load(response)

    def test_auth_cross_origin_and_static(self):
        self.assertIn('夜间守护', self.call('/'))
        with self.assertRaises(HTTPError) as result:
            self.call('/v1/snapshot', auth=False)
        self.assertEqual(result.exception.code, 401)
        with self.assertRaises(HTTPError) as result:
            self.call('/v1/demo/tick', {}, origin='https://elsewhere.invalid')
        self.assertEqual(result.exception.code, 403)

    def test_data_persistence_and_duplicate_ingestion(self):
        self.call('/v1/demo/tick', {})
        snapshot = self.call('/v1/snapshot')
        self.assertEqual(len(snapshot['samples']), 2)
        result = self.call('/v1/samples', {'samples': snapshot['samples']})
        self.assertEqual(result['inserted'], 0)
        self.assertEqual(len(Store(self.path).snapshot()['samples']), 2)
        bad = dict(snapshot['samples'][0], source='live_device')
        with self.assertRaises(HTTPError):
            self.call('/v1/samples', {'samples': [bad]})

    def test_alert_levels_receipts_and_manual_labels(self):
        for level in (1, 2):
            event = self.call('/v1/events', {'level': level})
            self.call('/v1/receipts', {'event_id': event['id'], 'status': 'acknowledged'})
            self.call('/v1/receipts', {'event_id': event['id'], 'status': 'uncertain'})
        snapshot = self.call('/v1/snapshot')
        self.assertEqual(len(snapshot['events']), 2)
        self.assertEqual(len(snapshot['receipts']), 4)
        with self.assertRaises(HTTPError):
            self.call('/v1/events', {'level': 3})

    def test_observation_retry_and_assistant_honesty(self):
        record = {'id': 'ae4e76f8-a39e-40e8-b70b-692107589554', 'text': '演示翻身记录', 'occurred_at': now()}
        self.call('/v1/observations', record)
        self.call('/v1/observations', record)
        self.assertEqual(len(self.call('/v1/snapshot')['observations']), 1)
        result = self.call('/v1/assistant', {'question': '发生了什么', 'consent': False})
        self.assertEqual(result['mode'], 'local_summary')
        self.assertIn('非 AI', result['answer'])
        with patch.dict(os.environ, {'ASSISTANT_URL': '', 'ASSISTANT_KEY': '', 'ASSISTANT_MODEL': ''}):
            with self.assertRaises(HTTPError) as failure:
                self.call('/v1/assistant', {'question': '发生了什么', 'consent': True})
        self.assertEqual(failure.exception.code, 400)

    def test_csv_preview_atomic_import_dedup_and_no_live_alerts(self):
        body = {'csv_text': TEMPLATE, 'origin_kind': 'synthetic'}
        preview = self.call('/v1/csv/preview', body)
        self.assertEqual(preview['errors'], [])
        self.assertEqual(self.call('/v1/snapshot')['replay_samples'], [])
        imported = self.call('/v1/csv/import', body)
        self.assertEqual(imported['inserted'], 3)
        self.assertFalse(imported['live_alerts_triggered'])
        self.assertEqual(self.call('/v1/csv/import', body)['inserted'], 0)
        snapshot = self.call('/v1/snapshot')
        self.assertEqual(snapshot['events'], [])
        self.assertEqual(snapshot['samples'], [])
        self.assertEqual(len(snapshot['replay_samples']), 3)
        self.assertEqual(len(Store(self.path).snapshot()['imports']), 1)
        summary = self.store.summary('csv_latest')
        self.assertEqual(summary['source_counts']['replay'], 3)
        self.assertEqual(summary['window_from'], '2026-10-15T16:00:00+00:00')
        self.assertEqual(self.store.summary()['sample_count'], 0)
        result = self.call('/v1/assistant', {'scope': 'csv_latest', 'consent': False})
        self.assertIn('unavailable', result['answer'])

    def test_csv_errors_cause_no_partial_import(self):
        text = 'timestamp,heart_rate\n2026-10-15T00:00:00Z,80\nbad-date,81\n'
        with self.assertRaises(HTTPError) as failure:
            self.call('/v1/csv/import', {'csv_text': text})
        self.assertEqual(failure.exception.code, 422)
        snapshot = self.call('/v1/snapshot')
        self.assertEqual(snapshot['replay_samples'], [])
        self.assertEqual(snapshot['imports'], [])


if __name__ == '__main__':
    unittest.main()
