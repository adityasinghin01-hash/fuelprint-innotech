#!/usr/bin/env python3
"""Read-only CPCB wind summary. Run: python3 -I tools/science/wind_drift.py [raw-directory].

Use consecutive 15-minute pairs with BOTH endpoints in the scenario wind band.
Prefer 20:00-06:00 IST when at least 100 such night pairs exist. Compute RMS
shortest-angle change; gaps are never bridged. For the calibration generator's
stationary, 1 Hz AR(1) direction offset with rho=.995, E[(d(t+900)-d(t))**2]
= 2*sigma**2*(1-rho**900). Thus sigma = RMS15/sqrt(2*(1-rho**900)); the per-second
innovation SD is sigma*sqrt(1-rho**2). Sigma is NOT the innovation SD.

Assumptions: station reports act as point samples, and filtered direction changes
represent a stationary AR(1) offset around a locally fixed mean. Rho is the
existing teaching correlation time, not measured by these 15-minute data. This
cannot establish sub-minute turbulence or resolve any averaging in station data;
sector filtering truncates large turns. No raw rows are written or served.

The supplied CPCB speeds span only 0..0.99 m/s, leaving no 2..5 m/s samples.
The optional --speed-proxy FILE explicitly uses the Open-Meteo hourly 10 m speed
for the containing UTC hour as a regional regime proxy. Direction still comes
from CPCB at 15-minute resolution. This does NOT establish that station wind
speed met the filter. Native CPCB coverage is always reported alongside it.
"""
import argparse
import csv
import gzip
import json
import math
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

DEFAULT_ROOT = Path('/Users/aditya/dev/fp-wt/data/data/real/cpcb/openaq-6951/raw')
IST = timezone(timedelta(hours=5, minutes=30))
START = datetime(2025, 10, 1, tzinfo=IST)
END = datetime(2026, 10, 1, tzinfo=IST)
RHO = 0.995
MIN_NIGHT_PAIRS = 100


def angular_delta(before, after):
    return (after - before + 180) % 360 - 180


def scenario(sample):
    return 240 <= sample['wind_direction'] <= 300 and 2 <= sample['wind_speed'] <= 5


def night(time):
    return time.hour >= 20 or time.hour < 6


def summarize(samples):
    times = sorted(samples)
    pairs = []
    for before, after in zip(times, times[1:]):
        if (after - before).total_seconds() != 900:
            continue
        if scenario(samples[before]) and scenario(samples[after]):
            pairs.append((before, after, angular_delta(samples[before]['wind_direction'],
                                                      samples[after]['wind_direction'])))
    nights = [p for p in pairs if night(p[0]) and night(p[1])]
    selected = nights if len(nights) >= MIN_NIGHT_PAIRS else pairs
    if len(selected) < 2:
        raise ValueError('Too few eligible 15-minute wind pairs')
    rms = math.sqrt(sum(p[2] ** 2 for p in selected) / len(selected))
    sigma = rms / math.sqrt(2 * (1 - RHO ** 900))
    return {
        'script': 'tools/science/wind_drift.py',
        'station': 'CPCB Murthal, Sonipat; OpenAQ location 6951',
        'data_period': '2025-10-01 through 2026-09-30 (IST)',
        'observed_first': times[0].isoformat(),
        'observed_last': times[-1].isoformat(),
        'filters': 'Both endpoints: wind_speed 2..5 m/s, wind_direction 240..300 deg; exactly 900 s apart',
        'hours_ist': '20:00..05:59' if selected is nights else 'all hours (fewer than 100 night pairs)',
        'minimum_night_pairs': MIN_NIGHT_PAIRS,
        'paired_wind_timestamps': len(times),
        'eligible_all_hour_pairs': len(pairs),
        'eligible_night_pairs': len(nights),
        'selected_pairs': len(selected),
        'direction_change_rms_15min_deg': rms,
        'generator_rho_per_second': RHO,
        'direction_stationary_sd_deg': sigma,
        'direction_innovation_sd_per_second_deg': sigma * math.sqrt(1 - RHO ** 2),
        'mapping': 'sigma = RMS15 / sqrt(2*(1-rho^900)); innovation_sd = sigma*sqrt(1-rho^2)',
        'assumptions': '15-minute reports treated as point samples of a stationary 1 Hz AR(1) offset; '
                       'rho=0.995 retained from teaching generator, not estimated; no sub-minute validation; '
                       'station averaging unresolved; sector filtering truncates large turns'
    }


