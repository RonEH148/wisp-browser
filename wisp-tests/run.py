#!/usr/bin/env python3
"""Wisp user test - runs on GitHub Actions or on your own computer (Windows, Mac, Linux).

Needs: Python 3.8+, adb (Android platform-tools) and a device: an emulator or a phone/tablet
with USB debugging on. Pillow (pip install pillow) makes the screenshots small; optional.

  python run.py --apk Wisp-19.apk                 # the only device adb sees
  python run.py --apk Wisp-19.apk --serial emulator-5554
  python run.py --apk Wisp-19.apk --avd Pixel_6   # starts that emulator first
  python run.py --only core,tabs                  # some suites only (see --list)
  python run.py --no-install                      # test the Wisp already on the device

Results go to ./results (report.md, index.html with every screenshot, logs, video) and
results.zip next to it - attach that zip to the chat.

On a real device the test changes Wisp's settings, adds bookmarks, saved passwords and a test
VPN, and Quit deletes the logins of sites that aren't kept: run it on a real device only with
--allow-real-device, after a Wisp backup (Settings > Security & backup).
"""
import argparse, glob, importlib, os, shutil, subprocess, sys, threading, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from wisp import lib, app, server   # noqa: E402
from wisp.lib import C, R, sh, adb  # noqa: E402

SUITES = ['core', 'address', 'page', 'tabs', 'downloads', 'media', 'reader', 'privacy', 'logins',
          'adblock', 'sitesettings', 'settings', 'data', 'vpn', 'external', 'edge', 'looks', 'real', 'monkey']


def wait_boot(timeout=900):
    end = time.time() + timeout
    adb('wait-for-device', timeout=timeout)
    while time.time() < end:
        if sh('getprop sys.boot_completed').strip() == '1':
            return True
        time.sleep(3)
    return False


def start_avd(name):
    emu = shutil.which('emulator')
    if not emu:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ''
        for c in [os.path.join(sdk, 'emulator', 'emulator'), os.path.join(sdk, 'emulator', 'emulator.exe')]:
            if os.path.exists(c):
                emu = c
    if not emu:
        sys.exit('emulator not found: put the Android SDK emulator folder on PATH or set ANDROID_HOME')
    print('starting emulator %s ...' % name)
    subprocess.Popen([emu, '-avd', name, '-no-snapshot-save', '-no-boot-anim', '-noaudio'],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(10)
    if not wait_boot():
        sys.exit('the emulator did not finish starting')


def pick_device(serial):
    out = subprocess.run([C.adb, 'devices'], capture_output=True, text=True).stdout
    devs = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith('device')]
    if serial:
        if serial not in devs:
            sys.exit('device %s not found; adb sees: %s' % (serial, devs or 'nothing'))
        return serial
    if not devs:
        sys.exit('adb sees no device. Start an emulator, or connect the phone with USB debugging on.')
    if len(devs) > 1:
        sys.exit('adb sees several devices %s: choose one with --serial' % devs)
    return devs[0]


def setup_device():
    C.api = int((sh('getprop ro.build.version.sdk').strip() or '0'))
    lib.read_screen_size()
    dens = sh('wm density')
    import re
    m = re.findall(r'(\d+)', dens)
    density = int(m[-1]) if m else 420
    sw = min(C.width, C.height) * 160 // density
    C.tablet = sw >= 600
    for k in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        sh('settings put global %s 0' % k)
    sh('settings put global stay_on_while_plugged_in 7')
    sh('svc power stayon true')
    sh('input keyevent 224')
    sh('wm dismiss-keyguard')
    sh('settings put system accelerometer_rotation 0')
    sh('settings put system user_rotation 0')
    sh('settings put system font_scale 1.0')
    sh('cmd uimode night no')
    lib.set_network(True)
    return {'model': sh('getprop ro.product.model').strip(), 'android': sh('getprop ro.build.version.release').strip(),
            'api': C.api, 'screen': '%dx%d' % (C.width, C.height), 'density': density, 'sw_dp': sw,
            'tablet': C.tablet, 'emulator': is_emulator(),
            'webview': (sh('dumpsys webviewupdate | grep -i -m2 "webview package"') or '').strip()}


def is_emulator():
    return sh('getprop ro.kernel.qemu').strip() == '1' or sh('getprop ro.boot.qemu').strip() == '1' \
        or 'emulator' in (C.serial or '')


