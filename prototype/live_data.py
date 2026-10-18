"""Engineering live-data validation. No seizure/respiration inference."""
from datetime import datetime, timezone
import math
import uuid


ALLOWED_DEVICES = {'ld6004-local': {'radar_area', 'radar_targets', 'radar_point_cloud'},
                   'watch-standard-hr': {'watch_hr','watch_spo2'}}


def validate_live(item, received_at=None):
    if not isinstance(item, dict) or item.get('schema_version') != 1 or item.get('source') != 'live_device':
        raise ValueError('live_device schema required')
    uuid.UUID(item['id'])
    sensor = item['sensor_type']
    if sensor not in ALLOWED_DEVICES.get(item['device_id'], set()):
        raise ValueError('unregistered device or sensor')
    stamp = datetime.fromisoformat(item['observed_at'])
    received = received_at or datetime.now(timezone.utc)
    if stamp.tzinfo is None or (stamp-received).total_seconds() > 5:
        raise ValueError('invalid timestamp or clock ahead by more than 5 seconds')
    if item.get('time_basis') != 'pc_receive' or item.get('quality') not in ('unknown', 'good', 'poor', 'invalid'):
        raise ValueError('explicit receive-time basis and quality required')
    payload = item['payload']
    if not isinstance(payload, dict):
        raise ValueError('payload must be object')
    if sensor == 'watch_hr':
        value = payload.get('value')
        if type(value) is not int or not 0 <= value <= 65535 or payload.get('unit') != 'bpm':
            raise ValueError('invalid standard heart-rate representation')
    elif sensor == 'watch_spo2':
        value=payload.get('value')
        if type(value) not in (int,float) or not math.isfinite(value) or not 0 < value <= 100 or payload.get('unit') != '%':
            raise ValueError('invalid blood oxygen representation')
    elif sensor == 'radar_area':
        areas = payload.get('areas')
        if not isinstance(areas, list) or len(areas) != 4 or any(type(x) is not int or x not in (0, 1) for x in areas):
            raise ValueError('invalid area occupancy')
    else:
        targets = payload.get('targets')
        if not isinstance(targets, list) or len(targets) > 50:
            raise ValueError('invalid targets')
        for target in targets:
            if target.get('unit') != 'm' or any(type(target.get(k)) not in (float, int) or not math.isfinite(target[k]) for k in ('x', 'y', 'z')):
                raise ValueError('invalid target coordinates')
            if any(type(target.get(k)) is not int for k in ('dop_idx', 'cluster_id')):
                raise ValueError('invalid target identifiers')
    clean = {key: item[key] for key in ('schema_version', 'id', 'device_id', 'sensor_type', 'source', 'quality', 'time_basis', 'payload')}
    clean['observed_at'] = stamp.astimezone(timezone.utc).isoformat()
    clean['server_received_at'] = received.isoformat()
    clean['sequence'] = item.get('sequence')
    return clean


def status_snapshot(records, at=None, stale_seconds=15):
    at = at or datetime.now(timezone.utc)
    status = []
    for device in ALLOWED_DEVICES:
        rows = [x for x in records if x.get('device_id') == device and x.get('source') == 'live_device']
        latest = max(rows, key=lambda x: x['observed_at']) if rows else None
        if not latest:
            status.append({'device_id': device, 'state': 'no_data', 'age_seconds': None, 'latest': None})
            continue
        age = max(0, (at-datetime.fromisoformat(latest['observed_at'])).total_seconds())
        state = 'stale' if age > stale_seconds else 'invalid' if latest['quality'] == 'invalid' else 'receiving_unvalidated'
        status.append({'device_id': device, 'state': state, 'age_seconds': round(age, 1), 'latest': latest})
    return {'devices': status, 'stale_seconds': stale_seconds, 'medical_detection': 'disabled_unvalidated',
            'message': '这些状态只表示数据链路，不表示患者安全或异常。'}
