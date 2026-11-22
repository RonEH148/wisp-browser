from ._common import *

def ua_kind(site=SITE):
    goto('/ua', site=site)
    sleep(1)
    t = ' '.join(texts())
    return 'desktop' if 'DESKTOP UA' in t else 'mobile' if 'MOBILE UA' in t else None

@step('Pages open as mobile by default')
def _():
    k = ua_kind()
    note('lvh.me: %s' % k)
    check(k == 'mobile', 'default is not mobile: %s' % k)

@step('Desktop site applies to the whole site (www., m.) only')
def _():
    goto('/ua', expect='UA')
    menu('Desktop site', wait=4)
    k = [t for t in texts() if ' UA' in t]
    note('after switching: %s' % k)
    expect(any('DESKTOP' in x for x in k), 'page did not reload as desktop')
    for host in ('www.lvh.me', 'm.lvh.me'):
        kk = ua_kind('http://%s:%d' % (host, PORT))
        note('%s: %s' % (host, kk))
        expect(kk == 'desktop', '%s is not desktop' % host)
    k2 = ua_kind(SITE2)
    note('localtest.me: %s' % k2)
    expect(k2 == 'mobile', 'another site became desktop')

@step('Desktop site: no reload loop')
def _():
    m = log_mark()
    goto('/ua', expect='UA')
    samples = []
    for _ in range(8):
        sleep(2.5)
        samples.append(address())
    hits = server_hits('/ua', m)
    note('requests for /ua in 20 s: %d; addresses: %s' % (hits, sorted(set(samples), key=str)))
    expect(hits <= 3, 'page reloaded %d times (loop?)' % hits)
    expect(len(set(samples)) == 1, 'address kept changing')

@step('Site settings lists lvh.me once, with its settings')
def _():
    open_settings('Site settings')
    shot('site list')
    lv = [t for t in texts() if 'lvh.me' in t]
    note('entries: %s' % lv)
    expect(len([t for t in lv if t.strip() in ('lvh.me', 'www.lvh.me', 'm.lvh.me', 'app.lvh.me')]) <= 1,
           'lvh.me listed more than once: %s' % lv)
    n = wait_for(timeout=3, text='lvh.me')
    check(n, 'lvh.me not in Site settings')
    tap_xy(*n.center)
    sleep(1.5)
    shot('site page')
    d = switch_state('Desktop site')
    note('desktop switch: %s' % d)
    expect(d is True, 'site page does not show desktop on')
    r = scroll_to(4, text='Reset this site')
    check(r, 'no "Reset this site"')
    tap_xy(*r.center)
    sleep(1)
    press_button('Reset', 'OK', timeout=2)
    leave_settings()
    k = ua_kind()
    note('after reset: %s' % k)
    expect(k == 'mobile', 'still desktop after reset')

@step('Site sheet: Delete cookies')
def _():
    goto('/setcookie?name=del&value=1', expect='COOKIE SET')
    site_sheet()
    shot('site sheet')
    n = scroll_to(3, text='Delete cookies')
    check(n, 'no Delete cookies')
    tap_xy(*n.center)
    sleep(1)
    press_button('Delete', 'OK', timeout=2)
    to_base()
    goto('/cookies')
    expect(page_has('COOKIES: none', 4), 'cookies still there: %s' % [t for t in texts() if 'COOKIES' in t])

@step('Site sheet: Delete all site data')
def _():
    goto('/setcookie?name=all&value=1', expect='COOKIE SET')
    site_sheet()
    n = scroll_to(3, text='Delete all site data')
    check(n, 'no Delete all site data')
    tap_xy(*n.center)
    sleep(1)
    press_button('Delete', 'OK', timeout=2)
    to_base()
    goto('/cookies')
    expect(page_has('COOKIES: none', 4), 'cookies still there')

@step('Site sheet on the start page')
def _():
    close_all_tabs()
    tap(rid='ibSiteData')
    sleep(1.5)
    shot('start page shield')
    note('texts: %s' % texts()[:6])
    to_base()

@step('Site sheet shows the connection state')
def _():
    open_url('https://example.com', expect='Example Domain', timeout=30)
    site_sheet()
    expect(present(text='Connection is secure'), 'https site not shown as secure')
    back()
    goto('/other.html', expect='Other page')
    site_sheet()
    expect(present(text='Connection is not secure'), 'http site not shown as not secure')
    back()