def read_samples(root):
    samples = {}
    conflicts = set()
    files_read = 0
    for file in sorted(root.glob('year=202[56]*/**/*.csv.gz')):
        files_read += 1
        with gzip.open(file, 'rt', newline='', encoding='utf-8') as stream:
            for row in csv.DictReader(stream):
                parameter = row['parameter']
                if parameter not in ('wind_direction', 'wind_speed'):
                    continue
                time = datetime.fromisoformat(row['datetime']).astimezone(IST)
                if not START <= time < END:
                    continue
                value = float(row['value'])
                unit = 'deg' if parameter == 'wind_direction' else 'm/s'
                if row['units'] != unit:
                    raise ValueError('Unexpected wind units: ' + row['units'])
                if not math.isfinite(value) or value < 0 or (parameter == 'wind_direction' and value > 360):
                    continue
                values = samples.setdefault(time, {})
                if parameter in values and values[parameter] != value:
                    conflicts.add((time, parameter))
                values[parameter] = value
    for time, parameter in conflicts:
        samples[time].pop(parameter, None)
    complete = {time: values for time, values in samples.items() if len(values) == 2}
    return complete, files_read, len(conflicts)


def proxy_samples(samples, file):
    data = json.loads(file.read_text(encoding='utf-8'))
    if data['hourly_units']['wind_speed_10m'] != 'm/s' or data['utc_offset_seconds'] != 0:
        raise ValueError('Proxy must supply UTC hourly wind speed in m/s')
    hourly = {datetime.fromisoformat(t).replace(tzinfo=timezone.utc): speed
              for t, speed in zip(data['hourly']['time'], data['hourly']['wind_speed_10m'])
              if speed is not None and math.isfinite(speed) and speed >= 0}
    result = {}
    for time, sample in samples.items():
        hour = time.astimezone(timezone.utc).replace(minute=0, second=0, microsecond=0)
        if hour in hourly:
            result[time] = dict(sample, wind_speed=hourly[hour])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--speed-proxy', type=Path,
                        help='Explicit Open-Meteo UTC hourly m/s speed proxy; CPCB directions are retained')
    args = parser.parse_args()
    samples, files, conflicts = read_samples(args.root)
    speeds = [s['wind_speed'] for s in samples.values()]
    native = {
        'paired_wind_timestamps': len(samples),
        'speed_min_m_s': min(speeds) if speeds else None,
        'speed_max_m_s': max(speeds) if speeds else None,
        'scenario_timestamps': sum(scenario(s) for s in samples.values())
    }
    try:
        native['statistics'] = summarize(samples)
    except ValueError as error:
        native['status'] = str(error)
    if args.speed_proxy:
        result = summarize(proxy_samples(samples, args.speed_proxy))
        result['speed_source'] = 'Open-Meteo hourly 10 m wind speed, held over containing UTC hour; regional proxy'
        result['speed_proxy_file'] = args.speed_proxy.name
        result['speed_proxy_assumption'] = ('Regional hourly 10 m wind speed selects the regime; station speed '
                                            '2..5 m/s is NOT established; no sub-hour speed interpolation')
    else:
        result = {'script': 'tools/science/wind_drift.py', 'native_cpcb_only': True}
    result.update(native_cpcb=native, files_read=files,
                  conflicting_parameter_timestamps_excluded=conflicts)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
