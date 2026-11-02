"""Versioned host measurement contract; no device state mutations."""
import json
import re
import time

PATHS = ('native-launcher-stress', 'home-first-first-history')
PARSER_VERSION = 'outer-main-doframe-v2'
HOST_VERSION = 'home-first-a2'


def path_id(value):
    if value not in PATHS:
        raise SystemExit('explicit supported pathId is required')
    return value


def environment_gate(battery, thermal):
    reasons = []
    temperature = battery.get('temperature')
    if temperature is not None and temperature >= 420:
        reasons.append('battery temperature >= 420 tenths C')
    if thermal is not None and thermal >= 2:
        reasons.append('thermal status >= MODERATE(2)')
    return {'status': 'pause' if reasons else 'unknown' if temperature is None or thermal is None else 'observed-ok',
            'reasons': reasons, 'formalEnvironmentComplete': False}


def parse_battery(text):
    result = {}
    for key in ('level', 'temperature', 'status', 'voltage'):
        m = re.search(r'(?m)^\s*' + key + r':\s*(-?\d+)\s*$', text)
        result[key] = int(m.group(1)) if m else None
    result['plugging'] = {}
    for key in ('AC powered', 'USB powered', 'Wireless powered', 'Dock powered'):
        m = re.search(r'(?m)^\s*' + key + r':\s*(true|false)\s*$', text)
        result['plugging'][key] = m.group(1) == 'true' if m else None
    return result


def environment_snapshot(adb, serial, package, *, post=False, app_state=None):
    raw = {}
    def read(key, *cmd, select=None):
        try:
            r = adb(serial, 'shell', *cmd, check=False)
            text = r.stdout or ''
            if select: text = '\n'.join(line for line in text.splitlines() if select(line))
            raw[key] = {'stdout': text, 'stderr': r.stderr or '', 'returncode': r.returncode}
            return text if r.returncode == 0 else ''
        except Exception as error:
            raw[key] = {'error': str(error)}
            return ''
    battery = parse_battery(read('battery', 'dumpsys', 'battery'))
    thermal_raw = read('thermal', 'dumpsys', 'thermalservice')
    match = re.search(r'Thermal Status:\s*(\d+)', thermal_raw)
    thermal = int(match.group(1)) if match else (app_state or {}).get('thermalStatus')
    read('display', 'dumpsys', 'display')
    # Window dumps may contain unrelated app titles: retain only target and focus/display fields.
    focus = read('window', 'dumpsys', 'window', 'windows', select=lambda line:
                 'mCurrentFocus=' in line or 'mFocusedApp=' in line or package in line)
    # Redact OTHER focused component names; preserve evidence of target vs other.
    raw['window']['stdout'] = '\n'.join(line if package in line else re.sub(r'=.*', '=OTHER_OR_UNKNOWN', line)
                                       for line in focus.splitlines())
    for key in ('font_scale', 'window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        read(key, 'settings', 'get', 'system' if key == 'font_scale' else 'global', key)
    read('locale', 'getprop', 'persist.sys.locale')
    read('rotation', 'dumpsys', 'input', select=lambda line: 'SurfaceOrientation' in line or 'DisplayId' in line)
    read('loadavg', 'cat', '/proc/loadavg')
    read('cpuCounters', 'cat', '/proc/stat', select=lambda line: line.startswith(('cpu ', 'procs_running ', 'procs_blocked ')))
    # Target-only ART observable status, never dexopt/compile commands.
    read('packageProfile', 'dumpsys', 'package', package, select=lambda line:
         any(k in line for k in ('compiler-filter', 'status=', 'reason=', 'profile', 'versionCode', 'lastUpdateTime')))
    if post:
        read('memory', 'dumpsys', 'meminfo', package)
        read('artGc', 'dumpsys', 'art', package, select=lambda line:
             package in line or any(k in line.lower() for k in ('gc count', 'gc time', 'bytes allocated', 'gc freed')))
    memory = raw.get('memory', {}).get('stdout', '')
    pss = re.search(r'(?m)^\s*TOTAL PSS:\s*(\d+)', memory) or re.search(r'(?m)^\s*TOTAL\s+(\d+)\s+', memory)
    return {'capturedHostMonotonicNs': time.monotonic_ns(), 'phase': 'post' if post else 'pre',
            'battery': battery, 'thermalStatus': thermal, 'gate': environment_gate(battery, thermal),
            'targetForeground': (app_state or {}).get('focused') if app_state else next((package in line for line in focus.splitlines() if 'mCurrentFocus=' in line), None),
            'appState': app_state, 'deadlineStatus': 'unknown',
            'pssKb': int(pss.group(1)) if pss else None,
            'gc': {'status': 'unknown', 'rawAvailable': bool(raw.get('artGc', {}).get('stdout')), 'scope': 'target attribution requires platform support'}, 'raw': raw}
