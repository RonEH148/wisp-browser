"""Wisp test library: drives one Android device (emulator or real) through adb like a person.

Tests are plain functions registered with @step(...). Each step runs even if earlier ones
failed; a step ends with a screenshot and its screen layout, and its result goes in the report.
A step can only PASS, FAIL (a check didn't hold), ERROR (the test itself broke), CRASH (Wisp
crashed or froze), SKIP (not for this device) - plus notes that say what was seen.
"""
import json, os, re, subprocess, sys, time, traceback
import xml.etree.ElementTree as ET

PKG = 'com.sofabrowse.wisp'
PORT = 8000
# lvh.me and localtest.me: public names that point every subdomain at 127.0.0.1, so the
# device reaches the test server (adb reverse) under real-looking, separate sites
SITE = 'http://lvh.me:%d' % PORT
SITE2 = 'http://localtest.me:%d' % PORT
LOCAL = 'http://localhost:%d' % PORT


class Ctx:
    adb = 'adb'
    serial = None
    out = 'results'
    api = 0
    tablet = False
    width = 1080
    height = 2400
    real_sites = True
    only = None      # set of suite names
    skip = set()
    started = time.time()

C = Ctx()


# ---------------- adb ----------------
def adb(*args, timeout=60, binary=False):
    cmd = [C.adb] + (['-s', C.serial] if C.serial else []) + list(args)
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode('utf-8', 'replace')
    except subprocess.TimeoutExpired:
        return b'' if binary else ''

def sh(cmd, timeout=60):
    return adb('shell', cmd, timeout=timeout)

def sleep(s):
    time.sleep(s)

def key(code, wait=0.6):
    sh('input keyevent %s' % code)
    sleep(wait)

def back(wait=0.8):
    key(4, wait)

def home():
    key(3, 1.5)

def enter():
    key(66, 1)

def tap_xy(x, y, wait=0.9):
    sh('input tap %d %d' % (x, y))
    sleep(wait)

def long_press_xy(x, y, ms=1300):
    sh('input swipe %d %d %d %d %d' % (x, y, x, y, ms))
    sleep(1.2)

def swipe_up(fraction=0.5, ms=350):
    x = C.width // 2
    sh('input swipe %d %d %d %d %d' % (x, int(C.height * 0.75), x, int(C.height * (0.75 - fraction)), ms))
    sleep(1)

def swipe_down(fraction=0.5, ms=350):
    x = C.width // 2
    sh('input swipe %d %d %d %d %d' % (x, int(C.height * 0.25), x, int(C.height * (0.25 + fraction)), ms))
    sleep(1)

def type_text(t):
    """Types with the device's input (no keyboard app needed). Plain characters only."""
    out = ''
    for ch in t:
        if ch == ' ':
            out += '%s'
        elif ch in '\\&|;<>()$`"\'*~?[]#{}!':
            out += '\\' + ch
        else:
            out += ch
    sh('input text ' + out)
    sleep(0.8)

def rotate(landscape):
    sh('settings put system accelerometer_rotation 0')
    sh('settings put system user_rotation %d' % (1 if landscape else 0))
    sleep(2.5)
    read_screen_size()

def read_screen_size():
    out = sh('wm size')
    m = re.findall(r'(\d+)x(\d+)', out)
    if m:
        w, h = map(int, m[-1])
        rot = sh('dumpsys input | grep -m1 -i "SurfaceOrientation"')
        if re.search(r'SurfaceOrientation: [13]', rot) and h > w:
            w, h = h, w
        C.width, C.height = w, h

def set_network(on):
    for s in ('wifi', 'data'):
        sh('svc %s %s' % (s, 'enable' if on else 'disable'))
    sleep(3)


