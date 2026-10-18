"""Send explicitly synthetic HR/radar samples over the normal development API.

No BLE/serial hardware, live-device credentials, or alarm events are used.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
import uuid


def batch(index, at=None):
    stamp=(at or datetime.now(timezone.utc)).isoformat()
    return [dict(schema_version=1,id=str(uuid.uuid4()),device_id='sim-'+sensor,
                 sensor_type=sensor,source='synthetic',quality='unknown',observed_at=stamp,payload=payload)
            for sensor,payload in [('watch_hr',{'value':78+round(4*math.sin(index/9)+2*math.sin(index/3)),'unit':'bpm'}),
                                   ('watch_spo2',{'value':98-(index//12)%2,'unit':'%'}),
                                   ('radar_area',{'areas':[1,int(index%24>19),0,0],'protocol':'LD6004-simulation'})]]


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--api',default='http://127.0.0.1:8765')
    p.add_argument('--token-file',type=Path,default=Path(__file__).parent/'runtime/dev-token.txt')
    p.add_argument('--interval',type=float,default=5)
    p.add_argument('--count',type=int,default=120)
    p.add_argument('--seed-history',action='store_true',help='Seed 50 minutes of synthetic history first')
    args=p.parse_args()
    parsed=urlsplit(args.api)
    if parsed.scheme!='https' and not(parsed.scheme=='http' and parsed.hostname in ('localhost','127.0.0.1','::1')):
        p.error('Use loopback HTTP or HTTPS')
    if not 1<=args.interval<=60 or not 1<=args.count<=10000:p.error('invalid interval/count')
    token=args.token_file.read_text(encoding='utf-8').strip()
    def send(samples):
        request=Request(args.api.rstrip('/')+'/v1/samples',data=json.dumps({'samples':samples}).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
        with urlopen(request,timeout=8) as response:return json.load(response)
    if args.seed_history:
        now=datetime.now(timezone.utc)
        for start in (0,30,60,90):
            samples=[]
            for i in range(start,min(start+30,100)):samples.extend(batch(i,now-timedelta(seconds=(99-i)*30)))
            send(samples)
        print('Seeded synthetic history. Never inserted into live-device input.',flush=True)
    try:
        for i in range(args.count):
            send(batch(i));print(f'Synthetic sample pair {i+1}/{args.count}',flush=True)
            if i+1<args.count:time.sleep(args.interval)
    except KeyboardInterrupt:print('Synthetic generator stopped.')


if __name__=='__main__':main()
