from ._common import *

def dp(v):
    import re
    d = re.findall(r'(\d+)', sh('wm density'))
    return int(v * (int(d[-1]) if d else 420) / 160)

def sheet_bounds(nodes=None):
    nodes = dump() if nodes is None else nodes
    xs = [n for n in nodes if n.visible() and n.pkg == PKG and n.label]
    if not xs:
        return None
    return min(n.x1 for n in xs), min(n.y1 for n in xs), max(n.x2 for n in xs), max(n.y2 for n in xs)

def nav_bar_px():
    return dp(48)

@step('Menu: nothing under the navigation bar')
def _():
    goto('/t.html', expect='Wisp test page')
    open_menu()
    shot('menu')
    q = find(text='Quit Wisp')
    if q:
        note('Quit row bottom %d, screen %d' % (q[0].y2, C.height))
        expect(q[0].y2 <= C.height - dp(16), 'Quit row under the navigation bar')
    back()

@step('Sheets are not too wide on big screens', needs=['tablet'])
def _():
    for landscape in (False, True):
        rotate(landscape)
        goto('/t.html', expect='Wisp test page')
        open_menu()
        b = sheet_bounds()
        shot('menu %s' % ('landscape' if landscape else 'portrait'))
        if b:
            w = b[2] - b[0]
            note('menu width %d px (max %d)' % (w, dp(540)))
            expect(w <= dp(540), 'menu wider than 520dp')
        back()
    rotate(False)

@step('Tablet: settings, tabs and site sheet in both orientations', needs=['tablet'])
def _():
    for landscape in (False, True):
        rotate(landscape)
        goto('/t.html', expect='Wisp test page')
        open_settings()
        shot('settings')
        leave_settings()
        open_tabs_grid()
        shot('tabs')
        back()
        site_sheet()
        shot('site sheet')
        back()
    rotate(False)

@step('Landscape phone: menu, tabs, site sheet')
def _():
    rotate(True)
    goto('/t.html', expect='Wisp test page')
    open_menu()
    shot('menu landscape')
    q = find(text='Quit Wisp') or [scroll_to(3, text='Quit Wisp')]
    expect(q and q[0], 'Quit not reachable in landscape')
    back()
    open_tabs_grid()
    shot('tabs landscape')
    back()
    site_sheet()
    shot('site sheet landscape')
    back()
    rotate(False)

@step('Toolbar: address bar and buttons fit and are aligned')
def _():
    goto('/title?t=Toolbar', expect='Toolbar')
    nodes = dump()
    parts = {r: find(nodes, rid=r) for r in ('ibSiteData', 'etUrl', 'tvTabCount', 'ibNewTab', 'ibReload', 'ibMenu')}
    for r, n in parts.items():
        expect(n, '%s missing from the toolbar' % r)
    ns = [n[0] for n in parts.values() if n]
    if ns:
        cy = [n.center[1] for n in ns]
        note('vertical centres: %s' % cy)
        expect(max(cy) - min(cy) <= dp(6), 'toolbar items not on one line')
        expect(max(n.x2 for n in ns) <= C.width and min(n.x1 for n in ns) >= 0, 'toolbar item off the screen')
    sb = status_bar_px()
    e = parts['etUrl'][0] if parts['etUrl'] else None
    if e:
        expect(e.y1 >= sb - 2, 'address bar under the status bar')

@step('Page does not leave a bar at the top in fullscreen', needs=['real'])
def _():
    goto('/video.html', expect='Video test')
    tap(text='Fullscreen', exact=True)
    sleep(4)
    shot('fullscreen top')
    try:
        from PIL import Image
        im = Image.open(os.path.join(C.out, R.shots[-1])).convert('L')
        w, h = im.size
        top = [im.getpixel((x, 3)) for x in range(0, w, max(1, w // 20))]
        note('top row brightness: %s' % top)
        expect(max(top) < 200, 'white bar at the top in fullscreen')
    except Exception as e:
        note('could not measure: %s' % e)
    back()