# ---------------- screen layout ----------------
class Node:
    __slots__ = ('text', 'rid', 'desc', 'cls', 'pkg', 'checked', 'checkable', 'focused', 'selected',
                 'enabled', 'x1', 'y1', 'x2', 'y2')

    def __init__(self, a):
        self.text = a.get('text', '') or ''
        self.rid = a.get('resource-id', '') or ''
        self.desc = a.get('content-desc', '') or ''
        self.cls = a.get('class', '') or ''
        self.pkg = a.get('package', '') or ''
        self.checked = a.get('checked') == 'true'
        self.checkable = a.get('checkable') == 'true'
        self.focused = a.get('focused') == 'true'
        self.selected = a.get('selected') == 'true'
        self.enabled = a.get('enabled') != 'false'
        m = re.findall(r'-?\d+', a.get('bounds', '[0,0][0,0]'))
        self.x1, self.y1, self.x2, self.y2 = (list(map(int, m[:4])) + [0, 0, 0, 0])[:4]

    @property
    def center(self):
        return ((self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2)

    @property
    def label(self):
        return self.text or self.desc

    def visible(self):
        return self.x2 > self.x1 + 1 and self.y2 > self.y1 + 1

    def __repr__(self):
        return '<%s %r [%d,%d][%d,%d]>' % (self.rid.split('/')[-1] or self.cls.split('.')[-1],
                                           self.label[:40], self.x1, self.y1, self.x2, self.y2)

last_xml = ''

def dump():
    global last_xml
    for attempt in range(4):
        sh('rm -f /sdcard/wisp-ui.xml')
        r = sh('uiautomator dump /sdcard/wisp-ui.xml', timeout=45)
        x = adb('exec-out', 'cat /sdcard/wisp-ui.xml')
        i = x.find('<hierarchy')
        if i >= 0:
            try:
                root = ET.fromstring(x[i:])
                last_xml = x
                return [Node(n.attrib) for n in root.iter('node')]
            except ET.ParseError:
                pass
        sleep(1 + attempt)
    last_xml = ''
    return []

def find(nodes=None, text=None, rid=None, desc=None, exact=False, any_of=None, cls=None, pkg=None):
    """text: in the text or the description (case-insensitive part unless exact)."""
    nodes = dump() if nodes is None else nodes
    out = []
    for n in nodes:
        if not n.visible():
            continue
        if rid and not (n.rid.endswith(':id/' + rid) or n.rid == rid):
            continue
        if cls and cls not in n.cls:
            continue
        if pkg and n.pkg != pkg:
            continue
        if text is not None:
            t = text.lower()
            if exact:
                if n.text != text and n.desc != text:
                    continue
            elif t not in n.text.lower() and t not in n.desc.lower():
                continue
        if desc is not None and desc.lower() not in n.desc.lower():
            continue
        if any_of is not None:
            hay = (n.text + ' ' + n.desc).lower()
            if not any(a.lower() in hay for a in any_of):
                continue
        out.append(n)
    return out

def present(**kw):
    return bool(find(**kw))

def wait_for(timeout=12, gone=False, **kw):
    end = time.time() + timeout
    while True:
        r = find(**kw)
        if (not r) == gone:
            return True if gone else r[0]
        if time.time() > end:
            return None if not gone else False
        sleep(0.8)

def tap(timeout=8, index=0, **kw):
    n = wait_for(timeout=timeout, **kw)
    if not n:
        raise Check('not on screen: %s' % fmt(kw))
    if index:
        n = find(**kw)[index]
    tap_xy(*n.center)
    return n

def long_press(timeout=8, **kw):
    n = wait_for(timeout=timeout, **kw)
    if not n:
        raise Check('not on screen: %s' % fmt(kw))
    long_press_xy(*n.center)
    return n

def scroll_to(max_swipes=6, **kw):
    for _ in range(max_swipes + 1):
        n = find(**kw)
        if n:
            return n[0]
        swipe_up(0.4)
    return None

def fmt(kw):
    return ', '.join('%s=%r' % i for i in kw.items())

def texts(nodes=None):
    nodes = dump() if nodes is None else nodes
    return [n.label for n in nodes if n.label]


# ---------------- device state ----------------
def keyboard_shown():
    d = sh('dumpsys input_method')
    m = re.search(r'mInputShown=(true|false)', d) or re.search(r'isInputViewShown=(true|false)', d)
    return None if not m else m.group(1) == 'true'

def focus_window():
    d = sh('dumpsys window windows | grep -E "mCurrentFocus|mFocusedApp"')
    m = re.search(r'mCurrentFocus=\S*\{[^}]*\s(\S+)\}', d)
    return m.group(1) if m else d.strip()[:200]

def wisp_in_front():
    return PKG in focus_window()

def wisp_running():
    return sh('pidof %s' % PKG).strip() != ''

def clipboard():
    return sh('cmd clipboard get-primary-clip 2>/dev/null').strip()

def notifications():
    d = sh('dumpsys notification --noredact')
    return [l for l in d.splitlines() if 'pkg=' + PKG in l]

def files_in(*dirs):
    out = sh('find %s -type f 2>/dev/null' % ' '.join(dirs))
    return set(l.strip() for l in out.splitlines() if l.strip())

DOWNLOAD_DIRS = ('/sdcard/Download', '/sdcard/Pictures', '/sdcard/Movies', '/sdcard/Music', '/sdcard/Documents')

def mem_kb():
    m = re.search(r'TOTAL(?: PSS)?:?\s+(\d+)', sh('dumpsys meminfo %s' % PKG))
    return int(m.group(1)) if m else None


# ---------------- report ----------------
class Check(Exception):
    pass

class Skip(Exception):
    pass

def check(cond, msg):
    if not cond:
        raise Check(msg)

def skip(msg):
    raise Skip(msg)

def expect(cond, msg):
    """A soft check: noted as a problem, the step goes on, and ends as FAIL."""
    if not cond:
        R.soft.append(msg)
        note('PROBLEM: ' + msg)
    return cond

class R:
    results = []
    notes = []
    soft = []
    step_no = 0
    shot_no = 0
    suite = ''
    crash_log = ''
    shots = []

def note(msg):
    R.notes.append(str(msg))
    print('        ' + str(msg), flush=True)

def shot(name):
    R.shot_no += 1
    d = os.path.join(C.out, 'shots')
    os.makedirs(d, exist_ok=True)
    base = os.path.join(d, '%03d-%03d-%s' % (R.step_no, R.shot_no, re.sub(r'[^a-z0-9]+', '-', name.lower()).strip('-')[:50]))
    data = adb('exec-out', 'screencap -p', binary=True, timeout=30)
    path = base + '.png'
    with open(path, 'wb') as f:
        f.write(data)
    try:
        from PIL import Image
        im = Image.open(path).convert('RGB')
        im.thumbnail((640, 1400))
        im.save(base + '.jpg', quality=72)
        os.remove(path)
        path = base + '.jpg'
    except Exception:
        pass
    dump()
    if last_xml:
        with open(base + '.xml', 'w', encoding='utf-8') as f:
            f.write(last_xml)
    R.shots.append(os.path.relpath(path, C.out))
    return path

def new_crash():
    c = adb('logcat', '-b', 'crash', '-d')
    fresh = c[len(R.crash_log):] if c.startswith(R.crash_log) else c
    R.crash_log = c
    if PKG in fresh:
        return fresh
    a = adb('logcat', '-d', '-t', '400', '-s', 'ActivityManager:E', 'ActivityManager:W')
    m = re.search(r'ANR in %s.*' % re.escape(PKG), a)
    if m and m.group(0) not in R.crash_log:
        R.crash_log += m.group(0)
        return 'ANR: ' + m.group(0)
    return ''

STEPS = []

def step(name, suite=None, needs=None):
    """needs: 'network', 'real', 'tablet', 'phone', 'api>=N'"""
    def deco(fn):
        mod = suite or fn.__module__.split('.')[-1]
        STEPS.append((mod, name, fn, needs or []))
        return fn
    return deco

def _needs_ok(needs):
    for n in needs:
        if n == 'real' and not C.real_sites:
            return 'real websites switched off (--no-real-sites)'
        if n == 'tablet' and not C.tablet:
            return 'tablets only'
        if n == 'phone' and C.tablet:
            return 'phones only'
        m = re.match(r'api>=(\d+)', n)
        if m and C.api < int(m.group(1)):
            return 'needs Android API %s+' % m.group(1)
    return None

def run_steps(on_recover):
    for mod, name, fn, needs in STEPS:
        if C.only and mod not in C.only:
            continue
        if mod in C.skip:
            continue
        R.step_no += 1
        R.notes = []
        R.soft = []
        t0 = time.time()
        status, detail = 'PASS', ''
        why = _needs_ok(needs)
        if why:
            status, detail = 'SKIP', why
        else:
            try:
                fn()
                if R.soft:
                    status, detail = 'FAIL', '; '.join(R.soft)
            except Check as e:
                status, detail = 'FAIL', str(e)
            except Skip as e:
                status, detail = 'SKIP', str(e)
            except Exception as e:
                status, detail = 'ERROR', '%s: %s\n%s' % (type(e).__name__, e, traceback.format_exc()[-1500:])
        crash = new_crash() if status != 'SKIP' else ''
        if crash:
            status = 'CRASH'
            detail = (detail + '\n' if detail else '') + crash[-4000:]
        last_shot = None
        if status != 'SKIP':
            try:
                last_shot = shot('end')
            except Exception:
                pass
        R.results.append({'no': R.step_no, 'suite': mod, 'name': name, 'status': status, 'detail': detail,
                          'notes': list(R.notes), 'secs': round(time.time() - t0, 1),
                          'shots': [s for s in R.shots if os.path.basename(s).startswith('%03d-' % R.step_no)]})
        print('[%-5s] %03d %-10s %s (%.0fs) %s' % (status, R.step_no, mod, name, time.time() - t0,
                                                 detail.splitlines()[0][:160] if detail else ''), flush=True)
        if status != 'SKIP':
            try:
                on_recover()
            except Exception:
                traceback.print_exc()

def write_report(extra):
    out = C.out
    with open(os.path.join(out, 'summary.json'), 'w') as f:
        json.dump({'device': extra, 'results': R.results}, f, indent=1)
    counts = {}
    for r in R.results:
        counts[r['status']] = counts.get(r['status'], 0) + 1
    order = ['CRASH', 'ERROR', 'FAIL', 'PASS', 'SKIP']
    line = ' · '.join('%s %d' % (k, counts[k]) for k in order if k in counts)
    md = ['# Wisp test report', '', extra.get('title', ''), '', '**' + line + '**', '']
    bad = [r for r in R.results if r['status'] in ('CRASH', 'ERROR', 'FAIL')]
    if bad:
        md += ['## Problems', '']
        for r in bad:
            md.append('- **%03d %s / %s — %s**: %s' % (r['no'], r['suite'], r['name'], r['status'],
                                                     (r['detail'].splitlines() or [''])[0][:300]))
        md.append('')
    md += ['## All steps', '']
    for r in R.results:
        md.append('### %03d %s / %s — %s (%ss)' % (r['no'], r['suite'], r['name'], r['status'], r['secs']))
        if r['detail']:
            md += ['```', r['detail'][:4000], '```']
        md += ['- ' + n for n in r['notes']]
        md.append('')
    with open(os.path.join(out, 'report.md'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(md))
    # a page with every screenshot, for looking through on a phone
    colors = {'PASS': '#2e7d32', 'FAIL': '#c62828', 'ERROR': '#6a1b9a', 'CRASH': '#000', 'SKIP': '#777'}
    h = ['<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">',
         '<title>Wisp test report</title><style>body{font-family:sans-serif;margin:10px;background:#f4f4f4}'
         '.s{background:#fff;border-radius:8px;padding:8px;margin:8px 0}.b{color:#fff;border-radius:4px;padding:1px 6px;font-size:12px}'
         'img{max-width:46%;margin:2px;border:1px solid #ccc;vertical-align:top}pre{white-space:pre-wrap;font-size:11px;background:#eee}'
         'li{font-size:13px}</style>', '<h2>Wisp test report</h2><p>%s</p><p><b>%s</b></p>' % (extra.get('title', ''), line)]
    for r in R.results:
        h.append('<div class="s"><span class="b" style="background:%s">%s</span> <b>%03d %s / %s</b> (%ss)'
                 % (colors.get(r['status'], '#333'), r['status'], r['no'], r['suite'], esc(r['name']), r['secs']))
        if r['detail']:
            h.append('<pre>%s</pre>' % esc(r['detail'][:3000]))
        if r['notes']:
            h.append('<ul>%s</ul>' % ''.join('<li>%s</li>' % esc(n) for n in r['notes']))
        h.append(''.join('<a href="%s"><img loading="lazy" src="%s"></a>' % (s, s) for s in r['shots']))
        h.append('</div>')
    with open(os.path.join(out, 'index.html'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(h))
    return line

def esc(s):
    return str(s).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
