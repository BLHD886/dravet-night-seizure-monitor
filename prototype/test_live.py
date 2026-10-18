from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from gateway import Outbox, radar_samples, decode_standard_hr, envelope
from ld6004 import Decoder
from live_data import status_snapshot, validate_live
from server import Store, make_handler


class LiveTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.store=Store(Path(self.temp.name)/'server.sqlite3')
        self.http=ThreadingHTTPServer(('127.0.0.1',0),make_handler(self.store,'parent','collector',True))
        self.thread=threading.Thread(target=self.http.serve_forever,daemon=True);self.thread.start()
        self.base=f'http://127.0.0.1:{self.http.server_port}'

    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.thread.join();self.temp.cleanup()

    def request(self,path,token,body=None):
        headers={'Authorization':'Bearer '+token}
        if body is not None:headers['Content-Type']='application/json'
        with urlopen(Request(self.base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers),timeout=3) as response:return json.load(response)

    def test_full_frame_to_outbox_upload_phone_api(self):
        raw=bytes.fromhex('01 00 06 00 04 0A 08 FE 00 00 00 00 FF')
        sample=list(radar_samples(Decoder().feed(raw)))[0]
        self.assertEqual(sample['sensor_type'],'radar_point_cloud')
        self.assertEqual(sample['source'],'live_device')
        outbox=Outbox(Path(self.temp.name)/'gateway.sqlite3');outbox.add(sample)
        self.assertEqual(outbox.upload(self.base,'collector'),1)
        self.assertEqual(outbox.upload(self.base,'collector'),0)
        state=self.request('/v1/live/status','parent')
        radar=next(d for d in state['devices'] if d['device_id']=='ld6004-local')
        self.assertEqual(radar['state'],'receiving_unvalidated')
        self.assertEqual(radar['latest']['payload']['targets'],[])
        self.assertEqual(state['medical_detection'],'disabled_unvalidated')
        self.assertEqual(state['events'],[])
        self.assertEqual(self.store.summary('live_recent')['sample_count'],1)
        self.assertEqual(self.store.summary('demo_recent')['sample_count'],0)

    def test_auth_split_and_dedup_and_conflict(self):
        sample=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'})
        with self.assertRaises(HTTPError) as failure:self.request('/v1/live/samples','parent',{'samples':[sample]})
        self.assertEqual(failure.exception.code,401)
        self.assertEqual(self.request('/v1/live/samples','collector',{'samples':[sample]})['inserted'],1)
        self.assertEqual(self.request('/v1/live/samples','collector',{'samples':[sample]})['inserted'],0)
        sample['payload']['value']=81
        with self.assertRaises(HTTPError):self.request('/v1/live/samples','collector',{'samples':[sample]})
        with self.assertRaises(HTTPError):self.request('/v1/live/status','collector')

    def test_stale_and_never_received_are_not_normal(self):
        stamp=datetime.now(timezone.utc)
        sample=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'})
        sample['observed_at']=(stamp-timedelta(seconds=60)).isoformat()
        clean=validate_live(sample,stamp)
        statuses=status_snapshot([clean],stamp)['devices']
        self.assertEqual(next(d for d in statuses if d['device_id']=='watch-standard-hr')['state'],'stale')
        self.assertEqual(next(d for d in statuses if d['device_id']=='ld6004-local')['state'],'no_data')

    def test_wrong_source_unregistered_and_future_rejected(self):
        for key,value in [('source','synthetic'),('device_id','unregistered'),('observed_at',(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat())]:
            sample=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'});sample[key]=value
            with self.assertRaises(ValueError):validate_live(sample)

    def test_failed_upload_keeps_outbox(self):
        outbox=Outbox(Path(self.temp.name)/'gateway.sqlite3')
        outbox.add(envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'}))
        with self.assertRaises(HTTPError):outbox.upload(self.base,'wrong')
        self.assertEqual(outbox.upload(self.base,'collector'),1)

    def test_conflicting_ids_inside_batch_are_atomic(self):
        sample=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'})
        other=json.loads(json.dumps(sample));other['payload']['value']=81
        with self.assertRaises(HTTPError):self.request('/v1/live/samples','collector',{'samples':[sample,other]})
        self.assertEqual(self.store.summary('live_recent')['sample_count'],0)

    def test_out_of_order_upload_does_not_replace_latest(self):
        old=envelope('watch-standard-hr','watch_hr',{'value':70,'unit':'bpm'})
        old['observed_at']=(datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat()
        current=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'})
        self.request('/v1/live/samples','collector',{'samples':[current,old]})
        state=self.request('/v1/live/status','parent')
        latest=next(d for d in state['devices'] if d['device_id']=='watch-standard-hr')['latest']
        self.assertEqual(latest['payload']['value'],80)

    def test_history_separates_simulation_from_live(self):
        from simulate_devices import batch
        self.request('/v1/samples','parent',{'samples':batch(1)})
        real=envelope('watch-standard-hr','watch_hr',{'value':90,'unit':'bpm'})
        self.request('/v1/live/samples','collector',{'samples':[real]})
        self.assertEqual(len(self.request('/v1/history?source=synthetic','parent')['samples']),3)
        live=self.request('/v1/history?source=live_device','parent')['samples']
        self.assertEqual([x['id'] for x in live],[real['id']])
        with self.assertRaises(HTTPError):self.request('/v1/history?source=synthetic','collector')
        with self.assertRaises(HTTPError):self.request('/v1/history?source=unknown','parent')
        status=self.request('/v1/live/status','parent')
        self.assertEqual(next(d for d in status['devices'] if d['device_id']=='ld6004-local')['state'],'no_data')

    def test_history_radar_cannot_crowd_out_heart_rate(self):
        real=envelope('watch-standard-hr','watch_hr',{'value':80,'unit':'bpm'})
        self.store.put('sample',validate_live(real))
        for i in range(605):
            radar=envelope('ld6004-local','radar_area',{'areas':[1,0,0,0]})
            self.store.put('sample',validate_live(radar))
        history=self.request('/v1/history','parent')
        self.assertEqual(len(history['samples']),601)
        self.assertEqual(sum(x['sensor_type']=='watch_hr' for x in history['samples']),1)
        self.assertTrue(history['possibly_truncated'])

    def test_spo2_contract_and_history(self):
        good=envelope('watch-standard-hr','watch_spo2',{'value':98,'unit':'%'})
        self.request('/v1/live/samples','collector',{'samples':[good]})
        rows=self.request('/v1/history','parent')['samples'];self.assertEqual(rows[0]['sensor_type'],'watch_spo2')
        for value in (0,101,float('nan')):
            bad=envelope('watch-standard-hr','watch_spo2',{'value':value,'unit':'%'})
            with self.assertRaises(ValueError):validate_live(bad)

    def test_standard_hr_8bit_16bit_and_short_frames(self):
        self.assertEqual(decode_standard_hr(bytes([0,80])),80)
        self.assertEqual(decode_standard_hr(bytes([1,44,1])),300)
        for raw in (b'',b'\x00',b'\x01\x00'):
            with self.assertRaises(ValueError):decode_standard_hr(raw)

    def test_pyserial_loopback_transport_to_decoder(self):
        import serial
        raw=bytes.fromhex('01 00 06 00 04 0A 08 FE 00 00 00 00 FF')
        with serial.serial_for_url('loop://',timeout=1) as port:
            port.write(raw)
            frames=Decoder().feed(port.read(len(raw)))
        self.assertEqual(len(list(radar_samples(frames))),1)


if __name__=='__main__':unittest.main()
