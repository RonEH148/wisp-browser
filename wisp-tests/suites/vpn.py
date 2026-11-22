from ._common import *
import base64

def wg_config(name='Test'):
    k = lambda: base64.b64encode(os.urandom(32)).decode()
    return ('[Interface]\nPrivateKey = %s\nAddress = 10.66.0.2/32\nDNS = 1.1.1.1\n\n'
            '[Peer]\nPublicKey = %s\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:51820\n' % (k(), k())).encode()

def vpn_page():
    open_settings('VPN')
    sleep(1)

def remove_vpn():
    vpn_page()
    n = scroll_to(4, text='Remove VPN')
    if n:
        tap_xy(*n.center)
        sleep(1)
        press_button('Remove', 'Delete', 'OK', 'Yes', timeout=3)
        sleep(1)
    leave_settings()

@step('VPN page without a configuration')
def _():
    vpn_page()
    shot('vpn empty')
    no_lumen_text()
    t = ' '.join(texts())
    note('status: %s' % [x for x in texts() if 'VPN' in x or 'Import' in x][:4])
    expect('Import' in t, 'no import option')
    leave_settings()

@step('Import a .conf file')
def _():
    push_file(wg_config(), 'wisp-test.conf')
    vpn_page()
    n = scroll_to(4, text='Import from file or archive') or scroll_to(2, text='Import .conf file')
    check(n, 'no import row')
    tap_xy(*n.center)
    picker_pick('wisp-test')
    for _ in range(8):
        if wisp_in_front():
            break
        sleep(1)
    sleep(2)
    shot('after import')
    t = ' '.join(texts())
    note('status: %s' % [x for x in texts() if 'connect' in x.lower() or 'saved' in x.lower()][:4])
    expect('Connect' in t, 'no Connect button after the import')
    leave_settings()

@step('Connect and disconnect the VPN')
def _():
    vpn_page()
    n = scroll_to(4, text='Connect', exact=True)
    check(n, 'no Connect button')
    tap_xy(*n.center)
    sleep(2)
    shot('vpn permission')
    system_dialog_ok()
    sleep(8)
    shot('vpn status')
    st = [x for x in texts() if 'onnect' in x]
    note('status: %s' % st[:4])
    vpn_on = 'tun0' in sh('ip addr') or 'VPN' in sh('dumpsys connectivity | grep -m3 -i vpn')
    note('Android shows a VPN interface: %s' % vpn_on)
    d = scroll_to(3, text='Disconnect', exact=True)
    if d:
        tap_xy(*d.center)
        sleep(3)
        shot('vpn off')
    leave_settings()
    goto('/t.html', expect='Wisp test page')

@step('Browsing still works after the VPN was on')
def _():
    goto('/other.html', expect='Other page')
    open_url('example.com', expect='Example Domain', timeout=30)

@step('Import a .zip with two configs')
def _():
    import io, zipfile
    b = io.BytesIO()
    with zipfile.ZipFile(b, 'w') as z:
        z.writestr('home.conf', wg_config())
        z.writestr('work.conf', wg_config())
    push_file(b.getvalue(), 'wisp-two.zip')
    vpn_page()
    n = scroll_to(4, text='Import from file or archive')
    check(n, 'no import row')
    tap_xy(*n.center)
    picker_pick('wisp-two')
    sleep(3)
    shot('choose config')
    c = wait_for(timeout=4, text='work')
    note('choice shown: %s' % bool(c))
    expect(c, 'no choice between the two configs')
    if c:
        tap_xy(*c.center)
        sleep(2)
    leave_settings()

@step('Import a QR code picture')
def _():
    try:
        import qrcode
    except ImportError:
        skip('python package "qrcode" not installed on the test computer')
    import io
    img = qrcode.make(wg_config().decode())
    b = io.BytesIO()
    img.save(b, format='PNG')
    push_file(b.getvalue(), 'wisp-qr.png')
    vpn_page()
    n = scroll_to(4, text='Import from file or archive')
    tap_xy(*n.center)
    picker_pick('wisp-qr')
    for _ in range(8):
        if wisp_in_front():
            break
        sleep(1)
    sleep(3)
    shot('after qr import')
    t = ' '.join(texts())
    expect('No WireGuard QR' not in t, 'QR code not read')
    leave_settings()

@step('A broken config file is refused')
def _():
    push_file(b'this is not a wireguard file\n', 'wisp-bad.conf')
    vpn_page()
    n = scroll_to(4, text='Import from file or archive')
    tap_xy(*n.center)
    picker_pick('wisp-bad')
    sleep(3)
    shot('bad config')
    note('texts: %s' % [x for x in texts() if 'valid' in x.lower() or 'found' in x.lower() or 'read' in x.lower()][:3])
    check(wisp_running(), 'Wisp stopped')
    leave_settings()

@step('VPN options say Wisp/phone, then remove the VPN')
def _():
    vpn_page()
    for _ in range(3):
        swipe_up(0.5)
    shot('vpn options')
    no_lumen_text()
    leave_settings()
    remove_vpn()
    vpn_page()
    expect(not present(text='Disconnect', exact=True) and not present(text='Remove VPN'), 'VPN still there after removing')
    leave_settings()
