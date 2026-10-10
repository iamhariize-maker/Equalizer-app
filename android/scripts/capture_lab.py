#!/usr/bin/env python3
"""Real Android capture Lab selection plus downstream host PCM; no OEM/player quality claim."""
import json
import math
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import subprocess
import sys
import time
from host_audio_level import capture_wav

serial, destination = sys.argv[1:3]
out = Path(destination)
out.mkdir(parents=True, exist_ok=True)
player = 'app.svan.testsource.capturable'
results = []


def adb(*args):
    return subprocess.check_output(['adb', '-s', serial, *args], timeout=100).decode(errors='replace')


def command(name, *args):
    adb('shell', 'am', 'start', '-W', '-n', 'app.svan/.Command', '--es', 'cmd', name, *args)


def report(kind):
    path = 'files/' + ('capture-report' if kind == 'capture' else 'lab-status') + '.json'
    adb('shell', 'run-as', 'app.svan', 'rm', '-f', path)
    command('capture_report' if kind == 'capture' else 'lab_status')
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        receipt = subprocess.run(['adb', '-s', serial, 'shell', 'run-as', 'app.svan', 'cat', path],
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=100)
        if receipt.returncode == 0:
            try:
                return json.loads(receipt.stdout)
            except json.JSONDecodeError:
                pass
        time.sleep(.1)
    raise AssertionError('No fresh receipt: ' + kind)


