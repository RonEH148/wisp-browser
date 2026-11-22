"""Shared by the suites."""
from wisp.lib import *    # noqa
from wisp.app import *    # noqa
from wisp import lib, app, server  # noqa

def goto(path, expect=None, site=None, timeout=25):
    return open_url((site or SITE) + path, expect=expect, timeout=timeout)

def server_hits(path_part, since=0):
    """How many requests for path_part the test site got (from log entry number since)."""
    return sum(1 for l in server.Handler.log[since:] if path_part in l)

def log_mark():
    return len(server.Handler.log)

def no_lumen_text():
    import re as _re
    bad = [t for t in texts() if 'lumen' in t.lower() or _re.search(r'\bTVs?\b|\bremote\b', t)]
    expect(not bad, 'TV/Lumen wording shown in Wisp: %s' % bad[:3])

def text_height(label):
    n = find(text=label)
    return (n[0].y2 - n[0].y1) if n else None

def status_bar_px():
    d = sh('dumpsys window | grep -m1 -E "statusBars|StatusBar.*frame|mStableInsets"')
    import re
    m = re.search(r'frame=\[0,0\]\[\d+,(\d+)\]', d)
    if m:
        return int(m.group(1))
    dens = re.findall(r'(\d+)', sh('wm density'))
    return int(24 * (int(dens[-1]) if dens else 420) / 160)

DOCS_PKGS = ('com.android.documentsui', 'com.google.android.documentsui')

def in_file_picker():
    w = focus_window()
    return any(p in w for p in DOCS_PKGS)

def picker_go_downloads():
    r = find(desc='Show roots')
    if r:
        tap_xy(*r[0].center)
        sleep(1.2)
        d = find(text='Downloads', exact=True) or find(text='Download', exact=True)
        if d:
            tap_xy(*d[0].center)
            sleep(1.5)
            return True
        back()
    return False

def picker_pick(name_part, timeout=10):
    """In Android's file picker: open Downloads and tap the file whose name has name_part."""
    end = time.time() + timeout
    while time.time() < end and not in_file_picker():
        sleep(1)
    check(in_file_picker(), 'the file picker did not open (in front: %s)' % focus_window())
    n = wait_for(timeout=3, text=name_part)
    if not n:
        picker_go_downloads()
        n = wait_for(timeout=4, text=name_part)
    if not n:
        s = find(desc='Search')
        if s:
            tap_xy(*s[0].center)
            type_text(name_part)
            enter()
            sleep(2)
            n = wait_for(timeout=4, text=name_part)
    shot('file picker')
    check(n, 'file "%s" not in the picker' % name_part)
    tap_xy(*n.center)
    sleep(2)
    press_button('Select', 'Open', 'OK', timeout=1)

def picker_save():
    end = time.time() + 10
    while time.time() < end and not in_file_picker():
        sleep(1)
    check(in_file_picker(), 'the save dialog did not open (in front: %s)' % focus_window())
    picker_go_downloads()
    shot('save dialog')
    b = press_button('Save', timeout=4)
    check(b, 'no Save button in the save dialog')
    sleep(2)
    press_button('OK', 'Replace', timeout=1)

def push_file(local_bytes, name):
    import tempfile
    p = os.path.join(tempfile.mkdtemp(), name)
    with open(p, 'wb') as f:
        f.write(local_bytes)
    adb('push', p, '/sdcard/Download/' + name, timeout=60)
    sh('am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/%s' % name)
    sh('content call --uri content://media --method scan_volume --arg external_primary 2>/dev/null')
    sleep(1)
