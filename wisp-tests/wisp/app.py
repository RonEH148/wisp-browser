"""Wisp-specific helpers: the address bar, menu, sheets, settings, tabs."""
import re
from .lib import *   # noqa
from . import lib

_launcher = None

def launcher():
    global _launcher
    if not _launcher:
        r = sh('cmd package resolve-activity --brief -c android.intent.category.LAUNCHER %s' % PKG)
        lines = [l.strip() for l in r.splitlines() if '/' in l]
        _launcher = lines[-1] if lines else PKG + '/com.phlox.tvwebbrowser.activity.main.MainActivity'
    return _launcher

def launch(wait=4):
    """Starts Wisp (or brings it to the front). Returns the start time in ms, if Android said."""
    out = sh('am start -W -n %s' % launcher(), timeout=60)
    sleep(wait)
    m = re.search(r'TotalTime: (\d+)', out)
    return int(m.group(1)) if m else None

def at_base():
    """The browser itself is in front: toolbar visible, no sheet or dialog over it."""
    return wisp_in_front() and present(rid='ibMenu')

def to_base(max_backs=5):
    if not wisp_in_front():
        launch()
    for _ in range(max_backs):
        if keyboard_shown():
            back()
            continue
        if present(rid='ibMenu') and present(rid='etUrl') and not editing():
            return True
        back()
    return present(rid='ibMenu')

def recover():
    """After every step: same starting point for the next one."""
    if C.width > C.height:
        rotate(False)
    if not wisp_running() or not wisp_in_front():
        launch()
    to_base()

# ---------- address bar ----------
def address():
    n = find(rid='etUrl')
    return n[0].text if n else None

def editing():
    n = find(rid='etUrl')
    return bool(n and n[0].focused)

def start_typing():
    tap(rid='etUrl')
    sleep(1.3)   # Wisp selects the whole address 0.5 s after the field opens

def clear_field():
    key(67, 0.4)   # the selected address goes
    a = address() or ''
    if a and a != hint_text():
        sh('input keyevent 123')
        if C.api >= 26:
            sh('input keyevent ' + ' '.join(['67'] * (len(a) + 2)))
        else:
            for _ in range(len(a) + 2):
                sh('input keyevent 67')
        sleep(0.4)

def hint_text():
    return 'Search or type address'

def type_address(text):
    start_typing()
    clear_field()
    type_text(text)

def open_url(url, expect=None, timeout=25):
    type_address(url)
    enter()
    if expect:
        n = wait_for(timeout=timeout, text=expect)
        check(n is not None, '"%s" never appeared after opening %s (address bar: %r)' % (expect, url, address()))
        return n
    sleep(5)

def page_has(text, timeout=10):
    return wait_for(timeout=timeout, text=text) is not None

def end_typing():
    if keyboard_shown():
        back()
    if editing():
        back()
    sleep(0.5)

# ---------- menu ----------
def open_menu():
    to_base(2)
    tap(rid='ibMenu')
    check(wait_for(timeout=6, text='Quit Wisp') or wait_for(timeout=2, text='Settings'), 'the menu did not open')

def menu_has(label):
    open_menu()
    n = find(text=label, exact=True) or find(text=label)
    if not n:
        m = find(text='More', exact=True)
        if m:
            tap_xy(*m[0].center)
            sleep(1)
            n = find(text=label)
    if not n:
        f = scroll_to(3, text=label)
        n = [f] if f else []
    return n[0] if n else None

def menu(label, wait=1.5):
    n = menu_has(label)
    check(n is not None, 'menu item "%s" not found' % label)
    check(n.enabled, 'menu item "%s" is greyed out' % label)
    tap_xy(*n.center)
    sleep(wait)

# ---------- site info sheet ----------
def site_sheet():
    to_base(2)
    tap(rid='ibSiteData')
    check(wait_for(timeout=6, any_of=['Block ads on this site', 'Delete all site data', 'Open a website']),
          'site info sheet did not open')

def switch_for(label, nodes=None):
    """The on/off switch in the row that has this label (None if the row isn't there)."""
    nodes = dump() if nodes is None else nodes
    rows = find(nodes, text=label)
    if not rows:
        return None, None
    r = rows[0]
    best = None
    for n in nodes:
        if n.checkable and n.visible():
            cy = n.center[1]
            if r.y1 - 90 <= cy <= r.y2 + 90:
                if best is None or abs(cy - r.center[1]) < abs(best.center[1] - r.center[1]):
                    best = n
    return r, best

def switch_state(label):
    r, s = switch_for(label)
    return None if s is None else s.checked

def set_switch(label, on, scroll=True):
    r, s = switch_for(label)
    if r is None and scroll:
        if scroll_to(5, text=label):
            r, s = switch_for(label)
    check(r is not None, 'row "%s" not found' % label)
    if s is not None and s.checked == on:
        return False
    tap_xy(*(s.center if s is not None else r.center))
    sleep(1)
    r2, s2 = switch_for(label)
    if s2 is not None:
        expect(s2.checked == on, 'switch "%s" did not change to %s' % (label, 'on' if on else 'off'))
    return True

# ---------- settings ----------
SETTINGS_PAGES = ['General', 'Privacy and security', 'Ad blocking', 'Site content', 'Downloads', 'VPN',
                  'Site settings', 'Security', 'Advanced', 'About Wisp']

def open_settings(page=None):
    menu('Settings')
    check(wait_for(timeout=6, text='General'), 'Settings did not open')
    if page:
        n = scroll_to(5, text=page)
        check(n is not None, 'settings page "%s" not in the list' % page)
        tap_xy(*n.center)
        sleep(1.5)

def leave_settings():
    for _ in range(6):
        if present(rid='ibMenu'):
            return
        back()

# ---------- tabs ----------
def tab_count():
    n = find(rid='tvTabCount')
    if not n:
        return None
    try:
        return int(re.sub(r'\D', '', n[0].text) or -1)
    except ValueError:
        return None

def new_tab():
    to_base(2)
    tap(rid='ibNewTab')
    sleep(1.5)

def open_tabs_grid():
    to_base(2)
    tap(rid='tvTabCount')
    check(wait_for(timeout=5, text='Tabs ('), 'the tab list did not open')

def grid_header_count():
    n = find(text='Tabs (')
    if not n:
        return None
    m = re.search(r'\((\d+)\)', n[0].label)
    return int(m.group(1)) if m else None

def close_all_tabs():
    open_tabs_grid()
    n = find(text='Close all tabs') or find(desc='Close all tabs')
    if n:
        tap_xy(*n[0].center)
        sleep(1)
        press_button('Close all', 'Close all tabs', 'OK', 'Yes', timeout=2)
    to_base()

# ---------- dialogs ----------
def press_button(*labels, timeout=4):
    """Taps the first button whose text is one of labels (case-insensitive)."""
    end = time.time() + timeout
    want = [l.lower() for l in labels]
    while time.time() < end:
        for n in dump():
            if n.visible() and n.label.strip().lower() in want and (n.cls.endswith('Button') or n.cls.endswith('TextView')):
                tap_xy(*n.center)
                return n.label
        sleep(0.7)
    return None

def system_dialog_ok():
    """Android's own permission/VPN dialogs."""
    return press_button('Allow', 'OK', 'While using the app', 'Only this time', 'Allow all the time', timeout=4)

def quit_wisp():
    menu('Quit Wisp', wait=2)
    press_button('Quit', 'Quit Wisp', 'OK', timeout=2)
    for _ in range(10):
        if not wisp_in_front():
            break
        sleep(1)
    sleep(2)
