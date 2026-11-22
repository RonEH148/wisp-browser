"""The local test website. The device reaches it through `adb reverse tcp:8000 tcp:8000` as
http://lvh.me:8000 (and any subdomain: www.lvh.me, app.lvh.me…) or http://localtest.me:8000
(a second, separate site for third-party tests). Pages are in site/, the rest is made here."""
import base64, io, os, shutil, struct, tempfile, threading, time, urllib.parse, zipfile, zlib
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler

HERE = os.path.dirname(os.path.abspath(__file__))


def _png(w, h, rgb):
    raw = b''.join(b'\x00' + bytes(rgb) * w for _ in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

def make_assets(d):
    open(os.path.join(d, 'pic.png'), 'wb').write(_png(600, 400, (70, 130, 180)))
    open(os.path.join(d, 'pic2.png'), 'wb').write(_png(300, 300, (200, 120, 40)))
    try:
        from PIL import Image
        frames = [Image.new('P', (120, 60), i * 40) for i in range(4)]
        frames[0].save(os.path.join(d, 'ad.gif'), save_all=True, append_images=frames[1:], duration=200, loop=0)
    except Exception:
        # one-frame gif
        open(os.path.join(d, 'ad.gif'), 'wb').write(base64.b64decode(
            'R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=='))
    pdf = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj "
           b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n")
    open(os.path.join(d, 'file.pdf'), 'wb').write(pdf)


DL = {  # name -> (content type, body maker)
    'archive.zip': ('application/zip', 'zip'),
    'noext': ('image/gif', 'gif'),          # no extension: Wisp must name it .gif, not .bin
    'clip.webm': ('video/webm', 'webm'),     # Wisp offers to make a GIF
    'song.mp3': ('audio/mpeg', 'mp3'),
    'big.bin': ('application/octet-stream', 'big'),
    'doc.pdf': ('application/pdf', 'pdf'),
}

def dl_body(kind, d):
    if kind == 'zip':
        b = io.BytesIO()
        with zipfile.ZipFile(b, 'w') as z:
            z.writestr('hello.txt', 'hello from the Wisp test')
        return b.getvalue()
    if kind == 'gif':
        return open(os.path.join(d, 'ad.gif'), 'rb').read()
    if kind == 'webm':
        return b'\x1a\x45\xdf\xa3' + b'\x00' * 2000
    if kind == 'mp3':
        return b'ID3\x03\x00\x00\x00\x00\x00\x00' + b'\xff\xfb\x90\x00' * 500
    if kind == 'big':
        return os.urandom(8 * 1024 * 1024)
    if kind == 'pdf':
        return open(os.path.join(d, 'file.pdf'), 'rb').read()
    return b''


def page(title, body, head=''):
    return ('<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>%s</title>%s<style>body{font-family:sans-serif;margin:12px;font-size:18px}</style></head><body>%s</body></html>'
            % (title, head, body)).encode()


class Handler(SimpleHTTPRequestHandler):
    site_dir = None
    log = []

    def log_message(self, fmt, *args):
        Handler.log.append('%s %s %s' % (time.strftime('%H:%M:%S'), self.headers.get('Host', ''), fmt % args))

    def send(self, body, ctype='text/html; charset=utf-8', code=200, headers=()):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(body)

    def cookies(self):
        return self.headers.get('Cookie') or ''

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        n = int(self.headers.get('Content-Length') or 0)
        data = self.rfile.read(n)
        if u.path == '/login':
            f = urllib.parse.parse_qs(data.decode('utf-8', 'replace'))
            user = (f.get('username') or [''])[0] or 'nobody'
            self.send(b'', code=303, headers=[('Location', '/account'),
                      ('Set-Cookie', 'session=%s; Path=/; Max-Age=2592000' % user)])
            return
        if u.path == '/upload':
            got = b'filename="' in data
            name = data.split(b'filename="', 1)[1].split(b'"', 1)[0].decode('utf-8', 'replace') if got else ''
            self.send(page('Upload done', '<h1>UPLOAD RECEIVED</h1><p>file: %s, %d bytes</p>' % (name or '(none)', n)))
            return
        self.send(page('Posted', '<h1>POSTED</h1>'))

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        p = u.path
        host = (self.headers.get('Host') or '').split(':')[0]
        if p == '/ua':
            ua = self.headers.get('User-Agent', '')
            kind = 'MOBILE UA' if ('Mobile' in ua or 'Android' in ua) else 'DESKTOP UA'
            return self.send(page('User agent', '<h1>%s</h1><p id="ua">%s</p><p>host %s</p>' % (kind, ua, host)))
        if p == '/setcookie':
            name, value = q.get('name', 'c'), q.get('value', '1')
            attrs = '; Path=%s' % q.get('path', '/')
            if q.get('domain'):
                attrs += '; Domain=%s' % q['domain']
            attrs += '; Max-Age=%s' % q.get('maxage', '2592000')
            if q.get('samesite'):
                attrs += '; SameSite=%s' % q['samesite']
            return self.send(page('Cookie set', '<h1>COOKIE SET %s</h1><p>%s</p>' % (name, host)),
                             headers=[('Set-Cookie', '%s=%s%s' % (name, value, attrs))])
        if p in ('/cookies', '/app/cookies'):
            c = self.cookies()
            return self.send(page('Cookies', '<h1>COOKIES: %s</h1><p>host %s path %s</p>' % (c or 'none', host, p)))
        if p == '/login':
            return self.send(page('Sign in', '<h1>Sign in</h1><form method="post" action="/login">'
                                  '<p><input id="user" name="username" autocomplete="username" placeholder="Username"></p>'
                                  '<p><input id="pass" type="password" name="password" autocomplete="current-password" placeholder="Password"></p>'
                                  '<p><button id="go" type="submit">Sign in</button></p></form>'))
        if p == '/account':
            c = self.cookies()
            user = ''
            for part in c.split(';'):
                k, _, v = part.strip().partition('=')
                if k == 'session':
                    user = v
            body = '<h1>Logged in as %s</h1>' % user if user else '<h1>Not logged in</h1>'
            return self.send(page('Account', body + '<p>host %s</p>' % host))
        if p == '/app/' or p == '/app':
            return self.send(page('App folder', '<h1>APP FOLDER</h1>'),
                             headers=[('Set-Cookie', 'appc=1; Path=/app; Max-Age=2592000')])
        if p == '/slow':
            time.sleep(float(q.get('s', '8')))
            return self.send(page('Slow page', '<h1>Slow page done</h1>'))
        if p == '/redirect':
            n = int(q.get('n', '3'))
            loc = '/redirect?n=%d' % (n - 1) if n > 1 else '/other.html'
            return self.send(b'', code=302, headers=[('Location', loc)])
        if p == '/status/404':
            return self.send(page('Missing', '<h1>Not found page</h1>'), code=404)
        if p == '/title':
            t = q.get('t', 'Tab')
            return self.send(page(t, '<h1>%s</h1>' % t))
        if p.startswith('/dl/'):
            name = p[4:]
            if name in DL:
                ctype, kind = DL[name]
                hdr = []
                if q.get('attach') == '1':
                    hdr.append(('Content-Disposition', 'attachment; filename="%s"' % name))
                return self.send(dl_body(kind, self.site_dir), ctype=ctype, headers=hdr)
        if p == '/log':
            return self.send('\n'.join(Handler.log[-200:]).encode(), ctype='text/plain')
        return super().do_GET()

    def translate_path(self, path):
        path = urllib.parse.urlparse(path).path
        return os.path.join(self.site_dir, path.lstrip('/') or 'index.html')

    def end_headers(self):
        if not any(h.lower().startswith(b'cache-control') for h in getattr(self, '_headers_buffer', [])):
            self.send_header('Cache-Control', 'no-store')
        super().end_headers()


def start(port=8000):
    d = tempfile.mkdtemp(prefix='wisp-site-')
    src = os.path.join(HERE, 'site')
    for f in os.listdir(src):
        shutil.copy(os.path.join(src, f), d)
    make_assets(d)
    Handler.site_dir = d
    srv = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, d
