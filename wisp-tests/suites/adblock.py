from ._common import *

def ad_results(timeout=20):
    end = time.time() + timeout
    r = []
    while time.time() < end:
        r = [t for t in texts() if t.startswith('AD ')]
        if r and not any('waiting' in x for x in r):
            break
        sleep(1.5)
    return r

@step('Ads are blocked by default')
def _():
    goto('/adtest.html', expect='Ad block test')
    r = ad_results()
    if any('LOADED' in x for x in r):
        note('first try: %s - filter lists may still be downloading, trying again in 40 s' % r)
        sleep(40)
        tap(rid='ibReload')
        r = ad_results()
    note('result: %s' % r)
    shot('adblock default')
    expect(r and all('BLOCKED' in x or 'TIMEOUT' in x for x in r), 'ads not blocked: %s' % r)

@step('Ad blocker off for this site (and its www.)')
def _():
    goto('/adtest.html', expect='Ad block test')
    site_sheet()
    set_switch('Block ads on this site', False)
    back()
    tap(rid='ibReload')
    r = ad_results()
    note('lvh.me with blocker off: %s' % r)
    expect(r and any('LOADED' in x for x in r), 'still blocked with the blocker off: %s' % r)
    goto('/adtest.html', expect='Ad block test', site='http://www.lvh.me:%d' % PORT)
    r2 = ad_results()
    note('www.lvh.me: %s' % r2)
    expect(r2 and any('LOADED' in x for x in r2), 'www.lvh.me still blocked (should follow lvh.me): %s' % r2)
    site_sheet()
    sw = switch_state('Block ads on this site')
    note('switch on www.lvh.me shows: %s' % sw)
    expect(sw is False, 'site sheet on www.lvh.me shows the blocker on')
    set_switch('Block ads on this site', True)
    back()
    goto('/adtest.html', expect='Ad block test')
    r3 = ad_results()
    note('lvh.me after switching back on: %s' % r3)
    expect(r3 and all('LOADED' not in x for x in r3), 'not blocked again: %s' % r3)

@step('Settings: ad blocking switch for all sites')
def _():
    open_settings('Ad blocking')
    shot('adblock page')
    set_switch('Block ads and trackers', False)
    leave_settings()
    goto('/adtest.html', expect='Ad block test')
    r = ad_results()
    note('all off: %s' % r)
    expect(r and any('LOADED' in x for x in r), 'still blocked with ad blocking off')
    open_settings('Ad blocking')
    set_switch('Block ads and trackers', True)
    leave_settings()

@step('Update filter lists now')
def _():
    open_settings('Ad blocking')
    n = scroll_to(5, text='Update filter lists now')
    check(n, 'no update button')
    tap_xy(*n.center)
    sleep(2)
    shot('updating')
    for _ in range(30):
        if not present(text='Updating'):
            break
        sleep(2)
    shot('after update')
    note('texts: %s' % [t for t in texts() if 'updated' in t.lower()][:2])
    leave_settings()

@step('Small animated ads are hidden, own pictures stay')
def _():
    goto('/smallads.html', expect='Small ads test')
    sleep(3)
    shot('small ads')
    expect(not present(text='moving ad'), 'the small animated ad is shown')
    expect(present(text='own small picture'), "the site's own small picture was hidden")

@step('Small ads switch off shows them')
def _():
    open_settings('Ad blocking')
    n = scroll_to(5, text='Hide small animated ads')
    check(n, 'no small-ads switch')
    set_switch('Hide small animated ads', False)
    leave_settings()
    goto('/smallads.html', expect='Small ads test')
    sleep(3)
    expect(present(text='moving ad'), 'ad still hidden with the switch off')
    open_settings('Ad blocking')
    scroll_to(5, text='Hide small animated ads')
    set_switch('Hide small animated ads', True)
    leave_settings()

@step('Hide element: stays hidden on the whole site, can be shown again')
def _():
    goto('/hide.html', expect='Hide test')
    long_press(text='HIDE ME BOX')
    h = wait_for(timeout=5, text='Hide element')
    shot('hide menu')
    check(h, 'no "Hide element" in the long-press menu')
    tap_xy(*h.center)
    sleep(2)
    expect(not present(text='HIDE ME BOX'), 'box still shown after hiding')
    tap(rid='ibReload')
    sleep(3)
    expect(not present(text='HIDE ME BOX'), 'box back after reload')
    goto('/hide.html', expect='Hide test', site='http://www.lvh.me:%d' % PORT)
    sleep(2)
    expect(not present(text='HIDE ME BOX'), 'box shown on www.lvh.me (should be hidden on the whole site)')
    goto('/hide.html', expect='Hide test')
    site_sheet()
    u = scroll_to(3, text='Show hidden elements')
    check(u, 'no "Show hidden elements" in the site sheet')
    tap_xy(*u.center)
    sleep(1)
    to_base()
    tap(rid='ibReload')
    sleep(3)
    expect(present(text='HIDE ME BOX'), 'box still hidden after "Show hidden elements"')

@step('Pop-up blocking switch')
def _():
    open_settings('Ad blocking')
    n = scroll_to(5, text='Block pop-ups')
    check(n, 'no pop-up switch')
    set_switch('Block pop-ups', False)
    leave_settings()
    goto('/popup.html', expect='Popup test')
    sleep(3)
    note('auto pop-up with blocking off: %s' % [t for t in texts() if 'auto popup' in t or 'Auto popup' in t])
    shot('popup off')
    to_base()
    open_settings('Ad blocking')
    scroll_to(5, text='Block pop-ups')
    set_switch('Block pop-ups', True)
    leave_settings()
