"""CSV -> normalized replay records. No interpolation, medical labels or alerts.

column_map maps canonical field names to incoming CSV headers. Original times
remain observation times; importing a file does not make it a live stream.
"""
import csv
from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import math
import re
import uuid

FIELDS = ('timestamp', 'device_id', 'heart_rate', 'acc_x', 'acc_y', 'acc_z',
          'acc_unit', 'area0', 'area1', 'area2', 'area3', 'sequence','pulse_rate','spo2')
MAX_ROWS = 2000


def parse_time(value, timestamp_format='iso8601', timezone_offset=None):
    if timestamp_format in ('unix_s', 'unix_ms'):
        stamp = float(value)
        if not math.isfinite(stamp):
            raise ValueError('时间必须为有限数值')
        return datetime.fromtimestamp(stamp / (1000 if timestamp_format == 'unix_ms' else 1), timezone.utc)
    if timestamp_format != 'iso8601':
        raise ValueError('未知时间格式')
    stamp = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if stamp.tzinfo is None:
        if not isinstance(timezone_offset, str) or not re.fullmatch(r'[+-](?:0\d|1[0-3]):[0-5]\d|[+-]14:00', timezone_offset):
            raise ValueError('无时区时间需显式设置 timezone_offset，例如 +08:00')
        sign = 1 if timezone_offset[0] == '+' else -1
        hours, minutes = map(int, timezone_offset[1:].split(':'))
        stamp = stamp.replace(tzinfo=timezone(sign*timedelta(hours=hours, minutes=minutes)))
    return stamp.astimezone(timezone.utc)


def read_csv(text, column_map=None, timestamp_format='iso8601', timezone_offset=None, origin_kind='unspecified'):
    if not isinstance(text, str) or len(text.encode('utf-8')) > 1_000_000:
        raise ValueError('CSV 必须是 UTF-8 文本，最大 1MB')
    if origin_kind not in ('unspecified', 'synthetic', 'sensor_export', 'public_dataset'):
        raise ValueError('未知原始数据类别')
    if column_map is not None and (not isinstance(column_map, dict) or any(k not in FIELDS or not isinstance(v, str) for k,v in column_map.items())):
        raise ValueError('column_map 必须是标准字段到 CSV 列名的字典')
    mapping = {name: name for name in FIELDS}
    mapping.update(column_map or {})
    options = {'mapping': mapping, 'timestamp_format': timestamp_format, 'timezone_offset': timezone_offset, 'origin_kind': origin_kind}
    text = text.lstrip('\ufeff')
    digest = hashlib.sha256((json.dumps(options, sort_keys=True)+text).encode('utf-8')).hexdigest()
    dataset_id = 'csv-'+digest[:24]
    reader = csv.DictReader(io.StringIO(text), strict=True)
    headers = reader.fieldnames
    if not headers or len(set(headers)) != len(headers):
        raise ValueError('CSV 缺少表头或表头重复')
    if mapping['timestamp'] not in headers:
        raise ValueError('缺少 timestamp 列；请提供 column_map')
    samples, errors, timestamps = [], [], []
    row_count = 0
    for row_count, row in enumerate(reader, 1):
        if row_count > MAX_ROWS:
            raise ValueError('每次最多导入 2000 行；高频 ACC 请分块导入')
        def get(key):
            value = row.get(mapping[key])
            return value.strip() if isinstance(value, str) else ''
        try:
            if None in row or any(value is None for value in row.values()):
                raise ValueError('列数与表头不匹配')
            stamp = parse_time(get('timestamp'), timestamp_format, timezone_offset).isoformat()
            device_id = get('device_id') or 'csv-unassigned-device'
            if len(device_id) > 160:
                raise ValueError('device_id 过长')
            sequence = int(get('sequence')) if get('sequence') else None
            entries = []
            if get('pulse_rate') or get('heart_rate'):
                value = float(get('pulse_rate') or get('heart_rate'))
                if not math.isfinite(value):
                    raise ValueError('脉搏不是有限数值')
                entries.append(('watch_hr', {'value': value, 'unit': 'bpm'}))
            if get('spo2'):
                value=float(get('spo2'))
                if not math.isfinite(value) or not 0 < value <= 100:raise ValueError('血氧值无效')
                entries.append(('watch_spo2', {'value':value,'unit':'%'}))
            axes = [get('acc_'+axis) for axis in 'xyz']
            if any(axes):
                if not all(axes):
                    raise ValueError('ACC 必须同时提供 x/y/z，缺失不能补零')
                values = list(map(float, axes))
                if not all(map(math.isfinite, values)) or get('acc_unit') not in ('g', 'm/s2'):
                    raise ValueError('ACC 需有限数值和明确单位 g 或 m/s2')
                entries.append(('watch_acc', dict(zip(('x', 'y', 'z'), values), unit=get('acc_unit'))))
            areas = [get('area'+str(i)) for i in range(4)]
            if any(areas):
                if any(v not in ('0', '1') for v in areas):
                    raise ValueError('区域占用必须同时提供四个 0/1 值')
                entries.append(('radar_area', {'areas': list(map(int, areas)), 'protocol': 'LD6004-compatible-csv'}))
            if not entries:
                raise ValueError('该行没有可导入指标；可只提供脉搏，无需 ACC')
            for sensor, payload in entries:
                samples.append({'schema_version': 1, 'id': str(uuid.uuid5(uuid.NAMESPACE_URL, f'{dataset_id}:{row_count}:{sensor}')),
                                'device_id': device_id, 'sensor_type': sensor, 'source': 'replay',
                                'observed_at': stamp, 'quality': 'unknown', 'time_basis': 'csv_provided',
                                'dataset_id': dataset_id, 'origin_kind': origin_kind, 'row_number': row_count,
                                'sequence': sequence, 'payload': payload})
            timestamps.append(stamp)
        except (ValueError, TypeError, OverflowError, OSError) as error:
            errors.append({'row': row_count+1, 'error': str(error)})
    if not row_count:
        raise ValueError('CSV 没有数据行')
    counts = {name: sum(s['sensor_type'] == name for s in samples) for name in ('watch_hr','watch_spo2','watch_acc','radar_area')}
    return {'dataset_id': dataset_id, 'sha256': digest, 'rows': row_count, 'sample_count': len(samples),
            'sensor_counts': counts, 'acc_status': 'data_present_unvalidated' if counts['watch_acc'] else 'unavailable',
            'origin_kind': origin_kind, 'window_from': min(timestamps) if timestamps else None,
            'window_to': max(timestamps) if timestamps else None, 'errors': errors,
            'ignored_columns': [h for h in headers if h not in mapping.values()], 'samples': samples}


def template():
    out=io.StringIO(newline='')
    writer=csv.DictWriter(out,fieldnames=FIELDS)
    writer.writeheader()
    writer.writerow({'timestamp':'2026-10-16T00:00:00+08:00','device_id':'demo-watch','pulse_rate':80,'spo2':98})
    writer.writerow({'timestamp':'2026-10-16T00:00:04+08:00','device_id':'demo-radar','area0':1,'area1':0,'area2':0,'area3':0})
    return out.getvalue()

TEMPLATE = template()