def wait(kind, predicate, name, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = report(kind)
        if predicate(value):
            (out / (name + '.json')).write_text(json.dumps(value, indent=2))
            return value
        time.sleep(.5)
    raise AssertionError('Timed out: ' + name + ' ' + json.dumps(value))


def check(condition, name):
    line = ('PASS ' if condition else 'FAIL ') + name
    results.append(line)
    (out / 'results.txt').write_text('\n'.join(results) + '\n')
    print(line, flush=True)
    assert condition, name


def captured(value):
    return value['capture'] and any(r['pkg'] == player and r['owner'] == 'ENGINE_B_MUTED'
                                  and r['uid'] in value['admitted'] for r in value['routes'])


def level(name):
    value = capture_wav(Path(os.environ['QEMU_WAV_PATH']), out / (name + '.float32le'))
    (out / (name + '.db')).write_text(str(value))
    return value


def screenshot(name):
    adb('shell', 'am', 'start', '-W', '-n', 'app.svan/.MainActivity')
    def tree():
        adb('shell', 'uiautomator', 'dump', '/sdcard/capture-lab.xml')
        return ET.fromstring(adb('exec-out', 'cat', '/sdcard/capture-lab.xml'))
    def tap(label):
        for _ in range(6):
            for node in tree().iter('node'):
                if label in (node.get('text'), node.get('content-desc')) and node.get('bounds') != '[0,0][0,0]':
                    x1,y1,x2,y2=map(int,re.findall(r'-?\d+',node.get('bounds')))
                    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
                    time.sleep(.7)
                    return
            time.sleep(.5)
        raise AssertionError('Native control missing: '+label)
    if any(n.get('text') == 'Got it' for n in tree().iter('node')):
        tap('Got it')
    tap('Lab')
    time.sleep(1)
    root=tree()
    (out/(name+'.xml')).write_text(ET.tostring(root,encoding='unicode'))
    (out/(name+'.png')).write_bytes(subprocess.check_output(
        ['adb','-s',serial,'exec-out','screencap','-p'],timeout=100))


def predicted(plan, hz):
    # Plan export deliberately includes the model's sampled response for independent host measurement.
    frequencies, curve = plan['frequenciesHz'], plan['predictedDb']
    for i in range(1, len(frequencies)):
        if frequencies[i] >= hz:
            t = math.log(hz / frequencies[i-1]) / math.log(frequencies[i] / frequencies[i-1])
            return curve[i-1] + (curve[i] - curve[i-1]) * t + plan['inputGainDb']
    raise AssertionError('Frequency outside exported prediction')


try:
    # Fix geometry before capture starts; screenshots must not reconfigure the
    # activity/display while downstream PCM and live epoch changes are measured.
    adb('shell', 'wm', 'size', '780x1688')
    adb('shell', 'wm', 'density', '320')
    for permission in ('DUMP', 'RECORD_AUDIO', 'POST_NOTIFICATIONS'):
        adb('shell', 'pm', 'grant', 'app.svan', 'android.permission.' + permission)
    command('stop_capture')
    time.sleep(3)
    command('lab_restore')
    command('reset_sound')
    command('svaramanas', '--ez', 'on', 'false')
    command('shared_output', '--ez', 'on', 'false')
    command('mix_fallback', '--ez', 'on', 'false')
    command('engine_mode', '--ez', 'system_only', 'true')
    command('app_engine', '--es', 'pkg', player, '--ez', 'system_only', 'false')
    command('forget_verdicts')
    command('start_system')
    adb('shell', 'am', 'start', '-W', '-n', player + '/app.svan.testsource.ToneActivity',
        '--ef', 'freq', '1000', '--ef', 'amp', '0.25', '--ez', 'broadcast', 'true', '--ez', 'component', 'true')
    wait('capture', lambda v: any(r['pkg'] == player and r['owner'] == 'ENGINE_A' for r in v['routes']), 'source-system')
    # Set the quality before fitting, so starting capture is not a settings edit that invalidates Lab.
    command('audio_settings', '--es', 'quality', 'AUDIOPHILE')
    command('eq_band', '--ef', 'frequency', '1000', '--ef', 'gain', '-6')
    command('lab_fit', '--ei', 'rate', '48000', '--ei', 'block', '8192', '--ez', 'hybrid', 'true')
    wait('lab', lambda v: v['ready'] and not v['busy'], 'fitted-before-capture', 180)
    command('lab_apply')
    wait('lab', lambda v: v['applied'] and not v['busy'], 'selected-before-capture')
    command('engine_mode', '--ez', 'system_only', 'false')
    adb('shell', 'appops', 'set', 'app.svan', 'PROJECT_MEDIA', 'allow')
    command('start_capture')
    time.sleep(2)
    initial = wait('capture', lambda v: captured(v) and v.get('labBlock') == 8192, 'capture-started-with-lab')
    check(initial['quality'] == 'AUDIOPHILE', 'preselected 8192 Lab survives engine-mode switch and capture startup at Audiophile quality')
    lab = report('lab')['plan']['capture']
    p = next(p for p in lab['plans'] if p['rate'] == 48000)
    wet = level('lab8192')
    screenshot('capture-lab8192')
    old_epoch = initial['epochId']
    command('lab_restore')
    normal = wait('capture', lambda v: captured(v) and v.get('labBlock') is None and v['epochId'] != old_epoch,
                  'live-restored-without-consent')
    command('lab_restore')
    time.sleep(1.5)
    repeated_restore = report('capture')
    (out / 'repeated-restore.json').write_text(json.dumps(repeated_restore, indent=2))
    check(normal['quality'] == 'AUDIOPHILE' and captured(repeated_restore) and
          repeated_restore['epochId'] == normal['epochId'],
          'live restore keeps capture ownership and quality; repeated restore does not rebuffer')
    ordinary = level('normal-native')
    check(abs(wet - ordinary - (predicted(p, 1000) + 6)) <= .85,
          'downstream host PCM matches Lab prediction with one static EQ and one operating margin')
    check(initial['latencyFrames'] - normal['latencyFrames'] == 8192,
          'capture reports the actual 8192-frame Lab delay')
    command('lab_apply')
    applied = wait('capture', lambda v: captured(v) and v.get('labBlock') == 8192 and v['epochId'] != normal['epochId'],
                   'live-applied-without-consent')
    check(applied['quality'] == 'AUDIOPHILE', 'live Lab apply keeps the same captured source and quality')
    command('eq_band', '--ef', 'frequency', '1000', '--ef', 'gain', '-3')
    wait('capture', lambda v: captured(v) and v.get('labBlock') is None, 'edit-restores-normal')
    check(abs(level('native-after-edit') - ordinary - 3) <= .75,
          'sound edit restores native EQ and measured expected level')
    command('svaramanas', '--ez', 'on', 'true', '--es', 'mode', 'SVARESA')
    time.sleep(4)
    before_fit = report('capture')
    command('lab_fit', '--ei', 'rate', '48000', '--ei', 'block', '4096', '--ez', 'hybrid', 'true')
    wait('lab', lambda v: v['ready'] and not v['busy'], 'fit-with-automatic-curve', 180)
    after_fit = report('capture')
    (out / 'normal-capture-after-fit.json').write_text(json.dumps(after_fit, indent=2))
    # Reproduce the inspection gap, covering two automatic periods before Apply.
    time.sleep(7)
    reviewed_fit = report('lab')
    reviewed_capture = report('capture')
    (out / 'reviewed-fit-before-apply.json').write_text(json.dumps(reviewed_fit, indent=2))
    command('lab_apply')
    active = wait('capture', lambda v: captured(v) and v.get('labBlock') == 4096, 'apply-with-automatic-curve')
    time.sleep(7)
    held = report('capture')
    check(reviewed_fit['ready'] and not reviewed_fit['busy'] and not reviewed_fit['applied'] and
          captured(reviewed_capture) and reviewed_capture['epochId'] == before_fit['epochId'] and
          captured(after_fit) and after_fit['epochId'] == before_fit['epochId'] and
          captured(held) and held['epochId'] == active['epochId'] and report('lab')['applied'],
          'normal fitting keeps its epoch; automatic curve is held through review and selected periodic updates')
    command('lab_restore')
    wait('capture', lambda v: captured(v) and v.get('labBlock') is None, 'final-restore')
finally:
    (out / 'logcat.txt').write_text(adb('logcat', '-d'))
    command('lab_restore')
    command('stop_capture')
    command('svaramanas', '--ez', 'on', 'false')