class Recorder(threading.Thread):
    """Screen video in 3-minute pieces (Android's limit), small and low-rate."""
    def __init__(self, out):
        super().__init__(daemon=True)
        self.out, self.stop, self.n = out, False, 0

    def run(self):
        size = '480x1040' if C.height >= C.width else '1040x480'
        while not self.stop and self.n < 40:
            self.n += 1
            sh('screenrecord --size %s --bit-rate 600000 --time-limit 170 /sdcard/wisp-rec%02d.mp4' % (size, self.n), timeout=200)

    def collect(self):
        self.stop = True
        sh('pkill -INT screenrecord')
        time.sleep(4)
        d = os.path.join(self.out, 'video')
        os.makedirs(d, exist_ok=True)
        for i in range(1, self.n + 1):
            adb('pull', '/sdcard/wisp-rec%02d.mp4' % i, os.path.join(d, 'rec%02d.mp4' % i), timeout=120)
            sh('rm -f /sdcard/wisp-rec%02d.mp4' % i)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--apk', help='Wisp apk to install (default: the newest Wisp-*.apk here)')
    ap.add_argument('--out', default='results')
    ap.add_argument('--serial')
    ap.add_argument('--avd', help='start this emulator first')
    ap.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    ap.add_argument('--only', help='comma-separated suites')
    ap.add_argument('--skip', help='comma-separated suites to leave out')
    ap.add_argument('--no-install', action='store_true')
    ap.add_argument('--keep-data', action='store_true', help="don't reset Wisp before the test (emulators are reset by default)")
    ap.add_argument('--no-real-sites', action='store_true', help='only the local test site (no YouTube etc.)')
    ap.add_argument('--no-video', action='store_true')
    ap.add_argument('--allow-real-device', action='store_true')
    ap.add_argument('--list', action='store_true')
    a = ap.parse_args()

    for s in SUITES:
        importlib.import_module('suites.' + s)
    if a.list:
        cur = None
        for mod, name, fn, needs in lib.STEPS:
            if mod != cur:
                print('\n' + mod)
                cur = mod
            print('   ' + name + ('  [%s]' % ','.join(needs) if needs else ''))
        print('\n%d steps' % len(lib.STEPS))
        return

    C.adb = a.adb
    if not shutil.which(C.adb) and not os.path.exists(C.adb):
        sys.exit('adb not found: install Android platform-tools and put it on PATH (or --adb <path>)')
    if a.avd:
        start_avd(a.avd)
    C.serial = pick_device(a.serial)
    C.out = os.path.abspath(a.out)
    if os.path.exists(C.out):
        shutil.rmtree(C.out)
    os.makedirs(C.out)
    C.real_sites = not a.no_real_sites
    C.only = set(a.only.split(',')) if a.only else None
    C.skip = set(a.skip.split(',')) if a.skip else set()
    if not wait_boot(120):
        sys.exit('the device is not ready')

    dev = setup_device()
    if not dev['emulator'] and not a.allow_real_device:
        sys.exit('%s is a real device. The test changes Wisp settings and data there; '
                 'make a Wisp backup, then run again with --allow-real-device.' % dev['model'])

    apk = a.apk
    if not a.no_install:
        if not apk:
            c = sorted(glob.glob('Wisp-*.apk') + glob.glob('*.apk'), key=os.path.getmtime)
            apk = c[-1] if c else None
        if not apk:
            sys.exit('no apk: give one with --apk, or --no-install')
        out = adb('install', '-r', '-g', apk, timeout=300)
        print(out.strip())
        if 'Success' not in out:
            sys.exit('install failed: ' + out)
    if dev['emulator'] and not a.keep_data:
        sh('pm clear %s' % lib.PKG)
    if C.api >= 33:
        sh('pm grant %s android.permission.POST_NOTIFICATIONS' % lib.PKG)
    ver = sh('dumpsys package %s | grep -m2 -E "versionName|versionCode"' % lib.PKG).strip().replace('\n', ' ')
    dev['wisp'] = ver
    title = '%s, Android %s (API %s), %s%s · %s' % (dev['model'], dev['android'], dev['api'], dev['screen'],
                                                    ' tablet' if dev['tablet'] else '', ver)
    print(title)

    srv, site_dir = server.start(lib.PORT)
    adb('reverse', 'tcp:%d' % lib.PORT, 'tcp:%d' % lib.PORT)

    adb('logcat', '-c')
    logf = open(os.path.join(C.out, 'logcat.txt'), 'w', encoding='utf-8', errors='replace')
    logp = subprocess.Popen([C.adb, '-s', C.serial, 'logcat', '-v', 'threadtime'], stdout=logf, stderr=subprocess.STDOUT)
    rec = None
    if not a.no_video:
        rec = Recorder(C.out)
        rec.start()

    R.crash_log = adb('logcat', '-b', 'crash', '-d')
    t0 = time.time()
    try:
        lib.run_steps(app.recover)
    except KeyboardInterrupt:
        print('stopped by you - writing the report for the steps done')
    dev['minutes'] = round((time.time() - t0) / 60, 1)
    dev['title'] = title

    if rec:
        rec.collect()
    logp.terminate()
    logf.close()
    with open(os.path.join(C.out, 'crashes.txt'), 'w', encoding='utf-8') as f:
        f.write(adb('logcat', '-b', 'crash', '-d'))
    with open(os.path.join(C.out, 'server-log.txt'), 'w') as f:
        f.write('\n'.join(server.Handler.log))
    # the full log is big: keep Wisp's own lines and the last 60000 lines
    p = os.path.join(C.out, 'logcat.txt')
    lines = open(p, encoding='utf-8', errors='replace').readlines()
    keep = ('com.sofabrowse', 'AndroidRuntime', 'chromium', 'cr_', 'WebView', 'Wisp', 'Sofa', 'Lumen',
            'ActivityManager', 'ActivityTaskManager', 'DEBUG', 'libc', 'System.err')
    with open(os.path.join(C.out, 'logcat-wisp.txt'), 'w', encoding='utf-8') as f:
        f.writelines(l for l in lines if any(k in l for k in keep))
    with open(p, 'w', encoding='utf-8') as f:
        f.writelines(lines[-60000:])
    line = lib.write_report(dev)
    print('\n' + line)

    z = C.out.rstrip('/\\') + '.zip'
    with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zf:
        for root, _, files in os.walk(C.out):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, os.path.dirname(C.out))
                if os.sep + 'video' + os.sep in full:
                    continue  # the video is separate (big)
                zf.write(full, rel)
    print('report: %s\nattach this: %s (%.1f MB)' % (os.path.join(C.out, 'index.html'), z, os.path.getsize(z) / 1e6))
    srv.shutdown()


if __name__ == '__main__':
    main()
