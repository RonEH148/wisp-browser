/*
 * Lumen page enhancements (runs in every frame at document start when supported).
 * Features: cosmetic ad hiding, pop-up blocking, automatic highest video quality,
 * built-in player fallback for HLS/DASH, forced dark mode, passwords, desktop site.
 * Based on TV Bro (https://github.com/truefedex/tv-bro).
 */
(function () {
    'use strict';
    var host = location.hostname || '';
    // Never touch human-verification frames: modifying them makes checks fail.
    // (checked before anything is written to the page, so those frames stay untouched)
    if (/(^|\.)challenges\.cloudflare\.com$/.test(host) || /(^|\.)hcaptcha\.com$/.test(host) ||
        location.pathname.indexOf('/cdn-cgi/challenge-platform') === 0 ||
        location.pathname.indexOf('/recaptcha/') === 0) {
        return;
    }
    // "Clean mode" while a site's verification runs: the app removes its bridge and reloads,
    // and the page must then see a completely unmodified browser.
    if (!window.TVBro) return;
    if (window.__sofaInstalled) return;
    try {
        Object.defineProperty(window, '__sofaInstalled', { value: true, enumerable: false });
    } catch (e) { return; }

    // ---------------- settings ----------------
    var cfg = (typeof __sofaCfgEmbedded !== 'undefined' && __sofaCfgEmbedded) || {};
    try {
        if (window.TVBro && typeof TVBro.sofaSettings === 'function') {
            var live = JSON.parse(TVBro.sofaSettings(location.hostname));
            if (live && typeof live === 'object') cfg = live;
        }
    } catch (e) { /* use embedded settings */ }

    var isTop = (window.top === window);

    // ---------------- page fetch relay for Lumen's own player ----------------
    // Some video sites only hand their stream to their own page. Lumen's player then asks this
    // frame to download each piece with the page's own fetch() (same connection, cookies and
    // tokens) and gets the bytes back over the binary "LumenRelay" channel (LumenPageFetch.kt).
    if (window.LumenRelay && typeof window.LumenRelay.postMessage === 'function') (function () {
        var relay = window.LumenRelay;
        var running = {};
        function send(obj) { try { relay.postMessage(JSON.stringify(obj)); } catch (e) {} }
        function sendChunk(id, data, bin) {
            var u = data instanceof Uint8Array ? data : new Uint8Array(data);
            if (bin) {
                var out = new Uint8Array(u.length + 4);
                new DataView(out.buffer).setInt32(0, id);
                out.set(u, 4);
                relay.postMessage(out.buffer);
            } else {
                for (var i = 0; i < u.length; i += 49152) {
                    var part = u.subarray(i, Math.min(u.length, i + 49152)), str = '';
                    for (var j = 0; j < part.length; j += 8192) {
                        str += String.fromCharCode.apply(null, part.subarray(j, Math.min(part.length, j + 8192)));
                    }
                    send({ op: 'data', id: id, b64: btoa(str) });
                }
            }
        }
        function run(m) {
            var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
            running[m.id] = ctrl;
            var opts = { method: 'GET', headers: m.headers || {}, credentials: 'include', cache: 'no-store' };
            if (ctrl) opts.signal = ctrl.signal;
            fetch(m.url, opts).then(function (r) {
                var h = {};
                try { r.headers.forEach(function (v, k) { h[k] = v; }); } catch (e) {}
                send({ op: 'head', id: m.id, status: r.status, url: r.url, headers: h });
                if (!r.body || !r.body.getReader) {
                    return r.arrayBuffer().then(function (b) { sendChunk(m.id, b, m.bin); });
                }
                var reader = r.body.getReader();
                function pump() {
                    return reader.read().then(function (x) {
                        if (x.done) return;
                        sendChunk(m.id, x.value, m.bin);
                        return pump();
                    });
                }
                return pump();
            }).then(function () {
                delete running[m.id];
                send({ op: 'end', id: m.id });
            }).catch(function (e) {
                delete running[m.id];
                send({ op: 'error', id: m.id, message: String((e && e.message) || e) });
            });
        }
        relay.onmessage = function (ev) {
            var m;
            try { m = JSON.parse(ev.data); } catch (e) { return; }
            if (!m) return;
            if (m.op === 'fetch') run(m);
            else if (m.op === 'abort' && running[m.id]) {
                try { running[m.id].abort(); } catch (e) {}
                delete running[m.id];
            }
        };
        send({ op: 'hello' });
    })();
    var origOpen = window.open;
    var challengePage = false;

    function isChallengePage() {
        try {
            // only Cloudflare's own markers: fake "verify you are human" ad pages copy the title
            if (window._cf_chl_opt) return true;
            // the challenge page itself (not Cloudflare's background bot script on normal pages)
            if (document.querySelector('script[src*="/cdn-cgi/challenge-platform/h/"]')) return true;
        } catch (e) {}
        return false;
    }

    function onReady(fn) {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', fn, { once: true });
        } else {
            fn();
        }
    }

    // Wisp's reader page (the article only): page tweaks for the site itself don't apply there
    function isWispReader() {
        try { return !!(document.documentElement && document.documentElement.getAttribute('data-wisp-reader')); } catch (e) { return false; }
    }

    function addStyle(id, css) {
        var root = document.head || document.documentElement;
        if (!root) return null;
        var el = document.getElementById(id);
        if (!el) {
            el = document.createElement('style');
            el.id = id;
            root.appendChild(el);
        }
        el.textContent = css;
        return el;
    }

    // ---------------- pop-up blocker ----------------
    var lastClick = { time: 0, href: null, overlay: false, anchor: null };
    var isAppHomePage = location.href.indexOf('https://tvbro.phlox.dev/appcontent/home') === 0;
    if (cfg.popups && !isAppHomePage) {
        // Remember what was clicked. A pop-up is only refused when there was no click at all, or when
        // the click landed on an invisible overlay (the usual pop-under trick). Clicks on real links,
        // buttons and text may open new tabs - download sites rely on that.
        var isOverlayElement = function (el) {
            try {
                var node = el;
                for (var depth = 0; node && node !== document.body && depth < 6; depth++, node = node.parentElement) {
                    var st = getComputedStyle(node);
                    if (st.position !== 'fixed' && st.position !== 'absolute') continue;
                    var r = node.getBoundingClientRect();
                    var big = r.width >= window.innerWidth * 0.7 && r.height >= window.innerHeight * 0.5;
                    var empty = (node.innerText || '').trim().length === 0 && !node.querySelector('img,video,canvas,svg,picture,iframe');
                    var see = parseFloat(st.opacity) < 0.1 || st.backgroundColor === 'rgba(0, 0, 0, 0)' || st.backgroundColor === 'transparent';
                    if (big && empty && see) return true;
                }
            } catch (e) {}
            return false;
        };

        document.addEventListener('click', function (e) {
            if (!e.isTrusted) return;
            lastClick.time = Date.now();
            var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
            lastClick.href = a ? a.href : null;
            lastClick.anchor = a;
            lastClick.overlay = isOverlayElement(e.target);
            if (a && /^https?:/i.test(a.href) && !lastClick.overlay) {
                try { TVBro.sofaLinkClicked(a.href); } catch (x) {}
            }
        }, true);

        var showBlockedBanner = function (url, label, onOpen) {
            try {
                var old = document.getElementById('lumen-popup-banner');
                if (old) old.remove();
                var bar = document.createElement('div');
                bar.id = 'lumen-popup-banner';
                bar.style.cssText = 'position:fixed;left:50%;bottom:18px;transform:translateX(-50%);z-index:2147483647;' +
                    'background:rgba(15,29,32,.95);color:#e8f5f2;font:500 16px/1.3 system-ui,Roboto,sans-serif;' +
                    'padding:10px 12px 10px 16px;border-radius:14px;display:flex;gap:10px;align-items:center;box-shadow:0 6px 24px rgba(0,0,0,.4)';
                var text = document.createElement('span');
                var host = '';
                try { host = new URL(url, location.href).hostname; } catch (e) {}
                text.textContent = (label || 'Pop-up blocked') + (host ? ' (' + host + ')' : '');
                var open = document.createElement('button');
                open.textContent = 'Open';
                open.style.cssText = 'all:unset;cursor:pointer;background:#20c997;color:#062b2f;font-weight:700;padding:8px 14px;border-radius:10px';
                open.addEventListener('click', function (e) {
                    e.stopPropagation();
                    bar.remove();
                    if (onOpen) onOpen(); else origOpen.call(window, url, '_blank');
                }, true);
                var close = document.createElement('button');
                close.textContent = '✕';
                close.style.cssText = 'all:unset;cursor:pointer;padding:6px 8px;opacity:.7';
                close.addEventListener('click', function (e) { e.stopPropagation(); bar.remove(); }, true);
                bar.appendChild(text); bar.appendChild(open); bar.appendChild(close);
                (document.body || document.documentElement).appendChild(bar);
                setTimeout(function () { if (bar.parentNode) bar.remove(); }, 8000);
            } catch (e) {}
        };

        var siteOf = function (host) { return (host || '').toLowerCase().split('.').slice(-2).join('.'); };
        var sameSite = function (url) {
            try {
                var u = new URL(url, location.href);
                return u.protocol === 'about:' || u.protocol === 'javascript:' || siteOf(u.hostname) === siteOf(location.hostname);
            } catch (e) { return false; }
        };
        // sign-in and payment windows that sites open on purpose
        var TRUSTED_POPUP = /(^|\.)(google\.com|apple\.com|microsoftonline\.com|live\.com|facebook\.com|paypal\.com|github\.com|discord\.com|twitter\.com|x\.com|twitch\.tv|steamcommunity\.com|stripe\.com|amazon\.com)$/i;
        var trustedHost = function (url) {
            try { return TRUSTED_POPUP.test(new URL(url, location.href).hostname); } catch (e) { return false; }
        };
        var sameUrlHost = function (a, b) {
            try { return new URL(a, location.href).hostname === new URL(b, location.href).hostname; } catch (e) { return false; }
        };

        // A new window is allowed only when it is what was clicked: the clicked link's own address,
        // the same site, or a sign-in/payment provider. Ad scripts open other sites on any click
        // ("click anywhere → ad tab"); those are refused and offered in the "Pop-up blocked" bar.
        var popupAllowed = function (full) {
            var recentClick = (Date.now() - lastClick.time) < 2000;
            if (!recentClick || lastClick.overlay) return false;
            if (!full || sameSite(full) || trustedHost(full)) return true;
            if (lastClick.href && sameUrlHost(lastClick.href, full)) return true;
            return false;
        };

        var blockedPopup = function (full) {
            console.log('Lumen blocked pop-up: ' + full);
            if (full) {
                if (isTop) showBlockedBanner(full);
                else try { window.top.postMessage({ __sofa: 'popup', url: full }, '*'); } catch (e) {}
            }
        };
        if (isTop) {
            // the app stopped this tab from being sent to an ad site
            window.__lumenBlockedNav = function (url) {
                showBlockedBanner(url, 'Redirect to an ad site blocked', function () {
                    try { TVBro.sofaAllowNav(url); } catch (e) {}
                    location.href = url;
                });
            };
            window.addEventListener('message', function (e) {
                if (e.data && e.data.__sofa === 'popup' && typeof e.data.url === 'string') showBlockedBanner(e.data.url);
            });
        }

        var lumenOpen = function (url, target, features) {
            // opening in the same tab is navigation, not a pop-up (used e.g. by the home page search)
            var t = (target || '').toLowerCase();
            if (t === '_self' || t === '_top' || t === '_parent') return origOpen.apply(window, arguments);
            var full = '';
            try { full = url ? new URL(url, location.href).href : ''; } catch (e) {}
            if (!popupAllowed(full)) {
                blockedPopup(full);
                // Many streaming sites only start the video after their ad tab "opened" (they check
                // what window.open returned). Hand them an empty, script-less stand-in window that is
                // thrown away a little later: the site carries on, no ad tab opens.
                return fakePopupWindow();
            }
            return origOpen.apply(window, arguments);
        };
        var fakePopupWindow = function () {
            try {
                var f = document.createElement('iframe');
                f.setAttribute('sandbox', '');
                f.setAttribute('aria-hidden', 'true');
                f.style.cssText = 'display:none!important;width:0;height:0;border:0';
                (document.body || document.documentElement).appendChild(f);
                var w = f.contentWindow;
                setTimeout(function () { try { f.remove(); } catch (e) {} }, 15000);
                return w;
            } catch (e) {
                return null;
            }
        };
        // Wisp: on sites with a human-verification check the page keeps its own window.open (a
        // replaced one is visible to the check); pop-ups there are still stopped by the app itself
        if (!(cfg.phone && cfg.verifySafe)) window.open = lumenOpen;

        // ad scripts also click a hidden <a target="_blank"> from code, or call open() of a fresh
        // invisible frame, to get around window.open.
        // Not on sites with a human-verification widget: these prototype changes are visible to it.
        if (!cfg.verifySafe) try {
            var origAnchorClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function () {
                var t = (this.target || '').toLowerCase();
                if (t && t !== '_self' && t !== '_top' && t !== '_parent' && this !== lastClick.anchor &&
                    this.href && !sameSite(this.href) && !trustedHost(this.href)) {
                    blockedPopup(this.href);
                    return;
                }
                return origAnchorClick.apply(this, arguments);
            };
        } catch (e) {}
        if (!cfg.verifySafe) try {
            var cwDesc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, 'contentWindow');
            if (cwDesc && cwDesc.get) {
                Object.defineProperty(HTMLIFrameElement.prototype, 'contentWindow', {
                    configurable: true, enumerable: cwDesc.enumerable,
                    get: function () {
                        var w = cwDesc.get.call(this);
                        try { if (w && w.open !== lumenOpen && w.location.href === 'about:blank') w.open = lumenOpen; } catch (e) {}
                        return w;
                    }
                });
            }
        } catch (e) {}

        // pop-under links: invisible full-page anchors that open ads in a new tab
        document.addEventListener('click', function (e) {
            var a = e.target && e.target.closest ? e.target.closest('a[target="_blank"]') : null;
            if (!a || sameSite(a.href)) return;
            var r = a.getBoundingClientRect();
            var hugeInvisible = r.width >= window.innerWidth * 0.8 && r.height >= window.innerHeight * 0.6 &&
                (a.textContent || '').trim().length === 0 && !a.querySelector('img,video,picture');
            if (hugeInvisible) {
                e.preventDefault();
                e.stopPropagation();
                a.remove();
                console.log('Lumen blocked pop-under link: ' + a.href);
            }
        }, true);
    }

    // ---------------- cosmetic ad hiding ----------------
    var AD_SELECTORS = [
        'ins.adsbygoogle', '.adsbygoogle', '[id^="google_ads_iframe"]', '[id^="div-gpt-ad"]',
        'iframe[src*="doubleclick.net"]', 'iframe[src*="googlesyndication.com"]',
        'iframe[src*="amazon-adsystem.com"]', 'iframe[src*="adnxs.com"]', 'iframe[src*="taboola.com"]',
        'amp-ad', 'amp-embed[type="taboola"]', '[data-ad-slot]', '[data-ad-unit]', '[data-adunit]',
        '[id^="taboola-"]', '.trc_related_container', '.OUTBRAIN', '[data-widget-id^="AR_"]',
        '.ad-banner', '.ad-container', '.ad-wrapper', '.ad-slot', '.ad-unit', '.ad_unit', '.adBanner',
        '.advertisement', '.advert', '.sponsored-ad', '.banner-ad', '.sticky-ad', '.ad-sticky',
        '#ad-banner', '#ad_banner', '#adContainer', '#sticky-ad', '[aria-label="Advertisement"]',
        'a[href*="doubleclick.net/"]', 'a[href*="/aclk?"]'
    ];

    function removeAntiAdblockOverlays() {
        var re = /ad.?block|disable your ad|turn off your ad|whitelist (our|this) site|adblocker detected/i;
        var nodes = document.querySelectorAll('body > div, body > section, body > aside');
        for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var st = getComputedStyle(el);
            if (st.position !== 'fixed' && st.position !== 'absolute') continue;
            var r = el.getBoundingClientRect();
            if (r.width < window.innerWidth * 0.5 || r.height < window.innerHeight * 0.4) continue;
            if (!re.test((el.innerText || '').slice(0, 2000))) continue;
            el.remove();
            [document.documentElement, document.body].forEach(function (n) {
                if (!n) return;
                n.style.setProperty('overflow', 'auto', 'important');
                n.style.removeProperty('position');
            });
        }
    }

    // ---------------- adblock-rust: uBlock Origin element hiding (## rules) ----------------
    // The page's own hiding rules arrive before it draws; generic rules for the classes and ids
    // on the page are asked for once it is ready and while it changes (first 20 s).
    if (cfg.cosmetic && typeof TVBro.lumenCosmetic === 'function' &&
        /^https?:$/.test(location.protocol) && location.hostname !== 'challenges.cloudflare.com' &&
        location.pathname.indexOf('/cdn-cgi/') !== 0) {
        (function () {
            var res = null;
            try {
                var raw = TVBro.lumenCosmetic(location.href);
                if (raw) res = JSON.parse(raw);
            } catch (e) { res = null; }
            if (!res) return;
            // scriptlets change page functions: never on sites with a human-verification widget
            if (res.script && !cfg.verifySafe) {
                try { (new Function(res.script))(); } catch (e) {}
            }
            if (res.css && !addStyle('lumen-abp', res.css)) {
                onReady(function () { addStyle('lumen-abp', res.css); });
            }
            if (res.generichide || typeof TVBro.lumenClassIds !== 'function') return;
            // phones (Wisp): each look-up stops the page while the app answers, so only the main
            // page is scanned, fewer elements, less often and for a shorter time
            if (cfg.phone && !isTop) return;
            var seenC = Object.create(null), seenI = Object.create(null), generic = '';
            var timer = 0, until = Date.now() + (cfg.phone ? 8000 : 20000), observer = null;
            function scan() {
                if (challengePage || (challengePage = isChallengePage())) return;
                var els = document.querySelectorAll('[class],[id]');
                var n = Math.min(els.length, cfg.phone ? 2500 : 5000), nc = [], ni = [];
                for (var i = 0; i < n; i++) {
                    var el = els[i];
                    var id = el.id;
                    if (id && typeof id === 'string' && !seenI[id]) { seenI[id] = 1; ni.push(id); }
                    var cl = el.classList;
                    if (!cl) continue;
                    for (var j = 0; j < cl.length; j++) {
                        var c = cl[j];
                        if (!seenC[c]) { seenC[c] = 1; nc.push(c); }
                    }
                }
                if (!nc.length && !ni.length) return;
                var css = '';
                try { css = TVBro.lumenClassIds(location.href, nc.join(' '), ni.join(' ')) || ''; } catch (e) {}
                if (css) {
                    generic += css;
                    addStyle('lumen-abp-generic', generic);
                }
            }
            function schedule() {
                if (Date.now() > until) {
                    if (observer) { observer.disconnect(); observer = null; }
                    return;
                }
                if (!timer) timer = setTimeout(function () { timer = 0; scan(); }, cfg.phone ? 2000 : 1000);
            }
            onReady(function () {
                scan();
                try {
                    observer = new MutationObserver(schedule);
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                } catch (e) {}
            });
        })();
    }

    if (cfg.cosmetic) {
        onReady(function () {
            if (challengePage || (challengePage = isChallengePage())) return;
            addStyle('sofa-cosmetic', AD_SELECTORS.join(',\n') +
                ' { display: none !important; visibility: hidden !important; }');
            if (isTop) {
                setTimeout(removeAntiAdblockOverlays, 1500);
                setTimeout(removeAntiAdblockOverlays, 5000);
            }
        });
    }

    // ---------------- fake prompts from ad networks ----------------
    // "Verify you're not a robot – OK", "Press Allow to continue", "Virus detected", "You won"…
    // These boxes are ads: OK/Allow opens an ad, a redirect or a notification sign-up. They come
    // from ever-changing throwaway addresses, so lists can't keep up: they are recognised by what
    // they say and how they sit on the page (a box above the page with a button), then removed.
    // Real checks (Cloudflare, hCaptcha, reCAPTCHA) are frames from their own sites and stay.
    var FAKE_RE = new RegExp([
        "not a (ro)?bot", "are you (a )?(human|robot)", "(verify|confirm|prove)( that)? you( a|['’]?re| are) (human|not)",
        "(press|click|tap|hit|select)( on)?( the)? [\"'“‘]?(ok|allow)[\"'”’]?( button)? (to|if|and|below)",
        "allow( the)? notifications?( to| and)", "(virus|viruses|malware|trojan|spyware)(es)? (were |was |has been |have been )?(detected|found)",
        "your (phone|device|android|system|browser|tv) (is|has been|may be|might be) (infected|damaged|hacked|at risk|compromised)",
        "battery (is )?(damaged|infected)", "congratulations.{0,60}(won|winner|selected|lucky)", "you['’]?ve (been selected|won)",
        "(update|install|download) (your |the )?(video )?(player|codec|flash)( now)? (to|is required|required)",
        "your (video|download|file) is ready.{0,40}(click|press|tap)"
    ].join('|'), 'i');
    var CAPTCHA_SRC = /(challenges\.cloudflare\.com|hcaptcha\.com|\/recaptcha\/|google\.com\/recaptcha|recaptcha\.net|arkoselabs|funcaptcha|geetest)/i;
    var fakeRemoved = 0;
    // textContent: no page layout needed, so checking is cheap
    function fakeText(el) {
        var t = '';
        try { t = el.textContent || ''; } catch (e) {}
        return t.length > 700 ? '' : t;
    }
    function isRealCaptcha(el) {
        try {
            var fr = el.querySelectorAll ? el.querySelectorAll('iframe') : [];
            for (var i = 0; i < fr.length; i++) if (CAPTCHA_SRC.test(fr[i].src || '')) return true;
            return !!(el.querySelector && el.querySelector('.cf-turnstile, .h-captcha, .g-recaptcha, [data-sitekey]'));
        } catch (e) { return false; }
    }
    function floats(el) {
        try {
            if (el.tagName === 'DIALOG' && el.open) return true;
            var st = getComputedStyle(el);
            if (st.position === 'fixed' || st.position === 'sticky') return true;
            return st.position === 'absolute' && (parseInt(st.zIndex, 10) || 0) >= 100;
        } catch (e) { return false; }
    }
    function hasButton(el) {
        return !!el.querySelector('button, a, input[type=button], input[type=submit], [role=button], [onclick]') ||
            /^(button|a)$/i.test(el.tagName);
    }
    function removeBackdrops() {
        // the dimmed full-screen layer behind such a box: fixed, empty, covers most of the page
        var all = document.body ? document.body.children : [];
        for (var i = all.length - 1; i >= 0; i--) {
            var n = all[i];
            if (/^LUMEN-/.test(n.tagName) || n.id === 'lumen-popup-banner') continue;
            try {
                var st = getComputedStyle(n);
                if (st.position !== 'fixed') continue;
                var r = n.getBoundingClientRect();
                if (r.width < innerWidth * 0.8 || r.height < innerHeight * 0.8) continue;
                if ((n.innerText || '').trim() || n.querySelector('img,video,canvas,iframe,svg')) continue;
                if ((parseInt(st.zIndex, 10) || 0) < 100) continue;
                n.remove();
            } catch (e) {}
        }
    }
    function fakeNotice() {
        fakeRemoved++;
        if (!isTop) { try { window.top.postMessage({ __sofa: 'fakeRemoved' }, '*'); } catch (e) {} return; }
        try {
            var old = document.getElementById('lumen-fake-note');
            if (old) old.remove();
            var n = document.createElement('div');
            n.id = 'lumen-fake-note';
            n.textContent = '🛡  Fake pop-up removed';
            n.style.cssText = 'all:initial;position:fixed;left:50%;top:14px;transform:translateX(-50%);z-index:2147483647;' +
                'background:rgba(15,29,32,.92);color:#e8f5f2;font:500 14px/1.3 system-ui,sans-serif;padding:8px 14px;border-radius:12px;pointer-events:none';
            (document.body || document.documentElement).appendChild(n);
            setTimeout(function () { n.remove(); }, 2500);
        } catch (e) {}
    }
    // climbs from an element with the fake text to the box floating above the page, and removes it
    function killFake(start) {
        var box = null;
        for (var n = start, d = 0; n && n !== document.body && n !== document.documentElement && d < 14; n = n.parentElement, d++) {
            var t = fakeText(n);
            if (!t) break; // too much text: this is page content, not a small box
            if (floats(n)) box = n;
        }
        if (!box || isRealCaptcha(box) || !hasButton(box)) return false;
        if (box.querySelector('input[type=password], input[type=email], textarea, video')) return false;
        console.log('Lumen removed a fake prompt: ' + fakeText(box).replace(/\s+/g, ' ').slice(0, 120));
        box.remove();
        removeBackdrops();
        try { document.documentElement.style.removeProperty('overflow'); document.body.style.removeProperty('overflow'); } catch (e) {}
        fakeNotice();
        return true;
    }
    var BOX_SEL = 'dialog, [role=dialog], [role=alertdialog], [aria-modal=true], [class*=modal i], [class*=popup i], ' +
        '[id*=popup i], [class*=overlay i], [id*=overlay i], [class*=notice i], [class*=alert i]';
    function scanFake(root) {
        if (challengePage || !root || !root.querySelectorAll) return;
        var t = fakeText(root);
        if (!t) {
            // a big part of the page: look only at the boxes in it
            var boxes = Array.prototype.slice.call(root.querySelectorAll(BOX_SEL));
            if (root === document.body) {
                // and the layers floating at the top of the page, whatever their class names
                for (var c = 0; c < root.children.length && c < 80; c++) {
                    var ch = root.children[c];
                    if (!/^(SCRIPT|STYLE|LINK|LUMEN-)/.test(ch.tagName) && floats(ch)) boxes.push(ch);
                }
            }
            for (var b = 0; b < boxes.length && b < 40; b++) {
                var bt = fakeText(boxes[b]);
                if (bt && FAKE_RE.test(bt)) { scanFake(boxes[b]); }
            }
            return;
        }
        if (t && FAKE_RE.test(t)) {
            // the smallest element still holding the text, then up to its floating box
            var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
            var tn, seen = 0;
            while ((tn = walker.nextNode()) && seen++ < 400) {
                if (FAKE_RE.test(tn.data) || (tn.parentElement && FAKE_RE.test(fakeText(tn.parentElement)))) {
                    if (killFake(tn.parentElement)) return;
                }
            }
            killFake(root);
        }
    }
    // a whole ad frame showing such a prompt: its page asks the parent to remove the frame
    function frameIsFake() {
        if (isTop || !document.body) return false;
        var t = fakeText(document.body);
        return t && t.length < 500 && FAKE_RE.test(t) && hasButton(document.body) && !isRealCaptcha(document.body) &&
            !CAPTCHA_SRC.test(location.href);
    }
    if ((cfg.popups || cfg.cosmetic) && !isAppHomePage && !CAPTCHA_SRC.test(location.href)) {
        window.addEventListener('message', function (e) {
            var d = e.data;
            if (!d || (d.__sofa !== 'fakeFrame' && d.__sofa !== 'fakeRemoved')) return;
            if (d.__sofa === 'fakeRemoved') { if (isTop) { fakeRemoved--; fakeNotice(); } return; }
            var frames = document.querySelectorAll('iframe, frame');
            for (var i = 0; i < frames.length; i++) {
                if (frames[i].contentWindow !== e.source) continue;
                var f = frames[i], box = f;
                for (var n = f.parentElement, k = 0; n && n !== document.body && k < 6; n = n.parentElement, k++) {
                    if ((n.innerText || '').trim().length > 0 || n.querySelectorAll('iframe').length > 1) break;
                    if (floats(n)) box = n;
                }
                box.remove();
                removeBackdrops();
                fakeNotice();
            }
        });
        var pendingFake = [], fakeTimer = 0;
        var flushFake = function () {
            fakeTimer = 0;
            if (challengePage || (challengePage = isChallengePage())) { pendingFake = []; return; }
            var list = pendingFake; pendingFake = [];
            for (var i = 0; i < list.length; i++) {
                var n = list[i];
                if (!n.isConnected || list.indexOf(n) !== i) continue;
                // skip nodes inside another node of this batch: that one covers them
                var covered = false;
                for (var j = 0; j < list.length && !covered; j++) if (j !== i && list[j] !== n && list[j].contains(n)) covered = true;
                if (!covered) scanFake(n);
            }
            if (frameIsFake()) { try { window.parent.postMessage({ __sofa: 'fakeFrame' }, '*'); } catch (e) {} }
        };
        var fakeObserver = new MutationObserver(function (records) {
            for (var i = 0; i < records.length; i++) {
                var r = records[i];
                if (r.type === 'attributes') { pendingFake.push(r.target); continue; }
                var add = r.addedNodes;
                for (var j = 0; j < add.length; j++) {
                    var n = add[j];
                    if (n.nodeType === 3) n = n.parentElement;
                    if (n && n.nodeType === 1 && !/^LUMEN-/.test(n.tagName)) pendingFake.push(n);
                }
            }
            if (pendingFake.length > 60) pendingFake = [document.body || document.documentElement];
            if (pendingFake.length && !fakeTimer) fakeTimer = setTimeout(flushFake, 250);
        });
        onReady(function () {
            if (challengePage || (challengePage = isChallengePage())) return;
            fakeObserver.observe(document.documentElement, { childList: true, subtree: true,
                attributes: true, attributeFilter: ['open', 'hidden'] });
            // already on the page before the observer started
            pendingFake.push(document.body || document.documentElement);
            flushFake();
            // boxes that were on the page but hidden, and shown later by a style change
            [3000, 8000, 20000].forEach(function (ms) {
                setTimeout(function () { if (document.body) { pendingFake.push(document.body); flushFake(); } }, ms);
            });
        });
    }

    // ---------------- YouTube ads ----------------
    // YouTube serves ads from the same servers as the videos, so filter lists can't block them.
    // Instead: (1) remove the ad schedule from the player data before the player reads it,
    // (2) if an ad still starts, mute it and jump to its end / press "Skip",
    // (3) hide banner/feed ads and the "ad blockers are not allowed" pop-up.
    var isYouTube = /(^|\.)(youtube\.com|youtube-nocookie\.com)$/i.test(host);
    if (cfg.cosmetic && isYouTube) {
        var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams', 'adParams'];
        var prune = function (o, depth) {
            if (!o || typeof o !== 'object' || depth > 6) return o;
            for (var i = 0; i < AD_KEYS.length; i++) {
                if (Object.prototype.hasOwnProperty.call(o, AD_KEYS[i])) {
                    try { delete o[AD_KEYS[i]]; } catch (e) {}
                }
            }
            // player data can be nested (e.g. inside "playerResponse")
            if (o.playerResponse) prune(o.playerResponse, depth + 1);
            if (Array.isArray(o)) for (var j = 0; j < o.length && j < 20; j++) prune(o[j], depth + 1);
            return o;
        };
        try {
            var origParse = JSON.parse;
            JSON.parse = function () {
                var r = origParse.apply(this, arguments);
                try { if (r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.adSlots || r.playerResponse)) prune(r, 0); } catch (e) {}
                return r;
            };
            JSON.parse.toString = function () { return origParse.toString(); };
        } catch (e) {}
        try {
            var origJson = Response.prototype.json;
            Response.prototype.json = function () {
                return origJson.apply(this, arguments).then(function (r) {
                    try { prune(r, 0); } catch (e) {}
                    return r;
                });
            };
        } catch (e) {}
        // first page load: the data comes in a global variable
        ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
            try {
                var value;
                Object.defineProperty(window, name, {
                    configurable: true,
                    get: function () { return value; },
                    set: function (v) { value = prune(v, 0); }
                });
            } catch (e) {}
        });

        var skipAds = function () {
            var player = document.querySelector('#movie_player, .html5-video-player');
            var video = player ? player.querySelector('video') : null;
            var adShowing = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
            if (adShowing && video) {
                video.muted = true;
                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
                    try { video.currentTime = video.duration - 0.1; } catch (e) {}
                }
                try { video.playbackRate = 16; } catch (e) {}
            } else if (video && video.__lumenWasAd) {
                video.muted = false;
                if (video.playbackRate === 16) video.playbackRate = 1;
            }
            if (video) video.__lumenWasAd = !!adShowing;
            var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
                'button[class*="skip-ad"], .ytm-skip-ad-button, ytm-skip-ad-button button');
            if (skip) try { skip.click(); } catch (e) {}
            // anti-adblock dialog: remove it and keep playing
            var nag = document.querySelector('ytd-enforcement-message-view-model, ytm-enforcement-message-view-model');
            if (nag) {
                var dlg = nag.closest('tp-yt-paper-dialog, ytd-popup-container, bottom-sheet-container') || nag;
                dlg.remove();
                var backdrop = document.querySelector('tp-yt-iron-overlay-backdrop');
                if (backdrop) backdrop.remove();
                if (video && video.paused) try { video.play(); } catch (e) {}
            }
        };
        setInterval(skipAds, 300);
        onReady(function () {
            addStyle('lumen-youtube-ads',
                '#player-ads, #masthead-ad, ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-banner-promo-renderer,' +
                ' ytd-promoted-sparkles-web-renderer, ytd-promoted-video-renderer, ytd-display-ad-renderer,' +
                ' ytd-companion-slot-renderer, ytd-action-companion-ad-renderer, ytd-statement-banner-renderer,' +
                ' ytd-rich-item-renderer:has(ytd-ad-slot-renderer), ytd-search-pyv-renderer, .ytd-merch-shelf-renderer,' +
                ' .ytp-ad-overlay-container, .ytp-ad-overlay-slot, .ytp-ad-image-overlay, .video-ads.ytp-ad-module,' +
                ' ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ytm-promoted-video-renderer,' +
                ' ad-slot-renderer, ytm-ad-slot-renderer, .ytm-promoted-sparkles-text-search-root,' +
                ' ytd-enforcement-message-view-model, ytm-enforcement-message-view-model' +
                ' { display: none !important; }');
        });
    }

    // ---------------- forced dark mode ----------------
    function luminance(color) {
        var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/.exec(color || '');
        if (!m) return -1;
        if (m[4] !== undefined && parseFloat(m[4]) === 0) return -1; // transparent
        return (0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3]) / 255;
    }

    if (cfg.dark && isTop) {
        onReady(function () {
            if (challengePage || (challengePage = isChallengePage())) return;
            var bg = luminance(getComputedStyle(document.body || document.documentElement).backgroundColor);
            if (bg === -1) bg = luminance(getComputedStyle(document.documentElement).backgroundColor);
            if (bg !== -1 && bg < 0.45) return; // page is already dark
            addStyle('sofa-dark',
                'html.sofa-dark { filter: invert(1) hue-rotate(180deg) !important; background: #fff !important; }\n' +
                'html.sofa-dark img, html.sofa-dark video, html.sofa-dark picture, html.sofa-dark canvas,' +
                ' html.sofa-dark iframe, html.sofa-dark embed, html.sofa-dark object,' +
                ' html.sofa-dark [style*="background-image"], html.sofa-dark .sofa-player-bar' +
                ' { filter: invert(1) hue-rotate(180deg) !important; }\n' +
                'html.sofa-dark picture img, html.sofa-dark picture video { filter: none !important; }');
            document.documentElement.classList.add('sofa-dark');
            document.addEventListener('fullscreenchange', function () {
                document.documentElement.classList.toggle('sofa-dark', !document.fullscreenElement);
            });
            document.addEventListener('webkitfullscreenchange', function () {
                document.documentElement.classList.toggle('sofa-dark', !document.webkitFullscreenElement);
            });
        });
    }

    // ---------------- highest quality ----------------
    function pickHighestIndex(levels, getHeight, getBitrate) {
        var best = -1, bestH = -1, bestB = -1;
        for (var i = 0; i < levels.length; i++) {
            var h = getHeight(levels[i]) || 0;
            var b = getBitrate(levels[i]) || 0;
            if (h > bestH || (h === bestH && b > bestB)) { best = i; bestH = h; bestB = b; }
        }
        return best;
    }

    function labelHeight(label) {
        var m = /(\d{3,4})\s*p/i.exec(label || '');
        if (m) return parseInt(m[1], 10);
        if (/4k|uhd|2160/i.test(label || '')) return 2160;
        if (/hd/i.test(label || '')) return 720;
        return 0;
    }

    function patchHls(H) {
        if (!H || !H.prototype || H.prototype.__sofaPatched) return;
        H.prototype.__sofaPatched = true;
        var origLoad = H.prototype.loadSource;
        if (typeof origLoad !== 'function') return;
        H.prototype.loadSource = function () {
            var hls = this;
            try {
                if (!hls.__sofaHooked && cfg.maxQuality && hls.on && H.Events) {
                    hls.__sofaHooked = true;
                    hls.on(H.Events.MANIFEST_PARSED, function () {
                        try {
                            var idx = pickHighestIndex(hls.levels || [],
                                function (l) { return l.height; }, function (l) { return l.bitrate; });
                            if (idx >= 0) {
                                hls.autoLevelCapping = -1;
                                hls.currentLevel = idx;
                                hls.loadLevel = idx;
                            }
                        } catch (e) {}
                    });
                }
            } catch (e) {}
            return origLoad.apply(this, arguments);
        };
    }

    function patchShaka(shaka) {
        if (!shaka || !shaka.Player || !shaka.Player.prototype || shaka.Player.prototype.__sofaPatched) return;
        var P = shaka.Player.prototype;
        P.__sofaPatched = true;
        var origLoad = P.load;
        if (typeof origLoad !== 'function') return;
        P.load = function () {
            var player = this;
            var result = origLoad.apply(this, arguments);
            var apply = function () {
                if (!cfg.maxQuality) return;
                try {
                    var tracks = player.getVariantTracks();
                    var idx = pickHighestIndex(tracks, function (t) { return t.height; }, function (t) { return t.bandwidth; });
                    if (idx >= 0) {
                        player.configure({ abr: { enabled: false } });
                        player.selectVariantTrack(tracks[idx], true);
                    }
                } catch (e) {}
            };
            if (result && typeof result.then === 'function') result.then(apply, function () {});
            else setTimeout(apply, 2000);
            return result;
        };
    }

    function patchDash(dashjs) {
        if (!dashjs || typeof dashjs.MediaPlayer !== 'function' || dashjs.MediaPlayer.__sofaPatched) return;
        var origFactory = dashjs.MediaPlayer;
        var wrapped = function () {
            var f = origFactory.apply(this, arguments);
            try {
                var origCreate = f.create;
                f.create = function () {
                    var p = origCreate.apply(this, arguments);
                    try {
                        p.on('streamInitialized', function () {
                            if (!cfg.maxQuality) return;
                            try {
                                p.updateSettings({ streaming: { abr: { autoSwitchBitrate: { video: false } } } });
                                if (typeof p.getBitrateInfoListFor === 'function') { // dash.js v3/v4
                                    var list = p.getBitrateInfoListFor('video') || [];
                                    var i = pickHighestIndex(list, function (l) { return l.height; }, function (l) { return l.bitrate; });
                                    if (i >= 0) p.setQualityFor('video', i, true);
                                } else if (typeof p.getRepresentationsByType === 'function') { // dash.js v5
                                    var reps = p.getRepresentationsByType('video') || [];
                                    var j = pickHighestIndex(reps, function (l) { return l.height; }, function (l) { return l.bandwidth; });
                                    if (j >= 0) p.setRepresentationForTypeByIndex('video', j, true);
                                }
                            } catch (e) {}
                        });
                    } catch (e) {}
                    return p;
                };
            } catch (e) {}
            return f;
        };
        for (var k in origFactory) { try { wrapped[k] = origFactory[k]; } catch (e) {} }
        wrapped.__sofaPatched = true;
        try { dashjs.MediaPlayer = wrapped; } catch (e) {}
    }

    function hookGlobal(name, patcher) {
        try {
            var current = window[name];
            if (current) { patcher(current); return; }
            var desc = Object.getOwnPropertyDescriptor(window, name);
            if (desc && !desc.configurable) return;
            var value;
            Object.defineProperty(window, name, {
                configurable: true,
                enumerable: true,
                get: function () { return value; },
                set: function (v) { value = v; try { patcher(v); } catch (e) {} }
            });
        } catch (e) {}
    }

    function applyPlayerApiQuality() {
        if (!cfg.maxQuality) return;
        // YouTube (desktop, mobile and embeds)
        try {
            var yt = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (yt && typeof yt.getAvailableQualityLevels === 'function') {
                var levels = yt.getAvailableQualityLevels().filter(function (q) { return q !== 'auto'; });
                var key = levels[0] + '|' + location.href;
                if (levels.length && yt.__sofaQ !== key) {
                    if (typeof yt.setPlaybackQualityRange === 'function') yt.setPlaybackQualityRange(levels[0], levels[0]);
                    if (typeof yt.setPlaybackQuality === 'function') yt.setPlaybackQuality(levels[0]);
                    yt.__sofaQ = key;
                }
            }
        } catch (e) {}
        // JW Player
        try {
            if (typeof window.jwplayer === 'function') {
                var jw = window.jwplayer();
                if (jw && typeof jw.getQualityLevels === 'function') {
                    var q = jw.getQualityLevels() || [];
                    var idx = pickHighestIndex(q, function (l) { return l.height || labelHeight(l.label); },
                        function (l) { return l.bitrate; });
                    if (idx >= 0 && jw.getCurrentQuality() !== idx) jw.setCurrentQuality(idx);
                }
            }
        } catch (e) {}
        // Video.js (quality-levels plugin)
        try {
            if (window.videojs && typeof window.videojs.getPlayers === 'function') {
                var players = window.videojs.getPlayers();
                Object.keys(players).forEach(function (id) {
                    var p = players[id];
                    if (!p || typeof p.qualityLevels !== 'function') return;
                    var ql = p.qualityLevels();
                    if (!ql || !ql.length) return;
                    var arr = [];
                    for (var i = 0; i < ql.length; i++) arr.push(ql[i]);
                    var best = pickHighestIndex(arr, function (l) { return l.height; }, function (l) { return l.bitrate; });
                    for (var j = 0; j < arr.length; j++) arr[j].enabled = (j === best);
                });
            }
        } catch (e) {}
        // Plyr
        try {
            var plyrEls = document.querySelectorAll('.plyr');
            for (var n = 0; n < plyrEls.length; n++) {
                var pl = plyrEls[n].plyr;
                if (pl && pl.options && pl.options.quality && pl.options.quality.length) {
                    var max = Math.max.apply(null, pl.options.quality.map(Number).filter(isFinite));
                    if (isFinite(max) && pl.quality !== max) pl.quality = max;
                }
            }
        } catch (e) {}
        // late-loaded libraries
        try { patchHls(window.Hls); } catch (e) {}
        try { patchShaka(window.shaka); } catch (e) {}
    }

    if (cfg.maxQuality) {
        hookGlobal('Hls', patchHls);
        hookGlobal('shaka', patchShaka);
        hookGlobal('dashjs', patchDash);
    }

    // Most sites bundle their player (hls.js, video.js, JW, Shaka, Clappr, ...) inside their own
    // code, where the hooks above can't reach it, and the player's automatic quality only climbs to
    // a 10+ Mbit/s version after measuring a fast connection for a while - often never. So the
    // stream's quality list itself (HLS master playlist / DASH manifest) is trimmed to the best
    // version this device can play before any player reads it; every player then has to use it.
    var lumenCodecOk = function (mime, codecs) {
        if (!codecs) return true;
        try {
            if (window.MediaSource && typeof MediaSource.isTypeSupported === 'function') {
                return MediaSource.isTypeSupported(mime + '; codecs="' + codecs + '"');
            }
        } catch (e) {}
        return true;
    };

    var hlsAttr = function (attrs, name) {
        var m = new RegExp('(?:^|,)' + name + '=("([^"]*)"|[^,]*)').exec(attrs);
        if (!m) return null;
        return m[2] !== undefined ? m[2] : m[1];
    };

    window.__lumenBestHls = function (text) {
        if (typeof text !== 'string' || text.indexOf('#EXT-X-STREAM-INF') < 0) return null;
        var lines = text.split(/\r?\n/);
        var variants = [];
        for (var i = 0; i < lines.length; i++) {
            if (lines[i].indexOf('#EXT-X-STREAM-INF:') !== 0) continue;
            var j = i + 1;
            while (j < lines.length && (lines[j].trim() === '' || lines[j].charAt(0) === '#')) j++;
            if (j >= lines.length) break;
            var attrs = lines[i].substring(18);
            var res = hlsAttr(attrs, 'RESOLUTION') || '';
            var rm = /(\d+)x(\d+)/i.exec(res);
            var codecs = hlsAttr(attrs, 'CODECS');
            var onlyAudio = codecs && !/(avc|hvc|hev|av01|vp0?9|vp8|dvh|dva)/i.test(codecs);
            variants.push({
                tag: i, uri: j,
                h: rm ? parseInt(rm[2], 10) : 0,
                bw: parseInt(hlsAttr(attrs, 'BANDWIDTH') || '0', 10) || 0,
                ok: !onlyAudio && lumenCodecOk('video/mp4', codecs)
            });
            i = j;
        }
        if (variants.length < 2) return null;
        var best = null;
        for (var k = 0; k < variants.length; k++) {
            var v = variants[k];
            if (!v.ok) continue;
            if (!best || v.h > best.h || (v.h === best.h && v.bw > best.bw)) best = v;
        }
        if (!best) return null;
        var drop = {};
        for (var d = 0; d < variants.length; d++) {
            if (variants[d] === best) continue;
            drop[variants[d].tag] = true;
            drop[variants[d].uri] = true;
        }
        var out = [];
        for (var n = 0; n < lines.length; n++) if (!drop[n]) out.push(lines[n]);
        return out.join('\n');
    };

    window.__lumenBestMpd = function (text) {
        if (typeof text !== 'string' || !/<MPD[\s>]/.test(text) || typeof DOMParser !== 'function') return null;
        var doc;
        try { doc = new DOMParser().parseFromString(text, 'application/xml'); } catch (e) { return null; }
        if (!doc || doc.getElementsByTagName('parsererror').length) return null;
        var sets = doc.getElementsByTagNameNS('*', 'AdaptationSet');
        var changed = false;
        for (var i = 0; i < sets.length; i++) {
            var set = sets[i];
            var reps = [];
            for (var c = set.firstElementChild; c; c = c.nextElementSibling) {
                if (c.localName === 'Representation') reps.push(c);
            }
            if (reps.length < 2) continue;
            var setMime = set.getAttribute('mimeType') || '';
            var isVideo = set.getAttribute('contentType') === 'video' || /video/i.test(setMime) ||
                /video/i.test(reps[0].getAttribute('mimeType') || '') ||
                (!!(reps[0].getAttribute('height') || set.getAttribute('height')) && !/audio|text/i.test(setMime));
            if (!isVideo) continue;
            var best = null, bestH = -1, bestB = -1;
            for (var r = 0; r < reps.length; r++) {
                var rep = reps[r];
                var mime = rep.getAttribute('mimeType') || setMime || 'video/mp4';
                var codecs = rep.getAttribute('codecs') || set.getAttribute('codecs');
                if (!lumenCodecOk(mime, codecs)) continue;
                var h = parseInt(rep.getAttribute('height') || set.getAttribute('height') || '0', 10) || 0;
                var b = parseInt(rep.getAttribute('bandwidth') || '0', 10) || 0;
                if (h > bestH || (h === bestH && b > bestB)) { best = rep; bestH = h; bestB = b; }
            }
            if (!best) continue;
            for (var x = 0; x < reps.length; x++) {
                if (reps[x] !== best) { set.removeChild(reps[x]); changed = true; }
            }
        }
        if (!changed) return null;
        try { return new XMLSerializer().serializeToString(doc); } catch (e) { return null; }
    };

    // the playlists the page's own player received: the app gives exactly these to an external
    // player (asking the server again can fail - some sites accept their request signatures once)
    var reportManifest = function (url, text) {
        try {
            if (!url || typeof text !== 'string' || text.length > 3000000) return;
            var head = text.substring(0, 2000).replace(/^\uFEFF/, '').replace(/^\s+/, '');
            if (head.indexOf('#EXTM3U') !== 0 && !/<MPD[\s>]/.test(head)) return;
            TVBro.sofaManifestCopy(String(new URL(url, location.href)), text);
        } catch (e) {}
    };

    var lumenTrimManifest = function (text) {
        if (typeof text !== 'string' || text.length > 4000000) return null;
        var head = text.substring(0, 2000).replace(/^﻿/, '').replace(/^\s+/, '');
        try {
            if (head.indexOf('#EXTM3U') === 0) return window.__lumenBestHls(text);
            if (/<MPD[\s>]/.test(head)) return window.__lumenBestMpd(text);
        } catch (e) {}
        return null;
    };

    if (cfg.maxQuality) {
        (function () {
            // XMLHttpRequest (hls.js, video.js, dash.js, JW Player, Clappr, ...)
            try {
                var XP = XMLHttpRequest.prototype;
                var txtDesc = Object.getOwnPropertyDescriptor(XP, 'responseText');
                var resDesc = Object.getOwnPropertyDescriptor(XP, 'response');
                var decoder = (typeof TextDecoder === 'function') ? new TextDecoder() : null;
                var encoder = (typeof TextEncoder === 'function') ? new TextEncoder() : null;
                var transformed = function (xhr) {
                    if (xhr.readyState !== 4) return undefined;
                    if (xhr.__lumenQ) return xhr.__lumenQ.out;
                    var st = { out: null };
                    xhr.__lumenQ = st;
                    try {
                        var type = xhr.responseType;
                        if (type === '' || type === 'text') {
                            var orig = txtDesc.get.call(xhr);
                            st.out = lumenTrimManifest(orig);
                            reportManifest(xhr.responseURL, typeof st.out === 'string' ? st.out : orig);
                        } else if (type === 'arraybuffer' && decoder && encoder) {
                            var buf = resDesc.get.call(xhr);
                            if (buf && buf.byteLength < 4000000) {
                                var first = decoder.decode(new Uint8Array(buf, 0, Math.min(buf.byteLength, 64)));
                                if (/#EXTM3U|<\?xml|<MPD/.test(first)) {
                                    var origT = decoder.decode(new Uint8Array(buf));
                                    var t = lumenTrimManifest(origT);
                                    reportManifest(xhr.responseURL, t != null ? t : origT);
                                    if (t != null) { var u8 = encoder.encode(t); st.out = u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength); st.text = t; }
                                }
                            }
                        }
                    } catch (e) { st.out = null; }
                    return st.out;
                };
                if (txtDesc && txtDesc.get && resDesc && resDesc.get) {
                    Object.defineProperty(XP, 'responseText', {
                        configurable: true, enumerable: txtDesc.enumerable,
                        get: function () {
                            var t = transformed(this);
                            if (typeof t === 'string') return t;
                            return txtDesc.get.call(this);
                        }
                    });
                    Object.defineProperty(XP, 'response', {
                        configurable: true, enumerable: resDesc.enumerable,
                        get: function () {
                            var t = transformed(this);
                            if (t != null) return t;
                            return resDesc.get.call(this);
                        }
                    });
                }
            } catch (e) {}
            // fetch (Shaka Player, newer hls.js setups)
            try {
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    var looksLikeManifest = /\.(m3u8|mpd)(\?|#|$)|m3u8|manifest|master|playlist/i;
                    window.fetch = function (input, init) {
                        var p = origFetch.apply(this, arguments);
                        var url = '';
                        try { url = typeof input === 'string' ? input : (input && input.url) || String(input); } catch (e) {}
                        return p.then(function (res) {
                            try {
                                if (!res || !res.ok || res.type === 'opaque') return res;
                                var ct = (res.headers && res.headers.get('content-type')) || '';
                                if (!/mpegurl|dash\+xml/i.test(ct) && !looksLikeManifest.test(url)) return res;
                                if (/^(video|audio|image)\//i.test(ct)) return res;
                                var len = parseInt((res.headers && res.headers.get('content-length')) || '0', 10);
                                if (len > 4000000) return res;
                                return res.clone().text().then(function (t) {
                                    var out = lumenTrimManifest(t);
                                    reportManifest(res.url || url, out != null ? out : t);
                                    if (out == null) return res;
                                    var nr = new Response(out, { status: res.status, statusText: res.statusText, headers: res.headers });
                                    try {
                                        Object.defineProperty(nr, 'url', { value: res.url });
                                        Object.defineProperty(nr, 'redirected', { value: res.redirected });
                                    } catch (e) {}
                                    return nr;
                                }, function () { return res; });
                            } catch (e) { return res; }
                        });
                    };
                }
            } catch (e) {}
        })();
    }

    // ---------------- media helpers ----------------
    function allMedia() {
        var list = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
        // same-origin iframes
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            try {
                var d = frames[i].contentDocument;
                if (d) list = list.concat(Array.prototype.slice.call(d.querySelectorAll('video, audio')));
            } catch (e) {}
        }
        return list;
    }

    function isPlaying(m) {
        return m.currentTime > 0 && !m.paused && !m.ended && m.readyState > 2;
    }

    function bestMedia() {
        var list = allMedia();
        var best = null, bestScore = -1;
        list.forEach(function (m) {
            var r = m.getBoundingClientRect();
            var area = Math.max(0, r.width) * Math.max(0, r.height);
            var score = area + (isPlaying(m) ? 1e9 : 0) + (m.tagName === 'VIDEO' ? 1 : 0);
            if (score > bestScore) { best = m; bestScore = score; }
        });
        return best;
    }

    var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

    function mediaCommand(cmd, m) {
        m = m || bestMedia();
        if (!m) return false;
        switch (cmd) {
            case 'toggle': if (m.paused || m.ended) m.play(); else m.pause(); break;
            case 'stop': m.pause(); m.currentTime = 0; break;
            case 'pause': m.pause(); break;
            case 'handoff-deband':
                // open this video in Lumen's own player with anime deband, at the same position
                try {
                    TVBro.sofaOpenVideo('native_deband', m.currentSrc || m.src || '', location.href,
                        (isTop ? document.title : '') || '', m.currentTime || 0);
                    m.pause();
                } catch (e) {}
                break;
            case 'rewind': m.currentTime = Math.max(0, m.currentTime - 10); break;
            case 'forward': m.currentTime = m.currentTime + 10; break;
            case 'speed':
                var i = SPEEDS.indexOf(m.playbackRate);
                m.playbackRate = SPEEDS[(i + 1) % SPEEDS.length] || 1;
                break;
        }
        return true;
    }

    // Called by remote-control media keys (see generic_injects.js). Falls back to
    // asking cross-origin iframes (video embeds) to handle the command.
    window.__sofaMedia = function (cmd) {
        if (mediaCommand(cmd)) return true;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            try { frames[i].contentWindow.postMessage({ __sofa: 'media', cmd: cmd }, '*'); } catch (e) {}
        }
        return true;
    };

    window.addEventListener('message', function (e) {
        var d = e.data;
        if (!d || d.__sofa !== 'media') return;
        if (!mediaCommand(d.cmd)) {
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
                try { frames[i].contentWindow.postMessage(d, '*'); } catch (err) {}
            }
        }
    });

    // ---------------- video playback ----------------
    // No on-screen controls: the site's own player is used. Lumen only picks the best quality and,
    // when the web engine can't play an HLS/DASH stream (".m3u8"/".mpd"), opens it in Lumen's
    // built-in hardware player instead.
    var handedOff = {};

    function streamSrc(v) {
        var src = v.currentSrc || v.src || '';
        if (!src) {
            var source = v.querySelector('source[src]');
            if (source) src = source.src;
        }
        return src;
    }

    function isManifestUrl(u) {
        return /\.(m3u8|mpd)(\?|#|$)/i.test(u || '');
    }

    document.addEventListener('play', function (e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO') return;
        applyPlayerApiQuality();
        setTimeout(applyPlayerApiQuality, 1500);
        setTimeout(applyPlayerApiQuality, 5000);
    }, true);

    // media errors don't bubble, so listen in the capture phase
    document.addEventListener('error', function (e) {
        var v = e.target;
        if (!v || (v.tagName !== 'VIDEO' && v.tagName !== 'SOURCE')) return;
        if (v.tagName === 'SOURCE') v = v.parentElement;
        if (!v || v.tagName !== 'VIDEO') return;
        var src = streamSrc(v);
        if (!isManifestUrl(src) || handedOff[src]) return;
        handedOff[src] = true;
        try {
            if (window.TVBro && TVBro.sofaOpenVideo) {
                TVBro.sofaOpenVideo('native', src, location.href, (isTop ? document.title : '') || '', v.currentTime || 0);
            }
        } catch (err) {}
    }, true);

    // ---------------- grab links ----------------
    // Read by the Grab button: media addresses written in the page itself (video/audio/<track>).
    window.__lumenDomMedia = function () {
        var out = [];
        var add = function (k, u) { if (u && /^https?:/i.test(u)) out.push({ k: k, u: u }); };
        var docs = [document];
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            try { if (frames[i].contentDocument) docs.push(frames[i].contentDocument); } catch (e) {}
        }
        docs.forEach(function (d) {
            Array.prototype.forEach.call(d.querySelectorAll('video'), function (v) {
                add('v', v.currentSrc || v.src);
                Array.prototype.forEach.call(v.querySelectorAll('source[src]'), function (x) { add('v', x.src); });
            });
            Array.prototype.forEach.call(d.querySelectorAll('audio'), function (a) {
                add('a', a.currentSrc || a.src);
                Array.prototype.forEach.call(a.querySelectorAll('source[src]'), function (x) { add('a', x.src); });
            });
            Array.prototype.forEach.call(d.querySelectorAll('track[src]'), function (t) { add('s', t.src); });
        });
        return out;
    };

    // ---------------- anime deband for videos in web pages ----------------
    // Toolbar "Deband" button: draws the video through a WebGL deband filter on a canvas placed
    // exactly over the <video>. Works for normal and MSE (blob:) videos; DRM-protected videos and
    // cross-origin videos without CORS can't be read by the browser and are left as they are.
    var DEBAND_VERT = "\nattribute vec4 aFramePosition;\nvarying vec2 vTexSamplingCoord;\nvoid main() {\n  gl_Position = aFramePosition;\n  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;\n}\n";
    var DEBAND_FRAG = "\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\nuniform sampler2D uTexSampler;\nuniform vec2 uTexSize;\nuniform vec3 uParams;\nvarying vec2 vTexSamplingCoord;\nfloat luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\nvec4 at(vec2 px) { return texture2D(uTexSampler, px / uTexSize); }\n\n// mpv's deband algorithm (as used by Hayase's \"video-deband\", MIT, github.com/ThaUnknown/video-deband):\n// for 4 growing radii, average 4 random samples around the pixel; where the pixel differs from that\n// average by less than a tiny threshold (a banding step), take the average. Then add fine grain.\nuniform float uFrame;\n#define THRESHOLD 88\n#define RANGE 17\n#define ITERATIONS 4\n#define GRAIN 12\nfloat mod289(float x)  { return x - floor(x / 289.0) * 289.0; }\nfloat permute(float x) { return mod289((34.0 * x + 1.0) * x); }\nfloat rand(float x)    { return fract(x / 41.0); }\nvec4 average(vec2 pos, float range, inout float h) {\n  float dist = rand(h) * range;     h = permute(h);\n  float dir  = rand(h) * 6.2831853; h = permute(h);\n  vec2 pt = dist / uTexSize;\n  vec2 o = vec2(cos(dir), sin(dir));\n  vec4 r0 = texture2D(uTexSampler, pos + pt * vec2( o.x,  o.y));\n  vec4 r1 = texture2D(uTexSampler, pos + pt * vec2(-o.y,  o.x));\n  vec4 r2 = texture2D(uTexSampler, pos + pt * vec2(-o.x, -o.y));\n  vec4 r3 = texture2D(uTexSampler, pos + pt * vec2( o.y, -o.x));\n  return (r0 + r1 + r2 + r3) * 0.25;\n}\nvoid main() {\n  vec2 pos = vTexSamplingCoord;\n  float random = fract(uFrame * 0.61803398875 + 0.1);\n  vec3 m = vec3(pos, random) + vec3(1.0);\n  float h = permute(permute(permute(m.x) + m.y) + m.z);\n  vec4 col = texture2D(uTexSampler, pos);\n  for (int i = 1; i <= ITERATIONS; i++) {\n    vec4 avg = average(pos, float(i * RANGE), h);\n    vec4 diff = abs(col - avg);\n    col = mix(avg, col, step(vec4(float(THRESHOLD) / (float(i) * 16384.0)), diff));\n  }\n  vec3 noise;\n  noise.x = rand(h); h = permute(h);\n  noise.y = rand(h); h = permute(h);\n  noise.z = rand(h); h = permute(h);\n  col.rgb += (float(GRAIN) / 8192.0) * (noise - vec3(0.5));\n  gl_FragColor = vec4(col.rgb, 1.0);\n}\n";
    var debandWanted = false;
    var debandLayers = [];

    function debandReport(code) {
        try { if (window.TVBro && TVBro.sofaDebandStatus) TVBro.sofaDebandStatus(code); } catch (e) {}
    }

    var DEINTERLACE_FRAG = "\n#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\nuniform sampler2D uTexSampler;\nuniform vec2 uTexSize;\nuniform vec3 uParams;\nvarying vec2 vTexSamplingCoord;\nfloat luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\nvec4 at(vec2 px) { return texture2D(uTexSampler, px / uTexSize); }\n\nvec3 chroma(vec3 c) { return c - vec3(luma(c)); }\nvec3 S(vec2 px, float dy) { return at(px + vec2(0.0, dy)).rgb; }\n// one full period, averaged (luma and colour of the average = average of luma and colour)\nvec3 box(vec2 px, float p) {\n  return 0.25 * (S(px, -0.375 * p) + S(px, -0.125 * p) + S(px, 0.125 * p) + S(px, 0.375 * p));\n}\nvoid metY(float yc, float lq1, float lq2, float lb1, float lb2, float lr1, float lr2, float p,\n          inout float best, inout float bestP, inout float bestM) {\n  float ii = 0.5 * (yc - 0.5 * (lb1 + lb2));\n  float qq = 0.5 * (lq1 - lq2);\n  float amp = sqrt(ii * ii + qq * qq);\n  float rep = max(abs(yc - lr1), abs(yc - lr2));\n  float m = smoothstep(uParams.x, uParams.y, amp) * (1.0 - smoothstep(0.6, 1.2, rep / max(2.0 * amp, 0.004)));\n  if (m * amp > best) { best = m * amp; bestP = p; bestM = m; }\n}\nvoid metC(vec3 cc, vec3 kq1, vec3 kq2, vec3 kb1, vec3 kb2, vec3 kr1, vec3 kr2, float p,\n          inout float best, inout float bestP, inout float bestM) {\n  vec3 ic = 0.5 * (cc - 0.5 * (kb1 + kb2));\n  vec3 qc = 0.5 * (kq1 - kq2);\n  float amp = sqrt(dot(ic, ic) + dot(qc, qc));\n  float rep = max(length(cc - kr1), length(cc - kr2));\n  float m = smoothstep(uParams.z, uParams.z * 3.0, amp) * (1.0 - smoothstep(0.6, 1.2, rep / max(2.0 * amp, 0.004)));\n  if (m * amp > best) { best = m * amp; bestP = p; bestM = m; }\n}\n// tests line spacing p for brightness and colour, and 2p for colour (4:2:0). The 2p test reuses\n// the p samples (its quarter and half period are p's half and full period): 8 reads instead of 18.\nvoid period(vec2 px, float p, float yc, vec3 cc,\n            inout float bY, inout float pY, inout float mY, inout float bC, inout float pC, inout float mC) {\n  vec3 q1 = S(px, 0.25 * p); vec3 q2 = S(px, -0.25 * p);\n  vec3 b1 = S(px, 0.5 * p);  vec3 b2 = S(px, -0.5 * p);\n  vec3 r1 = S(px, p);        vec3 r2 = S(px, -p);\n  vec3 d1 = S(px, 2.0 * p);  vec3 d2 = S(px, -2.0 * p);\n  metY(yc, luma(q1), luma(q2), luma(b1), luma(b2), luma(r1), luma(r2), p, bY, pY, mY);\n  vec3 cb1 = chroma(b1); vec3 cb2 = chroma(b2); vec3 cr1 = chroma(r1); vec3 cr2 = chroma(r2);\n  metC(cc, chroma(q1), chroma(q2), cb1, cb2, cr1, cr2, p, bC, pC, mC);\n  metC(cc, cb1, cb2, cr1, cr2, chroma(d1), chroma(d2), 2.0 * p, bC, pC, mC);\n}\nvoid main() {\n  vec2 px = vTexSamplingCoord * uTexSize;\n  vec4 c4 = at(px);\n  float yc = luma(c4.rgb);\n  vec3 cc = c4.rgb - vec3(yc);\n  // line spacing: native interlace, and interlace of a 576- or 480-line master scaled to this height\n  float pA = 2.0;\n  float pB = max(2.0, 2.0 * uTexSize.y / 576.0);\n  float pCc = max(2.0, 2.0 * uTexSize.y / 480.0);\n  float bY = 0.0; float pY = 0.0; float mY = 0.0;\n  float bC = 0.0; float pC = 0.0; float mC = 0.0;\n  period(px, pA, yc, cc, bY, pY, mY, bC, pC, mC);\n  if (pB != pA) period(px, pB, yc, cc, bY, pY, mY, bC, pC, mC);\n  if (pCc != pB) period(px, pCc, yc, cc, bY, pY, mY, bC, pC, mC);\n  float outY = yc; vec3 outC = cc;\n  if (bY > 0.0) {\n    vec3 bx = box(px, pY);\n    outY = mix(yc, luma(bx), mY);\n    if (bC > 0.0 && pC == pY) outC = mix(cc, chroma(bx), mC);\n  }\n  if (bC > 0.0 && !(bY > 0.0 && pC == pY)) outC = mix(cc, chroma(box(px, pC)), mC);\n  gl_FragColor = vec4(vec3(outY) + outC, c4.a);\n}\n";

    // Builds the two-pass GPU filter (deinterlace, then deband) on a WebGL context.
    // render(source, w, h) draws one frame of [source] (video, canvas or image of size w×h).
    function makeDebandRenderer(gl) {
        var compile = function (type, src) {
            var sh = gl.createShader(type); gl.shaderSource(sh, src); gl.compileShader(sh);
            if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
            return sh;
        };
        var link = function (frag) {
            var prog = gl.createProgram();
            gl.attachShader(prog, compile(gl.VERTEX_SHADER, DEBAND_VERT));
            gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, frag));
            gl.bindAttribLocation(prog, 0, 'aFramePosition');
            gl.linkProgram(prog);
            if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
            return {
                prog: prog,
                uTexSize: gl.getUniformLocation(prog, 'uTexSize'),
                uFrame: gl.getUniformLocation(prog, 'uFrame'),
                uParams: gl.getUniformLocation(prog, 'uParams'),
                uTex: gl.getUniformLocation(prog, 'uTexSampler')
            };
        };
        var deint = link(DEINTERLACE_FRAG);
        var deband = link(DEBAND_FRAG);
        // light: 2 instead of 4 deband rounds (TVs, or when the device can't keep up)
        var debandLight = link(DEBAND_FRAG.replace('#define ITERATIONS 4', '#define ITERATIONS 2'));
        var buf = gl.createBuffer();
        gl.bindBuffer(gl.ARRAY_BUFFER, buf);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 0, 1, 1, -1, 0, 1, -1, 1, 0, 1, 1, 1, 0, 1]), gl.STATIC_DRAW);
        gl.enableVertexAttribArray(0);
        gl.vertexAttribPointer(0, 4, gl.FLOAT, false, 0, 0);
        var makeTex = function () {
            var t = gl.createTexture();
            gl.bindTexture(gl.TEXTURE_2D, t);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
            gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
            return t;
        };
        var srcTex = makeTex();
        var midTex = makeTex();
        var fbo = gl.createFramebuffer();
        var midW = 0, midH = 0, frame = 0;
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
        return {
            render: function (source, w, h, outW, outH, light) {
                gl.activeTexture(gl.TEXTURE0);
                gl.bindTexture(gl.TEXTURE_2D, srcTex);
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, source);
                frame = (frame + 1) % 1024;
                var input = srcTex;
                // interlaced video is never above 1080 lines; skip the pass for bigger videos
                if (h <= 1200) {
                    if (midW !== w || midH !== h) {
                        gl.bindTexture(gl.TEXTURE_2D, midTex);
                        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, w, h, 0, gl.RGB, gl.UNSIGNED_BYTE, null);
                        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
                        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, midTex, 0);
                        midW = w; midH = h;
                    }
                    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
                    gl.viewport(0, 0, w, h);
                    gl.useProgram(deint.prog);
                    gl.bindTexture(gl.TEXTURE_2D, srcTex);
                    gl.uniform1i(deint.uTex, 0);
                    gl.uniform2f(deint.uTexSize, w, h);
                    gl.uniform3f(deint.uParams, 0.7 / 255, 2.5 / 255, 0.8 / 255);
                    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
                    input = midTex;
                }
                gl.bindFramebuffer(gl.FRAMEBUFFER, null);
                gl.viewport(0, 0, outW, outH);
                var db = light ? debandLight : deband;
                gl.useProgram(db.prog);
                gl.bindTexture(gl.TEXTURE_2D, input);
                gl.uniform1i(db.uTex, 0);
                gl.uniform2f(db.uTexSize, w, h);
                gl.uniform1f(db.uFrame, frame + Math.random() * 1000);
                gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
            }
        };
    }
    window.__lumenMakeDebandRenderer = makeDebandRenderer;

    function attachDeband(v) {
        if (!v || v.__lumenDeband || v.__lumenDebandTooSlow || !v.parentNode) return;
        if (v.mediaKeys) { debandReport('drm'); return; }
        var canvas = document.createElement('canvas');
        canvas.className = 'lumen-deband-layer';
        canvas.style.cssText = 'position:absolute;pointer-events:none;display:block;margin:0;padding:0;border:0;background:transparent;visibility:hidden;';
        var gl = canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, stencil: false, premultipliedAlpha: false });
        if (!gl) { debandReport('nogl'); return; }
        // the filters work in pixel positions: graphics chips without full float precision in
        // fragment shaders draw stripes instead of a clean picture, so don't run there
        try {
            var hp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
            if (!hp || hp.precision < 16) { debandReport('nogl'); return; }
        } catch (e) {}
        var renderer;
        try { renderer = makeDebandRenderer(gl); }
        catch (e) { console.log('Lumen deband: ' + e); debandReport('nogl'); return; }

        v.parentNode.insertBefore(canvas, v.nextSibling);
        var layer = { v: v, canvas: canvas, running: true, light: !!cfg.tv };
        // safety net: frames the screen showed without our filter in between mean the device
        // can't keep up; first go light, then stop (instead of freezing the whole TV)
        var perf = { since: 0, seen: 0, missed: 0, last: -1 };
        var checkPerf = function (now, meta) {
            if (!meta || typeof meta.presentedFrames !== 'number' || v.paused) { perf.last = -1; return true; }
            if (perf.last >= 0) {
                var gap = Math.max(0, meta.presentedFrames - perf.last - 1);
                perf.missed += gap;
                perf.seen += 1 + gap;
            }
            perf.last = meta.presentedFrames;
            if (!perf.since) perf.since = now;
            if (now - perf.since < 3000) return true;
            var slow = perf.seen > 30 && perf.missed / perf.seen > 0.25;
            perf.since = now; perf.seen = 0; perf.missed = 0;
            if (!slow) return true;
            if (!layer.light) { layer.light = true; debandReport('slow:light'); return true; }
            v.__lumenDebandTooSlow = true;
            detachDeband(layer);
            debandReport('slow:off');
            return false;
        };
        v.__lumenDeband = layer;
        debandLayers.push(layer);

        // position the canvas over the video (not every frame: reading styles each frame costs time)
        var place = function () {
            var cs = getComputedStyle(v);
            canvas.style.left = v.offsetLeft + 'px';
            canvas.style.top = v.offsetTop + 'px';
            canvas.style.width = v.offsetWidth + 'px';
            canvas.style.height = v.offsetHeight + 'px';
            canvas.style.objectFit = cs.objectFit || 'contain';
            canvas.style.objectPosition = cs.objectPosition || '50% 50%';
            canvas.style.zIndex = cs.zIndex === 'auto' ? '' : cs.zIndex;
            canvas.style.transform = cs.transform === 'none' ? '' : cs.transform;
            var shown = v.offsetWidth > 0 && cs.display !== 'none' && cs.visibility !== 'hidden' &&
                v.readyState >= 2 && layer.drawn;
            canvas.style.visibility = shown ? 'visible' : 'hidden';
        };
        layer.placeTimer = setInterval(place, 400);
        try {
            layer.resizeObs = new ResizeObserver(place);
            layer.resizeObs.observe(v);
        } catch (e) {}

        var draw = function (now, meta) {
            if (!layer.running) return;
            if (layer.drawn && !checkPerf(now, meta)) return;
            try {
                if (v.readyState >= 2 && v.videoWidth) {
                    var scale = Math.min(1, 1920 / v.videoWidth);
                    var w = Math.round(v.videoWidth * scale), h = Math.round(v.videoHeight * scale);
                    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
                    renderer.render(v, v.videoWidth, v.videoHeight, w, h, layer.light);
                    if (!layer.drawn) {
                        layer.drawn = true;
                        place();
                        debandReport('ok:' + v.videoWidth + 'x' + v.videoHeight);
                    }
                }
            } catch (e) {
                // the browser refuses to let pages read this video (protected or cross-site)
                detachDeband(layer);
                debandReport(e && e.name === 'SecurityError' ? 'blocked' : 'error:' + ((e && (e.name + ': ' + e.message)) || e));
                return;
            }
            if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(draw); else requestAnimationFrame(draw);
        };
        draw();
        v.addEventListener('seeked', function () { if (layer.running) requestAnimationFrame(draw); });
    }

    function detachDeband(layer) {
        layer.running = false;
        clearInterval(layer.placeTimer);
        try { if (layer.resizeObs) layer.resizeObs.disconnect(); } catch (e) {}
        if (layer.canvas.parentNode) layer.canvas.parentNode.removeChild(layer.canvas);
        layer.v.__lumenDeband = null;
        var i = debandLayers.indexOf(layer);
        if (i >= 0) debandLayers.splice(i, 1);
    }

    // all <video> elements, also inside web components (shadow DOM), which querySelectorAll misses
    function allVideos() {
        var out = Array.prototype.slice.call(document.querySelectorAll('video'));
        var walk = function (root, depth) {
            if (depth > 6) return;
            var all = root.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
                var sr = all[i].shadowRoot;
                if (sr) {
                    out = out.concat(Array.prototype.slice.call(sr.querySelectorAll('video')));
                    walk(sr, depth + 1);
                }
            }
        };
        try { walk(document, 0); } catch (e) {}
        return out;
    }

    var debandScanTimer = 0, debandReportTimer = 0;
    function setDebandLocal(on) {
        on = false; // Lumen: deband removed (external players have their own)
        // TVs: the in-page filter is too much for their graphics chips (and a 4K screen); the app
        // plays the video in its own player with deband instead
        if (on && cfg.tv) { debandReport('tvpage'); on = false; }
        debandWanted = !!on;
        clearInterval(debandScanTimer);
        if (debandWanted) {
            allVideos().forEach(attachDeband);
            // players often create their <video> (or replace it) later
            debandScanTimer = setInterval(function () { allVideos().forEach(attachDeband); }, 1500);
            // a short report from every frame, so the browser can say what it found where
            clearTimeout(debandReportTimer);
            debandReportTimer = setTimeout(function () {
                if (!debandWanted) return;
                var vids = allVideos(), playing = 0, filtered = 0, drm = 0;
                vids.forEach(function (v) {
                    if (!v.paused && v.readyState >= 2) playing++;
                    if (v.__lumenDeband && v.__lumenDeband.drawn) filtered++;
                    if (v.mediaKeys) drm++;
                });
                debandReport('scan:' + JSON.stringify({
                    h: location.host || String(location.href).slice(0, 40), top: isTop,
                    v: vids.length, p: playing, f: filtered, d: drm,
                    gl: !!document.createElement('canvas').getContext('webgl')
                }));
            }, 4000);
        } else {
            debandLayers.slice().forEach(detachDeband);
        }
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
            try { frames[i].contentWindow.postMessage({ __sofa: 'deband', on: debandWanted }, '*'); } catch (e) {}
        }
        return document.querySelectorAll('video').length;
    }

    // (deband removed: websites' own full screen is no longer wrapped)

    var origSetDebandLocal = setDebandLocal;
    setDebandLocal = function (on) {
        var n = origSetDebandLocal(on);
        if (on) {
            var fs = document.fullscreenElement || document.webkitFullscreenElement;
            if (fs && fs.tagName === 'VIDEO') debandReport('fsvideo');
        }
        return n;
    };
    window.__lumenSetDeband = setDebandLocal;

    // ---- automatic deband ----
    // 1. Sites set to "always use deband" (top page): on from the start.
    if (false && isTop && cfg.deband && !cfg.tv) {
        setDebandLocal(true);
        debandReport('auto:site');
    }

    // 2. Interlacing lines: every 1.5 s a 256×192 piece from the middle of each playing video is
    //    checked for a column-wise brightness oscillation at exactly the line spacing interlacing
    //    produces for this video height (2 lines, or a 480/576-line master scaled up). Videos
    //    with lines score 14-20 on a real interlaced anime; normal video stays below 3.
    var lineTables = {};
    var lineCanvas = null, lineCtx = null, lineHits = [];
    var lineAmp = function (Y, cols, rows, p) {
        var key = rows + ':' + p, t = lineTables[key];
        if (!t) {
            t = { c: new Float32Array(rows), s: new Float32Array(rows) };
            for (var i = 0; i < rows; i++) { t.c[i] = Math.cos(2 * Math.PI * i / p); t.s[i] = Math.sin(2 * Math.PI * i / p); }
            lineTables[key] = t;
        }
        var total = 0;
        for (var x = 0; x < cols; x++) {
            var I = 0, Q = 0;
            for (var y = 0; y < rows; y++) { var v = Y[y * cols + x]; I += v * t.c[y]; Q += v * t.s[y]; }
            total += Math.sqrt(I * I + Q * Q) / rows;
        }
        return total / cols;
    };
    var lineScore = function (v) {
        var W = v.videoWidth, H = v.videoHeight;
        var cw = Math.min(256, W), ch = Math.min(192, H);
        if (!lineCanvas) {
            lineCanvas = document.createElement('canvas');
            lineCtx = lineCanvas.getContext('2d', { willReadFrequently: true });
        }
        if (!lineCtx) return 0;
        lineCanvas.width = cw; lineCanvas.height = ch;
        lineCtx.drawImage(v, Math.floor((W - cw) / 2), Math.floor((H - ch) / 2), cw, ch, 0, 0, cw, ch);
        var d = lineCtx.getImageData(0, 0, cw, ch).data; // throws for videos pages can't read
        var cols = Math.floor(cw / 2), Y = new Float32Array(cols * ch);
        for (var x = 0; x < cols; x++) {
            var sum = 0;
            for (var y = 0; y < ch; y++) {
                var i = (y * cw + x * 2) * 4;
                var l = 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
                Y[y * cols + x] = l; sum += l;
            }
            var mean = sum / ch;
            for (var y2 = 0; y2 < ch; y2++) Y[y2 * cols + x] -= mean;
        }
        var periods = [2, 2 * H / 576, 2 * H / 480].map(function (q) { return Math.max(2, q); });
        var best = 0;
        periods.forEach(function (p) {
            var a = lineAmp(Y, cols, ch, p);
            var ref = p * 0.72 >= 2 ? (lineAmp(Y, cols, ch, p * 1.4) + lineAmp(Y, cols, ch, p * 0.72)) / 2 : lineAmp(Y, cols, ch, p * 1.4);
            if (a > 1.0) best = Math.max(best, a / (ref + 0.05));
        });
        return best;
    };
    window.__lumenLineScore = lineScore;
    if (false && cfg.autoLines && !cfg.tv) {
        setInterval(function () {
            if (debandWanted || document.hidden) return;
            var vids = allVideos();
            for (var k = 0; k < vids.length; k++) {
                var v = vids[k];
                if (v.paused || v.readyState < 2 || v.videoWidth < 320 || v.mediaKeys || v.__lumenNoRead) continue;
                var score = 0;
                try { score = lineScore(v); } catch (e) { v.__lumenNoRead = true; continue; }
                var now = Date.now();
                lineHits = lineHits.filter(function (t) { return now - t < 60000; });
                if (score > 10 || (score > 6 && lineHits.length > 0)) {
                    setDebandLocal(true);
                    debandReport('auto:lines');
                    return;
                }
                if (score > 6) lineHits.push(now);
            }
        }, 1500);
    }
    window.addEventListener('message', function (e) {
        if (!e.data) return;
        if (e.data.__sofa === 'deband') setDebandLocal(e.data.on);
        // a frame that loaded after deband was switched on (players often load their frame on
        // "play") asks its parent for the current state
        if (e.data.__sofa === 'deband?' && debandWanted && e.source) {
            try { e.source.postMessage({ __sofa: 'deband', on: true }, '*'); } catch (err) {}
        }
    });
    if (!isTop) {
        var askDeband = function () {
            try { window.parent.postMessage({ __sofa: 'deband?' }, '*'); } catch (e) {}
        };
        askDeband();
        onReady(askDeband);
        document.addEventListener('play', function () { if (!debandWanted) askDeband(); }, true);
    }
    document.addEventListener('play', function (e) {
        if (debandWanted && e.target && e.target.tagName === 'VIDEO') attachDeband(e.target);
    }, true);
    // a real video (not a muted preview/ad loop) started: the app hides its top bar
    var lastVideoReport = 0;
    document.addEventListener('playing', function (e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO' || v.muted || (v.duration && v.duration < 30)) return;
        var r = v.getBoundingClientRect();
        if (r.width < 200 || r.height < 120) return;
        if (Date.now() - lastVideoReport < 5000) return;
        lastVideoReport = Date.now();
        try { TVBro.sofaVideoStarted(); } catch (err) {}
    }, true);

    // ---------------- passwords ----------------
    function visible(el) {
        if (!el) return false;
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
    }

    function findUserField(pass) {
        var scope = pass.form || document;
        var inputs = Array.prototype.slice.call(scope.querySelectorAll(
            'input[type=email], input[type=text], input[type=tel], input:not([type]), input[autocomplete=username]'));
        var before = inputs.filter(function (i) {
            return visible(i) && (pass.compareDocumentPosition(i) & Node.DOCUMENT_POSITION_PRECEDING);
        });
        var pick = before.length ? before[before.length - 1] : inputs.filter(visible)[0];
        return pick || null;
    }

    // sign-up / new-password fields (not the password of a normal login)
    function isSignupField(p) {
        if (!p || p.type !== 'password') return false;
        var ac = (p.getAttribute('autocomplete') || '').toLowerCase();
        if (ac.indexOf('new-password') >= 0) return true;
        if (ac.indexOf('current-password') >= 0) return false;
        var scope = p.form || document;
        var others = Array.prototype.slice.call(scope.querySelectorAll('input[type=password]')).filter(visible);
        if (others.length >= 2) return true; // password + repeat password
        var f = p.form;
        // the page address only counts when it has just this one password form (not login + sign-up)
        var pwForms = 0;
        Array.prototype.forEach.call(document.forms, function (form) {
            if (form.querySelector('input[type=password]')) pwForms++;
        });
        var hint = [p.name, p.id, p.placeholder, p.getAttribute('aria-label'),
            f && f.id, f && f.className, f && f.getAttribute('action'),
            pwForms <= 1 ? location.pathname : ''].join(' ').toLowerCase();
        return /regist|sign.?up|create|new.?pass|join|confirm|repeat|retype/.test(hint);
    }

    function findSignupUser(scope) {
        scope = scope || document;
        var sel = ['input[autocomplete~=username]', 'input[autocomplete~=email]', 'input[type=email]',
            'input[name*=email i]', 'input[id*=email i]', 'input[name*=user i]', 'input[id*=user i]',
            'input[name*=login i]', 'input[id*=login i]'];
        for (var i = 0; i < sel.length; i++) {
            var list = Array.prototype.slice.call(scope.querySelectorAll(sel[i])).filter(function (el) {
                return visible(el) && el.type !== 'password' && el.type !== 'hidden' && el.value;
            });
            if (list.length) return list[0];
        }
        return null;
    }

    function strongPassword() {
        var sets = ['ABCDEFGHJKLMNPQRSTUVWXYZ', 'abcdefghijkmnopqrstuvwxyz', '23456789', '!@#$%&*-_=+?'];
        var all = sets.join('');
        var r = new Uint32Array(32);
        crypto.getRandomValues(r);
        var out = [];
        for (var i = 0; i < 16; i++) {
            var set = i < sets.length ? sets[i] : all; // at least one of every kind
            out.push(set.charAt(r[i] % set.length));
        }
        for (var j = out.length - 1; j > 0; j--) { // shuffle
            var k = r[16 + j] % (j + 1);
            var t = out[j]; out[j] = out[k]; out[k] = t;
        }
        return out.join('');
    }

    function setFieldValue(el, value) {
        var setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
        if (setter && setter.set) setter.set.call(el, value); else el.value = value;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function autoSave(user, pass) {
        try { TVBro.sofaAutoSaveLogin(user || '', pass); } catch (e) {}
    }

    // "Use a strong password" suggestion under sign-up password fields
    var suggestion = null;
    function hideSuggestion() {
        if (suggestion) { suggestion.host.remove(); suggestion = null; }
    }
    function showSuggestion(field) {
        if (suggestion && suggestion.field === field) return;
        hideSuggestion();
        var pw = strongPassword();
        var host = document.createElement('lumen-password');
        host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;left:0;top:0';
        var root = host.attachShadow({ mode: 'closed' });
        root.innerHTML = '<style>' +
            '.b{font:15px/1.3 system-ui,sans-serif;background:#0f1d20;color:#e8f5f2;border-radius:12px;' +
            'box-shadow:0 6px 24px rgba(0,0,0,.45);padding:10px 12px;display:flex;align-items:center;gap:10px;max-width:92vw}' +
            '.t{display:flex;flex-direction:column;min-width:0}.s{font-size:12px;opacity:.75}' +
            '.p{font:600 16px/1.3 ui-monospace,monospace;letter-spacing:.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
            'button{all:unset;cursor:pointer;border-radius:9px;padding:9px 14px;font:600 15px system-ui,sans-serif}' +
            '.u{background:#20c997;color:#062b2f}.x{opacity:.7;padding:9px 8px}</style>' +
            '<div class="b"><span>🔑</span><div class="t"><span class="s">Use a strong password (saved for this site)</span>' +
            '<span class="p"></span></div><button class="u">Use</button><button class="x">✕</button></div>';
        root.querySelector('.p').textContent = pw;
        var keep = function (e) { e.preventDefault(); e.stopPropagation(); };
        root.querySelector('.b').addEventListener('mousedown', keep, true);
        root.querySelector('.b').addEventListener('touchstart', function (e) { e.stopPropagation(); }, true);
        root.querySelector('.u').addEventListener('click', function (e) {
            keep(e);
            var scope = field.form || document;
            var targets = Array.prototype.slice.call(scope.querySelectorAll('input[type=password]'))
                .filter(function (p) { return visible(p) && isSignupField(p); });
            if (targets.indexOf(field) < 0) targets.unshift(field);
            targets.forEach(function (p) { setFieldValue(p, pw); });
            var user = findSignupUser(scope);
            autoSave(user ? user.value : '', pw);
            hideSuggestion();
        }, true);
        root.querySelector('.x').addEventListener('click', function (e) { keep(e); hideSuggestion(); }, true);
        document.documentElement.appendChild(host);
        suggestion = { host: host, field: field };
        placeSuggestion();
    }
    function placeSuggestion() {
        if (!suggestion) return;
        if (!suggestion.field.isConnected || !visible(suggestion.field)) { hideSuggestion(); return; }
        var r = suggestion.field.getBoundingClientRect();
        var h = suggestion.host.getBoundingClientRect().height || 60;
        var top = r.bottom + 6;
        if (top + h > window.innerHeight - 4) top = Math.max(4, r.top - h - 6);
        suggestion.host.style.left = Math.max(4, Math.min(r.left, window.innerWidth - 320)) + 'px';
        suggestion.host.style.top = top + 'px';
    }

    var lastOffer = 0;
    function captureLogin(scope) {
        if (!cfg.passwords || !isTop || !/^https?:$/.test(location.protocol)) return;
        var passes = Array.prototype.slice.call((scope || document).querySelectorAll('input[type=password]'))
            .filter(function (p) { return p.value; });
        if (!passes.length) return;
        if (Date.now() - lastOffer < 3000) return;
        var signup = passes.length > 1 || isSignupField(passes[0]);
        if (signup) {
            // new account: saved automatically (the new password, not an old one in a change form)
            var newPass = passes.filter(isSignupField).pop() || passes[passes.length - 1];
            var u = findSignupUser(scope) || findUserField(newPass);
            lastOffer = Date.now();
            autoSave(u ? u.value : '', newPass.value);
            return;
        }
        lastOffer = Date.now();
        var pass = passes[0];
        var user = findUserField(pass);
        try { TVBro.sofaOfferSaveLogin(user ? user.value : '', pass.value); } catch (e) {}
    }

    if (isTop && cfg.passwords) {
        document.addEventListener('focusin', function (e) {
            var t = e.target;
            if (t && t.tagName === 'INPUT' && isSignupField(t) && !t.value && /^https?:$/.test(location.protocol)) {
                showSuggestion(t);
            } else if (suggestion && t !== suggestion.field) {
                hideSuggestion();
            }
        }, true);
        document.addEventListener('input', function (e) {
            // typing an own password: the suggestion goes away
            if (suggestion && e.target === suggestion.field && e.isTrusted) hideSuggestion();
        }, true);
        window.addEventListener('scroll', placeSuggestion, true);
        window.addEventListener('resize', placeSuggestion);
    }

    if (isTop) {
        document.addEventListener('submit', function (e) { captureLogin(e.target); }, true);
        document.addEventListener('click', function (e) {
            var b = e.target && e.target.closest ? e.target.closest('button, input[type=submit], [role=button]') : null;
            if (!b) return;
            var form = b.form || b.closest('form');
            setTimeout(function () { captureLogin(form || document); }, 0);
        }, true);
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && e.target && e.target.type === 'password') captureLogin(e.target.form || document);
        }, true);

        // Called by the app on pages of a site with saved logins (usernames only). As soon as a
        // login form shows up, a small "Sign in as…" box asks which account to fill in.
        var loginBox = null, loginOffer = null, loginDismissed = false;
        function hideLoginBox() { if (loginBox) { loginBox.host.remove(); loginBox = null; } }
        function loginTarget() {
            var pass = Array.prototype.slice.call(document.querySelectorAll('input[type=password]'))
                .filter(function (p) { return visible(p) && !isSignupField(p); })[0];
            if (pass) return pass.value ? null : pass;
            // two-step logins: the username page comes first
            var u = Array.prototype.slice.call(document.querySelectorAll(
                'input[autocomplete~=username], input[type=email], input[name*=user i], input[name*=login i], input[id*=email i], input[name*=email i]'))
                .filter(function (el) { return visible(el) && el.type !== 'hidden' && !el.value; })[0];
            return u || null;
        }
        function placeLoginBox() {
            if (!loginBox) return;
            var f = loginBox.field;
            if (!f.isConnected || !visible(f)) { hideLoginBox(); return; }
            var r = f.getBoundingClientRect();
            var h = loginBox.host.getBoundingClientRect().height || 60;
            var top = r.bottom + 6;
            if (top + h > window.innerHeight - 4) top = Math.max(4, r.top - h - 6);
            loginBox.host.style.left = Math.max(4, Math.min(r.left, window.innerWidth - 340)) + 'px';
            loginBox.host.style.top = top + 'px';
        }
        function showLoginBox(field) {
            if (!loginOffer || loginDismissed) return;
            if (loginBox && loginBox.field === field) return;
            hideLoginBox();
            var host = document.createElement('lumen-login');
            host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;left:0;top:0';
            var root = host.attachShadow({ mode: 'closed' });
            var dark = !window.matchMedia || window.matchMedia('(prefers-color-scheme: dark)').matches;
            // Wisp: neutral colours like the rest of the app (Lumen keeps its TV colours)
            var C = !cfg.phone ? { bg: '#0f1d20', fg: '#e8f5f2', btn: '#173236', hi: '#20c997', hiFg: '#062b2f' }
                : dark ? { bg: '#1F1F21', fg: '#EDEDEF', btn: '#2A2A2D', hi: '#E4E4E7', hiFg: '#18181A' }
                : { bg: '#FFFFFF', fg: '#1A1A1C', btn: '#F0F0F2', hi: '#2C2C30', hiFg: '#FFFFFF' };
            root.innerHTML = '<style>' +
                '.b{font:15px/1.3 system-ui,sans-serif;background:' + C.bg + ';color:' + C.fg + ';border-radius:12px;' +
                'box-shadow:0 6px 24px rgba(0,0,0,.45);padding:10px 12px;display:flex;flex-direction:column;gap:6px;max-width:92vw;min-width:240px}' +
                '.h{display:flex;align-items:center;gap:8px;font-size:13px;opacity:.8}.h span{flex:1}' +
                'button{all:unset;cursor:pointer;border-radius:9px;padding:9px 12px;font:600 15px system-ui,sans-serif}' +
                '.a{background:' + C.btn + ';display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}' +
                '.a:hover,.a:focus{background:' + C.hi + ';color:' + C.hiFg + '}.x{opacity:.7;padding:4px 8px}</style>' +
                '<div class="b"><div class="h"><span></span><button class="x">✕</button></div></div>';
            root.querySelector('.h span').textContent = '🔑  Fill in a saved login for ' + loginOffer.site;
            var keep = function (e) { e.preventDefault(); e.stopPropagation(); };
            var box = root.querySelector('.b');
            box.addEventListener('mousedown', keep, true);
            box.addEventListener('touchstart', function (e) { e.stopPropagation(); }, true);
            loginOffer.names.slice(0, 5).forEach(function (name, i) {
                var b = document.createElement('button');
                b.className = 'a';
                b.textContent = '👤  ' + (name || '(no username)');
                b.addEventListener('click', function (e) {
                    keep(e);
                    hideLoginBox();
                    loginOffer = null;
                    try { TVBro.sofaFillLogin(i); } catch (x) {}
                }, true);
                box.appendChild(b);
            });
            root.querySelector('.x').addEventListener('click', function (e) {
                keep(e); loginDismissed = true; hideLoginBox();
            }, true);
            document.documentElement.appendChild(host);
            loginBox = { host: host, field: field };
            placeLoginBox();
        }
        var loginTimer = 0;
        var autoFilled = false;
        function checkLoginForm() {
            loginTimer = 0;
            if (!loginOffer || loginDismissed) return;
            var t = loginTarget();
            // Wisp: one saved account for the site: filled in by itself as soon as the form is there
            // (two-step logins: the password page is filled too, __sofaFill keeps watching)
            if (t && cfg.phone && loginOffer.names.length === 1 && !autoFilled) {
                autoFilled = true;
                hideLoginBox();
                try { TVBro.sofaFillLogin(0); } catch (x) {}
                return;
            }
            if (t && autoFilled && loginOffer.names.length === 1) return;
            if (t) showLoginBox(t); else hideLoginBox();
        }
        window.__sofaOfferLogins = function (site, names) {
            if (!names || !names.length) return;
            loginOffer = { site: site, names: names };
            loginDismissed = false;
            checkLoginForm();
            if (!window.__sofaLoginObs) {
                // login dialogs often appear later: look again (at most every 700 ms) for a minute
                var obs = new MutationObserver(function () {
                    if (!loginTimer && loginOffer) loginTimer = setTimeout(checkLoginForm, 700);
                });
                obs.observe(document.documentElement, { childList: true, subtree: true });
                setTimeout(function () { obs.disconnect(); }, 60000);
                window.__sofaLoginObs = obs;
                window.addEventListener('scroll', placeLoginBox, true);
                window.addEventListener('resize', placeLoginBox);
            }
        };
        document.addEventListener('focusin', function (e) {
            var t = e.target;
            if (!loginOffer || !t || t.tagName !== 'INPUT') return;
            loginDismissed = false; // focusing a login field brings the box back
            var lt = loginTarget();
            if (lt) showLoginBox(t === lt || t.type === 'password' ? t : lt);
        }, true);

        // Called by the app with the account the user picked. Fills visible fields,
        // also ones that appear later (sign-in dialogs), for up to one minute.
        window.__sofaFill = function (username, password) {
            var done = false;
            var setValue = function (el, value) {
                var proto = Object.getPrototypeOf(el);
                var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                if (setter && setter.set) setter.set.call(el, value); else el.value = value;
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            };
            var tryFill = function () {
                if (done) return;
                var pass = Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(visible)[0];
                var user = null;
                if (!pass) {
                    // two-step logins: username page first
                    user = document.querySelector('input[autocomplete=username], input[type=email], input[name*=user i], input[name*=login i], input[id*=email i]');
                    if (user && visible(user) && !user.value && username) setValue(user, username);
                    return;
                }
                user = findUserField(pass);
                if (user && !user.value && username) setValue(user, username);
                if (!pass.value) setValue(pass, password);
                done = true;
                observer.disconnect();
            };
            var observer = new MutationObserver(tryFill);
            observer.observe(document.documentElement, { childList: true, subtree: true });
            setTimeout(function () { observer.disconnect(); }, 60000);
            tryFill();
        };
    }

    // ---------------- tall player menus: keep them on screen ----------------
    // Player settings menus are made for desktop screens. On a TV (and under the app's top bar)
    // a tall one sticks out above the top edge, where it can't be reached. After a click, such
    // menus inside a player get a maximum height with their own scrolling, and are moved down.
    function playerRoots() {
        var fs = document.fullscreenElement || document.webkitFullscreenElement;
        if (fs) return [fs];
        var roots = [];
        allVideos().forEach(function (v) {
            var vr = v.getBoundingClientRect();
            if (vr.width < 200 || vr.height < 120) return;
            var root = v.parentElement, up = 0, best = root;
            while (root && root !== document.body && up < 6) {
                var r = root.getBoundingClientRect();
                if (r.width * r.height > vr.width * vr.height * 1.6) break;
                best = root;
                root = root.parentElement;
                up++;
            }
            if (best && roots.indexOf(best) < 0) roots.push(best);
        });
        return roots;
    }
    var fitted = [];
    // puts back what fitTallMenus changed (the screen size changed: menus are fitted again on the next click)
    function unfitMenus() {
        fitted.forEach(function (f) {
            try {
                ['max-height', 'overflow-y', 'transform'].forEach(function (k) {
                    if (f.saved[k][0]) f.el.style.setProperty(k, f.saved[k][0], f.saved[k][1]);
                    else f.el.style.removeProperty(k);
                });
            } catch (e) {}
        });
        fitted = [];
    }
    function fitTallMenus() {
        var vh = window.innerHeight;
        playerRoots().forEach(function (root) {
            var rootRect = root.getBoundingClientRect();
            var els = root.querySelectorAll('*');
            if (els.length > 3000) return;
            for (var i = 0; i < els.length; i++) {
                var el = els[i];
                if (el.tagName === 'VIDEO' || el.tagName === 'CANVAS' || el.tagName === 'IFRAME') continue;
                if (fitted.some(function (f) { return f.el === el; })) continue;
                var r = el.getBoundingClientRect();
                if (r.top >= -1 || r.bottom < 60 || r.height < 80 || r.width < 60) continue;
                // a layer as wide as the player (the video's own frame, an overlay) is not a menu
                if (r.width >= rootRect.width * 0.8 || r.width >= window.innerWidth * 0.8) continue;
                // never anything that holds the picture
                if (el.querySelector('video, canvas, iframe, img[src*="poster" i]')) continue;
                var cs = getComputedStyle(el);
                if (cs.position !== 'absolute' && cs.position !== 'fixed') continue;
                if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
                // a menu: several entries to choose from
                if (el.querySelectorAll('li, button, [role=menuitem], [role=option], [role=menuitemradio], a').length < 3) continue;
                var saved = {};
                ['max-height', 'overflow-y', 'transform'].forEach(function (k) {
                    saved[k] = [el.style.getPropertyValue(k), el.style.getPropertyPriority(k)];
                });
                fitted.push({ el: el, saved: saved });
                var maxH = Math.max(80, Math.min(r.height, r.bottom - 8, vh - 16));
                el.style.setProperty('max-height', Math.floor(maxH) + 'px', 'important');
                el.style.setProperty('overflow-y', 'auto', 'important');
                var r2 = el.getBoundingClientRect();
                if (r2.top < 0) {
                    var t = cs.transform && cs.transform !== 'none' ? cs.transform + ' ' : '';
                    el.style.setProperty('transform', t + 'translateY(' + Math.ceil(8 - r2.top) + 'px)', 'important');
                }
            }
        });
    }
    var scheduleFit = function () {
        [150, 450, 1000].forEach(function (d) { setTimeout(function () { try { fitTallMenus(); } catch (e) {} }, d); });
    };
    document.addEventListener('click', function (e) { if (e.isTrusted) scheduleFit(); }, true);
    document.addEventListener('keyup', function (e) { if (e.isTrusted && /Enter| /.test(e.key)) scheduleFit(); }, true);
    // entering / leaving full screen: the layout is moving, nothing is fitted then
    document.addEventListener('fullscreenchange', unfitMenus);
    document.addEventListener('webkitfullscreenchange', unfitMenus);
    window.addEventListener('resize', unfitMenus);

    // ---------------- video drawn by the browser (Site fixes) ----------------
    // Normally a playing video goes straight to the TV's hardware video layer. On some TVs that
    // layer breaks certain videos (old interlaced anime: blocks, coloured squares) that the same
    // chip decodes fine in a player app. An invisible CSS filter makes the browser draw the
    // video itself, through the graphics chip, like a picture on the page.
    if (cfg.videoCompat) {
        var COMPAT_CSS = 'filter:brightness(1.0001)!important;';
        onReady(function () {
            addStyle('lumen-video-compat', 'video { ' + COMPAT_CSS + ' }');
        });
        var compatVideo = function (v) {
            if (v && v.tagName === 'VIDEO' && !v.__lumenCompat) {
                v.__lumenCompat = true;
                try { v.style.setProperty('filter', 'brightness(1.0001)', 'important'); } catch (e) {}
            }
        };
        // also videos inside shadow DOM (the page's style sheet doesn't reach them)
        ['loadedmetadata', 'playing'].forEach(function (ev) {
            document.addEventListener(ev, function (e) { compatVideo(e.target); }, true);
        });
    }

    // ---------------- inverse telecine / deinterlacing in the page (Site fixes) ----------------
    // A port of Just Player URL+'s automatic IVTC (Ivtc.java): the same idet measurement, 3:2
    // cadence analysis and decision, with the FFmpeg filters rebuilt as WebGL shaders:
    //   pullup / fieldmatch -> field matching against the previous frame's fields (GPU, per frame)
    //   bwdif               -> motion-adaptive, edge-directed interpolation of combed pixels only
    //   decimate            -> not possible in a page (the browser times the frames)
    // Made for weak TV chips: everything runs at the video's own size (the browser scales the
    // result like any picture), once per video frame, nothing is read back while filtering, and
    // only a 4 KB summary per frame while measuring (~12 s). Too slow: lighter mode, then off.
    if (cfg.ivtc && /^(auto|ivtc|mixed|deint)$/.test(cfg.ivtc)) (function () {
        var WANT = cfg.ivtc;
        var VS = 'attribute vec2 aPos; varying vec2 vUV;\n' +
            'void main(){ vUV = vec2(aPos.x*0.5+0.5, 0.5-aPos.y*0.5); gl_Position = vec4(aPos,0.0,1.0); }';
        var HEAD = 'precision highp float;\nvarying vec2 vUV;\n' +
            'uniform sampler2D uA; uniform sampler2D uB; uniform sampler2D uC;\n' +
            'uniform vec2 uSize; uniform vec2 uBlocks;\n' +
            'float lum(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n' +
            'float Y(sampler2D t, float x, float l){ return lum(texture2D(t, vec2((x+0.5)/uSize.x, (l+0.5)/uSize.y)).rgb); }\n' +
            'vec2 pack(float v){ v = clamp(v, 0.0, 0.99998); float h = floor(v*255.0); return vec2(h/255.0, v*255.0-h); }\n';
        // idet: replace each line by the previous / next / same frame's line and measure
        // |above + below - 2*middle|. uWhich 0 -> (alpha0, alpha1), 1 -> (delta)
        var DETECT_FS = HEAD + 'uniform float uWhich;\n' +
            'void main(){\n' +
            '  vec2 b = floor(gl_FragCoord.xy); float a0 = 0.0, a1 = 0.0, d = 0.0, n = 0.0;\n' +
            '  for (int j = 0; j < 16; j++) { for (int i = 0; i < 8; i++) {\n' +
            '    float x = b.x*16.0 + float(i)*2.0; float l = b.y*16.0 + float(j);\n' +
            '    if (x >= uSize.x || l < 2.0 || l > uSize.y - 3.0) continue;\n' +
            '    float a = Y(uB, x, l-1.0), c = Y(uB, x, l+1.0), s = a + c;\n' +
            '    float fp = abs(s - 2.0*Y(uA, x, l)), fn = abs(s - 2.0*Y(uC, x, l));\n' +
            '    if (mod(l, 2.0) < 0.5) { a0 += fp; a1 += fn; } else { a1 += fp; a0 += fn; }\n' +
            '    d += abs(s - 2.0*Y(uB, x, l)); n += 1.0;\n' +
            '  } }\n' +
            '  n = max(n, 1.0) * 2.0;\n' +
            '  gl_FragColor = uWhich < 0.5 ? vec4(pack(a0/n), pack(a1/n)) : vec4(pack(d/n), pack(n/512.0));\n' +
            '}';
        // field matching scores per 16x16 block: combed share of c (current frame), pT (current
        // top field + previous bottom field) and pB (current bottom + previous top)
        var MATCH_FS = HEAD + 'uniform float uSkip; uniform float uThr;\n' +
            'float comb(float a, float b, float c){ float d1 = a-b, d2 = c-b; return (d1*d2 > 0.0 && min(abs(d1),abs(d2)) > uThr) ? 1.0 : 0.0; }\n' +
            'void main(){\n' +
            '  vec2 b = floor(gl_FragCoord.xy); float nc = 0.0, nt = 0.0, nb = 0.0, n = 0.0;\n' +
            '  for (int j = 0; j < 8; j++) { for (int i = 0; i < 8; i++) {\n' +
            '    if (uSkip > 0.5 && (mod(float(i), 2.0) > 0.5 || mod(float(j), 2.0) > 0.5)) continue;\n' +
            '    float x = b.x*16.0 + float(i)*2.0; float l = b.y*16.0 + float(j)*2.0 + 1.0;\n' +
            '    if (x >= uSize.x || l + 2.0 >= uSize.y) continue;\n' +
            '    float c0 = Y(uB,x,l-1.0), c1 = Y(uB,x,l), c2 = Y(uB,x,l+1.0), c3 = Y(uB,x,l+2.0);\n' +
            '    float p0 = Y(uA,x,l-1.0), p1 = Y(uA,x,l), p2 = Y(uA,x,l+1.0), p3 = Y(uA,x,l+2.0);\n' +
            // combing alternates over lines: two neighbouring lines both out of line (a thin
            // horizontal detail moves only one) - fewer false hits on fine drawings
            '    nc += comb(c0,c1,c2) * comb(c1,c2,c3);\n' +
            '    nt += comb(c0,p1,c2) * comb(p1,c2,p3);\n' +
            '    nb += comb(p0,c1,p2) * comb(c1,p2,c3);\n' +
            '    n += 1.0;\n' +
            '  } }\n' +
            '  n = max(n, 1.0);\n' +
            '  gl_FragColor = vec4(min(vec3(nc, nt, nb) / n * 4.0, 1.0), 1.0);\n' +
            '}';
        // block scores -> one value per column -> one value for the frame (all on the GPU)
        var COLS_FS = 'precision highp float;\nuniform sampler2D uA; uniform vec2 uBlocks;\n' +
            'void main(){ float x = floor(gl_FragCoord.x); vec4 s = vec4(0.0);\n' +
            '  for (int r = 0; r < 160; r++) { if (float(r) >= uBlocks.y) break; s += texture2D(uA, vec2((x+0.5)/uBlocks.x, (float(r)+0.5)/uBlocks.y)); }\n' +
            '  gl_FragColor = s / uBlocks.y; }';
        var GLOBAL_FS = 'precision highp float;\nuniform sampler2D uA; uniform vec2 uBlocks;\n' +
            'void main(){ vec4 s = vec4(0.0);\n' +
            '  for (int c = 0; c < 256; c++) { if (float(c) >= uBlocks.x) break; s += texture2D(uA, vec2((float(c)+0.5)/uBlocks.x, 0.5)); }\n' +
            '  gl_FragColor = s / uBlocks.x; }';
        // the picture: the best field match, then combed pixels that are left (interlaced parts,
        // scene changes, deint mode) interpolated from the kept field along the best edge direction
        var OUT_FS = HEAD + 'uniform sampler2D uBlk; uniform sampler2D uG; uniform float uMode; uniform float uLight;\n' +
            'uniform float uResid; uniform float uThr2;\n' +
            'void main(){\n' +
            '  float x = floor(vUV.x*uSize.x), l = floor(vUV.y*uSize.y);\n' +
            '  vec2 px = vec2(1.0/uSize.x, 1.0/uSize.y); vec2 uv = (vec2(x, l) + 0.5) * px;\n' +
            '  vec4 g = texture2D(uG, vec2(0.5));\n' +
            '  float cand = 0.0;\n' +
            '  if (uMode < 2.5) { float best = min(g.g, g.b); if (best < g.r*0.8 && g.r - best > 0.008) cand = g.g <= g.b ? 1.0 : 2.0; }\n' +
            '  bool keptEven = cand < 1.5;\n' +
            '  bool kept = (mod(l, 2.0) < 0.5) == keptEven;\n' +
            '  vec3 col = texture2D(uB, uv).rgb;\n' +
            '  if (!kept) {\n' +
            '    if (cand > 0.5) col = texture2D(uA, uv).rgb;\n' +
            '    if (l > 0.5 && l < uSize.y - 1.5) {\n' +
            '      vec4 blk = texture2D(uBlk, (floor(vec2(x, l) / 16.0) + 0.5) / uBlocks);\n' +
            '      float local = cand < 0.5 ? blk.r : (cand < 1.5 ? blk.g : blk.b);\n' +
            '      if (uMode > 2.5 || local > uResid) {\n' +
            '        vec3 a = texture2D(uB, uv - vec2(0.0, px.y)).rgb, d = texture2D(uB, uv + vec2(0.0, px.y)).rgb;\n' +
            '        float la = lum(a), lb = lum(col), ld = lum(d);\n' +
            // combed = this line and a neighbouring kept line both stick out (alternating lines);
            // a single thin line of the drawing doesn't count
            '        vec2 o2 = vec2(0.0, 2.0*px.y);\n' +
            '        vec3 b2 = cand > 0.5 ? texture2D(uA, uv + o2).rgb : texture2D(uB, uv + o2).rgb;\n' +
            '        vec3 b0 = cand > 0.5 ? texture2D(uA, uv - o2).rgb : texture2D(uB, uv - o2).rgb;\n' +
            '        float l2 = lum(b2), l0 = lum(b0);\n' +
            '        bool fix = (la-lb)*(ld-lb) > uThr2 && ((lb-ld)*(l2-ld) > uThr2 || (lb-la)*(l0-la) > uThr2);\n' +
            // deinterlacing (bwdif-like): a line of the other field that moved since the previous
            // frame is rebuilt from the kept field; still parts keep their full detail
            '        if (cand < 0.5 && uMode > 2.5) {\n' +
            '          float mv = max(abs(lb - lum(texture2D(uA, uv).rgb)), max(abs(la - lum(texture2D(uA, uv - vec2(0.0, px.y)).rgb)), abs(ld - lum(texture2D(uA, uv + vec2(0.0, px.y)).rgb))));\n' +
            '          fix = mv > 0.02;\n' +
            '        }\n' +
            '        if (fix) {\n' +
            '          vec3 s = (a + d) * 0.5;\n' +
            '          if (uLight < 0.5) {\n' +
            '            vec3 a1 = texture2D(uB, uv + vec2(px.x, -px.y)).rgb, d1 = texture2D(uB, uv + vec2(-px.x, px.y)).rgb;\n' +
            '            vec3 a2 = texture2D(uB, uv + vec2(-px.x, -px.y)).rgb, d2 = texture2D(uB, uv + vec2(px.x, px.y)).rgb;\n' +
            '            float e0 = abs(la - ld), e1 = abs(lum(a1) - lum(d1)), e2 = abs(lum(a2) - lum(d2));\n' +
            '            if (e1 + 0.03 < e0 && e1 <= e2) s = (a1 + d1) * 0.5; else if (e2 + 0.03 < e0) s = (a2 + d2) * 0.5;\n' +
            // never brighter or darker than the lines above and below (no new stripes)
            '            s = clamp(s, min(a, d), max(a, d));\n' +
            '          }\n' +
            '          col = s;\n' +
            '        }\n' +
            '      }\n' +
            '    }\n' +
            '  }\n' +
            '  gl_FragColor = vec4(col, 1.0);\n' +
            '}';

        function makeEngine(gl) {
            var compile = function (type, src) {
                var s = gl.createShader(type);
                gl.shaderSource(s, src); gl.compileShader(s);
                if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
                return s;
            };
            var vs = compile(gl.VERTEX_SHADER, VS);
            var prog = function (fs) {
                var p = gl.createProgram();
                gl.attachShader(p, vs); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
                gl.bindAttribLocation(p, 0, 'aPos'); gl.linkProgram(p);
                if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
                var u = {};
                ['uA', 'uB', 'uC', 'uSize', 'uBlocks', 'uWhich', 'uSkip', 'uThr', 'uBlk', 'uG', 'uMode', 'uLight', 'uResid', 'uThr2']
                    .forEach(function (n) { u[n] = gl.getUniformLocation(p, n); });
                return { p: p, u: u };
            };
            var P = { det: prog(DETECT_FS), match: prog(MATCH_FS), cols: prog(COLS_FS), glob: prog(GLOBAL_FS), out: prog(OUT_FS) };
            var quad = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, quad);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
            gl.enableVertexAttribArray(0);
            gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
            var texture = function () {
                var t = gl.createTexture();
                gl.bindTexture(gl.TEXTURE_2D, t);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
                gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
                return t;
            };
            var target = function (w, h) {
                var t = texture();
                gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
                var f = gl.createFramebuffer();
                gl.bindFramebuffer(gl.FRAMEBUFFER, f);
                gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
                return { t: t, f: f, w: w, h: h };
            };
            var frames = [texture(), texture(), texture()];
            var head = -1, count = 0, W = 0, H = 0, bw = 0, bh = 0, T = null;
            var bind = function (unit, tex, loc) {
                gl.activeTexture(gl.TEXTURE0 + unit);
                gl.bindTexture(gl.TEXTURE_2D, tex);
                if (loc) gl.uniform1i(loc, unit);
            };
            var run = function (pr, tgt, setup) {
                gl.useProgram(pr.p);
                gl.bindFramebuffer(gl.FRAMEBUFFER, tgt ? tgt.f : null);
                gl.viewport(0, 0, tgt ? tgt.w : W, tgt ? tgt.h : H);
                if (pr.u.uSize) gl.uniform2f(pr.u.uSize, W, H);
                if (pr.u.uBlocks) gl.uniform2f(pr.u.uBlocks, bw, bh);
                setup(pr.u);
                gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
            };
            var f = function (k) { return frames[((head - k) % 3 + 3) % 3]; };   // 0 = newest
            var buf = null;
            var self = {
                size: function () { return [W, H]; },
                // a new video frame; false when the size changed (start over)
                upload: function (v) {
                    var w = v.videoWidth || v.width, h = v.videoHeight || v.height, resized = false;
                    if (w !== W || h !== H) {
                        W = w; H = h; bw = Math.ceil(W / 16); bh = Math.ceil(H / 16);
                        T = { detA: target(bw, bh), detB: target(bw, bh), blk: target(bw, bh), cols: target(bw, 1), glob: target(1, 1) };
                        buf = new Uint8Array(bw * bh * 4);
                        count = 0; resized = true;
                    }
                    head = (head + 1) % 3;
                    gl.bindTexture(gl.TEXTURE_2D, frames[head]);
                    gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
                    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, v);
                    count++;
                    return !resized;
                },
                // idet for the frame before the newest (it needs the next frame too):
                // [alpha0, alpha1, delta] summed over the picture, or null
                detect: function () {
                    if (count < 3) return null;
                    var sums = [0, 0, 0];
                    [T.detA, T.detB].forEach(function (tg, which) {
                        run(P.det, tg, function (u) {
                            bind(0, f(2), u.uA); bind(1, f(1), u.uB); bind(2, f(0), u.uC);
                            gl.uniform1f(u.uWhich, which);
                        });
                        gl.readPixels(0, 0, bw, bh, gl.RGBA, gl.UNSIGNED_BYTE, buf);
                        for (var i = 0; i < buf.length; i += 4) {
                            var a = (buf[i] + buf[i + 1] / 255) / 255, b = (buf[i + 2] + buf[i + 3] / 255) / 255;
                            if (which === 0) { sums[0] += a; sums[1] += b; } else sums[2] += a;
                        }
                    });
                    return sums;
                },
                // draws the filtered newest frame to the canvas
                render: function (mode, light, test) {
                    if (count < 2) return false;
                    run(P.match, T.blk, function (u) {
                        bind(0, f(1), u.uA); bind(1, f(0), u.uB);
                        gl.uniform1f(u.uSkip, light ? 1 : 0); gl.uniform1f(u.uThr, 0.035);
                    });
                    run(P.cols, T.cols, function (u) { bind(0, T.blk.t, u.uA); });
                    run(P.glob, T.glob, function (u) { bind(0, T.cols.t, u.uA); });
                    if (test && test.scores) {
                        var px = new Uint8Array(4);
                        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
                        test.scores.push([px[0], px[1], px[2]]);
                    }
                    run(P.out, null, function (u) {
                        bind(0, f(1), u.uA); bind(1, f(0), u.uB); bind(2, T.blk.t, u.uBlk); bind(3, T.glob.t, u.uG);
                        gl.uniform1f(u.uMode, mode === 'deint' ? 3 : mode === 'mixed' ? 2 : 1);
                        gl.uniform1f(u.uLight, light ? 1 : 0);
                        gl.uniform1f(u.uResid, test && test.resid !== undefined ? test.resid : mode === 'mixed' ? 0.06 : 0.25);
                        gl.uniform1f(u.uThr2, 0.0009);
                    });
                    return true;
                }
            };
            return self;
        }

        // ---- Ivtc.java: 3:2 cadence over blocks of 30 frames, then the decision ----
        function cadence(p) {
            var c = { telecine: 0, interlaced: 0, unclear: 0, still: 0 };
            for (var s = 0; s + 30 <= p.length; s += 30) {
                var phase = [0, 0, 0, 0, 0], combed = 0, judged = 0;
                for (var i = s; i < s + 30; i++) {
                    var t = p[i] || 0;
                    if (t === 1 || t === 2) { combed++; phase[(i - s) % 5]++; }
                    if (t !== 0) judged++;
                }
                if (judged < 15 || combed < 3) { c.still++; continue; }
                var so = phase.slice().sort(function (a, b) { return a - b; });
                var top2 = so[4] + so[3];
                if (top2 >= combed * 0.8 && combed <= 15) c.telecine++;
                else if (combed >= judged * 0.6 && top2 < combed * 0.6) c.interlaced++;
                else c.unclear++;
            }
            c.motion = c.telecine + c.interlaced + c.unclear;
            return c;
        }
        var near = function (a, b, t) { return Math.abs(a - b) < t; };
        var ntsc = function (r) { return near(r, 29.97, 0.15) || near(r, 30, 0.15) || near(r, 59.94, 0.3); };
        // returns 'off' | 'ivtc' | 'mixed' | 'deint', or null = not enough yet
        function decide(st, isFinal) {
            var pat = [], combed = 0, clean = 0;
            st.frames.forEach(function (e) {
                var idx = Math.round((e.mt - st.t0) * st.fps);
                if (idx >= 0 && idx < 6000) pat[idx] = e.t;
                if (e.t === 1 || e.t === 2) combed++; else if (e.t === 3) clean++;
            });
            for (var i = 0; i < pat.length; i++) if (pat[i] === undefined) pat[i] = 0;
            var total = combed + clean, ratio = total ? combed / total : 0;
            var cad = cadence(pat);
            if (!isFinal && cad.motion < 10) return null;
            if (isFinal && cad.motion < 3 && total < 150 && st.extraRounds < 3) { st.extraRounds++; st.played = 0; return null; }
            st.why = Math.round(ratio * 100) + '% combed, 3:2 cadence in ' + cad.telecine + ', interlaced in ' + cad.interlaced +
                ' of ' + cad.motion + ' moving parts, ' + st.fps.toFixed(3) + ' fps';
            if (ratio < 0.10 && cad.telecine === 0 && cad.interlaced === 0) return 'off';
            var m = Math.max(1, cad.motion), tele = cad.telecine / m, inter = cad.interlaced / m;
            if (ntsc(st.fps)) {
                if (tele >= 0.4 && inter <= 0.2) return 'ivtc';
                if (tele >= 0.25 && inter > 0.2) return 'mixed';
                if (tele >= 0.25) return 'ivtc';
                if (inter >= 0.5) return 'deint';
                return 'ivtc';           // ivtc-telecine_below=1.0: NTSC is always IVTC'd
            }
            return 'deint';
        }

        if (cfg.ivtcDebug) window.__lumenIvtcEngine = makeEngine;
        var NAMES = { ivtc: 'inverse telecine (field matching)', mixed: 'inverse telecine + deinterlacing of interlaced parts',
            deint: 'deinterlacing', off: 'progressive, no filter needed' };
        function note(v, text) {
            console.log('Lumen IVTC: ' + text);
            try {
                var host = v.parentNode || document.body;
                var n = document.createElement('div');
                n.textContent = 'Lumen · ' + text;
                n.style.cssText = 'position:absolute;left:12px;top:12px;z-index:2147483647;background:rgba(15,29,32,.88);' +
                    'color:#e8f5f2;font:500 14px/1.3 system-ui,sans-serif;padding:6px 10px;border-radius:10px;pointer-events:none;max-width:80%';
                host.appendChild(n);
                setTimeout(function () { if (n.parentNode) n.parentNode.removeChild(n); }, 5000);
            } catch (e) {}
        }

        // full screen of the video element alone would hide the filtered picture: its parent goes
        // full screen instead, with the video (and the picture over it) filling it
        ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen'].forEach(function (name) {
            var orig = Element.prototype[name];
            if (typeof orig !== 'function') return;
            Element.prototype[name] = function () {
                if (this.tagName === 'VIDEO' && this.__lumenIvtc && this.__lumenIvtc.showing && this.parentElement) {
                    var host = this.parentElement;
                    host.classList.add('lumen-ivtc-fs');
                    addStyle('lumen-ivtc-fs-style',
                        '.lumen-ivtc-fs:fullscreen,.lumen-ivtc-fs:-webkit-full-screen{background:#000!important;width:100%!important;height:100%!important}' +
                        '.lumen-ivtc-fs:fullscreen>video,.lumen-ivtc-fs:-webkit-full-screen>video{width:100%!important;height:100%!important;' +
                        'max-width:none!important;max-height:none!important;left:0!important;top:0!important;object-fit:contain!important}');
                    return orig.apply(host, arguments);
                }
                return orig.apply(this, arguments);
            };
        });

        var memoryKey = 'lumen-ivtc|' + location.pathname;
        function remembered() { try { return sessionStorage.getItem(memoryKey); } catch (e) { return null; } }
        function remember(m) { try { sessionStorage.setItem(memoryKey, m); } catch (e) {} }

        function attach(v) {
            if (v.__lumenIvtc || !v.parentNode || v.mediaKeys) return;
            var canvas = document.createElement('canvas');
            canvas.className = 'lumen-ivtc-layer';
            canvas.style.cssText = 'position:absolute;pointer-events:none;display:block;margin:0;padding:0;border:0;visibility:hidden;';
            var gl = canvas.getContext('webgl', { alpha: false, antialias: false, depth: false, stencil: false,
                premultipliedAlpha: false, preserveDrawingBuffer: !!cfg.ivtcDebug, powerPreference: 'low-power' });
            if (!gl) { note(v, "this TV's graphics can't run the deinterlacer"); v.__lumenIvtc = { dead: true }; return; }
            try {
                var hp = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
                if (!hp || hp.precision < 16) { note(v, "this TV's graphics can't run the deinterlacer"); v.__lumenIvtc = { dead: true }; return; }
            } catch (e) {}
            var eng;
            try { eng = makeEngine(gl); } catch (e) { note(v, 'deinterlacer error: ' + e.message); v.__lumenIvtc = { dead: true }; return; }
            v.parentNode.insertBefore(canvas, v.nextSibling);
            var st = { mode: null, light: false, showing: false, running: true, frames: [], fps: 0, t0: -1, played: 0,
                lastMt: -1, lastPf: -1, deltas: [], extraRounds: 0, why: '' };
            v.__lumenIvtc = st;
            var saved = remembered();
            if (WANT !== 'auto') st.mode = WANT;
            else if (saved) st.mode = saved;
            if (st.mode && st.mode !== 'off') note(v, NAMES[st.mode] + (WANT === 'auto' ? ' (found earlier)' : ''));
            else if (!st.mode) note(v, 'checking the video for interlacing…');

            var place = function () {
                var cs = getComputedStyle(v);
                canvas.style.left = v.offsetLeft + 'px';
                canvas.style.top = v.offsetTop + 'px';
                canvas.style.width = v.offsetWidth + 'px';
                canvas.style.height = v.offsetHeight + 'px';
                canvas.style.objectFit = cs.objectFit || 'contain';
                canvas.style.objectPosition = cs.objectPosition || '50% 50%';
                canvas.style.zIndex = cs.zIndex === 'auto' ? '' : cs.zIndex;
                canvas.style.transform = cs.transform === 'none' ? '' : cs.transform;
                var shown = st.showing && v.offsetWidth > 0 && cs.display !== 'none' && cs.visibility !== 'hidden' && v.readyState >= 2;
                canvas.style.visibility = shown ? 'visible' : 'hidden';
            };
            var placeTimer = setInterval(place, 500);
            var ro = null;
            try { ro = new ResizeObserver(place); ro.observe(v); } catch (e) {}
            var stop = function (why) {
                st.running = false; st.showing = false;
                clearInterval(placeTimer);
                try { if (ro) ro.disconnect(); } catch (e) {}
                if (canvas.parentNode) canvas.parentNode.removeChild(canvas);
                if (why) note(v, why);
                var lose = gl.getExtension('WEBGL_lose_context');
                if (lose) try { lose.loseContext(); } catch (e) {}
            };
            // too slow for this TV: lighter filter first, then off (never a frozen picture)
            var perf = { since: 0, seen: 0, missed: 0, last: -1 };
            var checkPerf = function (now, meta) {
                if (!meta || typeof meta.presentedFrames !== 'number' || v.paused) { perf.last = -1; return true; }
                if (perf.last >= 0) {
                    var gap = Math.max(0, meta.presentedFrames - perf.last - 1);
                    perf.missed += gap; perf.seen += 1 + gap;
                }
                perf.last = meta.presentedFrames;
                if (!perf.since) perf.since = now;
                if (now - perf.since < 3000) return true;
                var slow = perf.seen > 30 && perf.missed / perf.seen > 0.2;
                perf.since = now; perf.seen = 0; perf.missed = 0;
                if (!slow) return true;
                if (!st.light) { st.light = true; note(v, 'lighter deinterlacing (the TV is busy)'); return true; }
                stop('deinterlacing switched off: this TV can\'t keep up');
                return false;
            };
            var newVideo = function () {
                // another episode / source in the same player: measure again
                st.frames = []; st.t0 = -1; st.played = 0; st.lastMt = -1; st.deltas = []; st.extraRounds = 0;
                if (WANT === 'auto') { st.mode = null; st.showing = false; place(); }
            };
            v.addEventListener('emptied', newVideo);

            var frame = function (now, meta) {
                if (!st.running) return;
                if (st.showing && !checkPerf(now, meta)) return;
                var mt = meta && typeof meta.mediaTime === 'number' ? meta.mediaTime : v.currentTime;
                try {
                    if (v.readyState >= 2 && v.videoWidth) {
                        if (v.videoHeight > 1088) { stop('deinterlacing is for videos up to 1080 lines'); return; }
                        if (!eng.upload(v)) newVideo();
                        if (!st.mode) measure(mt, meta);
                        if (st.mode && st.mode !== 'off') {
                            var s = eng.size();
                            if (canvas.width !== s[0] || canvas.height !== s[1]) { canvas.width = s[0]; canvas.height = s[1]; }
                            if (eng.render(st.mode, st.light) && !st.showing) { st.showing = true; place(); }
                        }
                    }
                } catch (e) {
                    stop(e && e.name === 'SecurityError' ? "this site's video can't be read by the page, so it can't be deinterlaced"
                        : 'deinterlacer error: ' + ((e && e.message) || e));
                    return;
                }
                if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(frame); else requestAnimationFrame(frame);
            };
            // the measuring part (Ivtc.java TICK / decide)
            var measure = function (mt, meta) {
                if (v.paused) { st.lastMt = -1; return; }
                if (st.lastMt >= 0) {
                    var d = mt - st.lastMt;
                    if (d < 0 || d > 1) { st.frames = []; st.t0 = -1; }      // seek: start over
                    else {
                        st.played += d;
                        if (meta && st.lastPf >= 0 && meta.presentedFrames - st.lastPf === 1 && d > 0.005) {
                            st.deltas.push(d);
                            if (st.deltas.length > 120) st.deltas.shift();
                        }
                    }
                }
                st.lastPf = meta ? meta.presentedFrames : -1;
                var prevMt = st.lastMt;
                st.lastMt = mt;
                var sums = eng.detect();
                if (!sums || prevMt < 0) return;
                var a0 = sums[0], a1 = sums[1], dl = sums[2], t = 0;
                if (a0 > 1.04 * a1) t = 1; else if (a1 > 1.04 * a0) t = 2; else if (a1 > 1.5 * dl) t = 3;
                if (st.t0 < 0) st.t0 = prevMt;
                st.frames.push({ mt: prevMt, t: t });       // the frame before the newest was measured
                if (st.deltas.length >= 20) {
                    var ds = st.deltas.slice().sort(function (a, b) { return a - b; });
                    st.fps = 1 / ds[ds.length >> 1];
                }
                if (!st.fps || st.frames.length % 30) return;
                var res = null;
                if (st.played >= 12) res = decide(st, true);
                else if (st.played >= 4) res = decide(st, false);
                if (!res) return;
                st.mode = res;
                remember(res);
                note(v, NAMES[res] + ' (' + st.why + ')');
                if (res === 'off') stop();
            };
            if (cfg.ivtcDebug) window.__lumenIvtcState = st;
            frame();
        }

        document.addEventListener('playing', function (e) {
            var v = e.target;
            if (!v || v.tagName !== 'VIDEO' || v.__lumenIvtc) return;
            var r = v.getBoundingClientRect();
            if (r.width < 320 || r.height < 180) return;    // not small previews and ads
            attach(v);
        }, true);
    })();

    // ---------------- site mini-players ----------------
    // Some sites keep the video playing in a small floating window after you leave its page
    // (Back inside the site). On a TV that window can't be used well and makes the whole page
    // stutter. When the page address changes while the main video keeps playing small and
    // floating, it is paused and its window closed.
    if (isTop && cfg.stopMini !== false) {
        var mainVideos = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
        document.addEventListener('playing', function (e) {
            var v = e.target;
            if (!mainVideos || !v || v.tagName !== 'VIDEO' || v.muted) return;
            var r = v.getBoundingClientRect();
            // the main video: big on the page, long, with sound (not a muted trailer)
            if (r.width >= innerWidth * 0.5 && (!v.duration || v.duration > 120)) mainVideos.add(v);
        }, true);
        var floatingBox = function (v) {
            for (var n = v.parentElement, d = 0; n && n !== document.body && d < 10; n = n.parentElement, d++) {
                var st = getComputedStyle(n);
                if (st.position === 'fixed' || st.position === 'sticky') return n;
            }
            return null;
        };
        var closeMini = function () {
            if (!mainVideos) return;
            allVideos().forEach(function (v) {
                if (!mainVideos.has(v) || v.paused) return;
                var r = v.getBoundingClientRect();
                if (r.width === 0 || r.width >= innerWidth * 0.6) return; // still the big player
                var box = floatingBox(v);
                if (!box) return;
                try { v.pause(); } catch (e) {}
                // the window's own close button, if it has one
                var btns = box.querySelectorAll('button, [role=button], [aria-label], [title]');
                for (var i = 0; i < btns.length; i++) {
                    var b = btns[i];
                    var label = ((b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('title') || '') + ' ' +
                        (b.className && b.className.baseVal !== undefined ? b.className.baseVal : b.className || '') + ' ' +
                        (b.textContent || '')).toLowerCase();
                    if (/close|dismiss|✕|×|✕|×/.test(label)) {
                        try { b.click(); } catch (e) {}
                        console.log('Lumen closed a mini-player');
                        return;
                    }
                }
                box.style.setProperty('display', 'none', 'important');
                console.log('Lumen hid a mini-player');
            });
        };
        var lastHref = location.href;
        setInterval(function () {
            if (location.href === lastHref) return;
            lastHref = location.href;
            // sites move the video into the small window shortly after the address changes
            [400, 1200, 2500].forEach(function (ms) { setTimeout(closeMini, ms); });
        }, 400);
    }

    // ---------------- 4K videos: hand over to a real player ----------------
    // Inside a web page every frame goes through the browser's graphics pipeline, and HEVC,
    // HDR, Dolby/DTS sound or MKV files often aren't supported at all. A dedicated player
    // (Lumen's own, or Just Player) decodes them straight on the TV's video chip.
    var bigHandled = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
    function offerBigVideo(v, reason) {
        if (!bigHandled || bigHandled.has(v)) return;
        bigHandled.add(v);
        var src = v.currentSrc || v.src || '';
        var title = '';
        try { title = (window.top.document.title || document.title || ''); } catch (e) { title = document.title || ''; }
        var handled = false;
        try { handled = TVBro.sofaBigVideo(reason, src, location.href, title, v.currentTime || 0); } catch (e) {}
        if (handled) { try { v.pause(); } catch (e) {} }
    }
    document.addEventListener('loadedmetadata', function (e) {
        var v = e.target;
        if (v && v.tagName === 'VIDEO' && (v.videoHeight >= 1600 || v.videoWidth >= 3000)) offerBigVideo(v, '4k');
    }, true);
    // every real video (not muted previews or short ad clips): the app decides by its setting
    document.addEventListener('playing', function (e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO' || v.muted || (v.duration && v.duration < 60)) return;
        var r = v.getBoundingClientRect();
        if (r.width < 200 || r.height < 120) return;
        offerBigVideo(v, (v.videoHeight >= 1600 || v.videoWidth >= 3000) ? '4k' : 'play');
    }, true);
    document.addEventListener('error', function (e) {
        var v = e.target;
        if (!v || v.tagName !== 'VIDEO' || !v.error) return;
        // 3 = can't decode, 4 = format not supported (HEVC, MKV, DTS, ...)
        if (v.error.code === 3 || v.error.code === 4) offerBigVideo(v, 'unsupported');
    }, true);

    // ---------------- long press: select & copy text, paste into fields ----------------
    // Called by the app when the remote's OK is held on the page (x, y in view pixels).
    if (isTop) {
        var lp = { edit: null, start: null, end: null };
        var toCss = function (x, y, viewW) {
            var f = viewW > 0 ? window.innerWidth / viewW : 1;
            return [x * f, y * f];
        };
        var caretAt = function (cx, cy) {
            try {
                if (document.caretRangeFromPoint) {
                    var r = document.caretRangeFromPoint(cx, cy);
                    return r ? { node: r.startContainer, offset: r.startOffset } : null;
                }
                if (document.caretPositionFromPoint) {
                    var p = document.caretPositionFromPoint(cx, cy);
                    return p ? { node: p.offsetNode, offset: p.offset } : null;
                }
            } catch (e) {}
            return null;
        };
        var editableOf = function (el) {
            var f = el && el.closest ? el.closest('input, textarea, [contenteditable]') : null;
            if (!f) return null;
            if (f.isContentEditable === false && f.tagName !== 'INPUT' && f.tagName !== 'TEXTAREA') return null;
            if (f.tagName === 'INPUT' && /^(button|submit|reset|checkbox|radio|file|image|range|color|hidden)$/i.test(f.type)) return null;
            if (f.disabled || f.readOnly) return null;
            return f;
        };
        window.__lumenLongPress = function (x, y, viewW) {
            var c = toCss(x, y, viewW);
            var el = document.elementFromPoint(c[0], c[1]);
            var link = el && el.closest ? el.closest('a[href]') : null;
            var ed = editableOf(el);
            if (ed) { lp.edit = ed; return JSON.stringify({ kind: 'edit' }); }
            var pos = caretAt(c[0], c[1]);
            if (pos && pos.node && pos.node.nodeType === 3 && /\S/.test(pos.node.data)) {
                var sel = window.getSelection();
                sel.removeAllRanges();
                sel.collapse(pos.node, pos.offset);
                try {
                    sel.modify('move', 'backward', 'word');
                    sel.modify('extend', 'forward', 'word');
                } catch (e) {}
                lp.start = { node: sel.anchorNode, offset: sel.anchorOffset };
                lp.end = { node: sel.focusNode, offset: sel.focusOffset };
                return JSON.stringify({ kind: 'text', text: sel.toString(), href: link ? link.href : null });
            }
            return JSON.stringify({ kind: link ? 'link' : 'none', href: link ? link.href : null });
        };
        // the pointer moved while selecting: the selection runs from the first word to the pointer
        window.__lumenSelExtend = function (x, y, viewW) {
            if (!lp.start) return '';
            var c = toCss(x, y, viewW);
            var pos = caretAt(c[0], c[1]);
            if (!pos || !pos.node) return '';
            var sel = window.getSelection();
            try {
                var word = document.createRange();
                word.setStart(lp.start.node, lp.start.offset);
                word.setEnd(lp.end.node, lp.end.offset);
                var where = word.comparePoint(pos.node, pos.offset);
                if (where === 0) sel.setBaseAndExtent(lp.start.node, lp.start.offset, lp.end.node, lp.end.offset);
                else if (where < 0) {
                    sel.setBaseAndExtent(lp.end.node, lp.end.offset, pos.node, pos.offset);
                    sel.modify('extend', 'backward', 'word');
                } else {
                    sel.setBaseAndExtent(lp.start.node, lp.start.offset, pos.node, pos.offset);
                    sel.modify('extend', 'forward', 'word');
                }
            } catch (e) {}
            return String(sel.toString().length);
        };
        window.__lumenSelCopy = function (cancel) {
            var sel = window.getSelection();
            var t = cancel ? '' : sel.toString();
            sel.removeAllRanges();
            lp.start = lp.end = null;
            return t;
        };
        window.__lumenPaste = function (text) {
            var f = lp.edit;
            if (!f || !f.isConnected) return 'none';
            var wasFocused = document.activeElement === f;
            f.focus();
            if (f.tagName === 'INPUT' || f.tagName === 'TEXTAREA') {
                try {
                    var s = f.selectionStart, e = f.selectionEnd;
                    // a field that wasn't being typed in: add at the end
                    if (!wasFocused && s !== null && s !== undefined) { s = e = (f.value || '').length; }
                    if (s === null || s === undefined) throw new Error('no caret');
                    f.setRangeText(text, s, e, 'end');
                    f.dispatchEvent(new Event('input', { bubbles: true }));
                    f.dispatchEvent(new Event('change', { bubbles: true }));
                } catch (err) {
                    setFieldValue(f, (f.value || '') + text); // email / number fields have no caret API
                }
            } else {
                document.execCommand('insertText', false, text);
            }
            return 'ok';
        };
    }

    // ---------------- desktop site ----------------
    // A desktop user agent alone still leaves "width=device-width" pages squeezed on a TV or
    // tablet, so lay the page out at a desktop width and let the browser zoom it to fit.
    try {
        if (isTop && window.TVBro && typeof TVBro.sofaIsDesktopSite === 'function') cfg.desktop = TVBro.sofaIsDesktopSite(location.hostname);
    } catch (e) {}
    if (cfg.desktop && isTop) {
        // Wisp: a narrower desktop page on phones (1024 px instead of 1280), so it isn't shrunk as much
        var vpContent = cfg.phone ? 'width=1024' : 'width=1280';
        var fixViewport = function () {
            var metas = document.querySelectorAll('meta[name="viewport" i]');
            for (var i = 0; i < metas.length; i++) {
                if (metas[i].getAttribute('content') !== vpContent) metas[i].setAttribute('content', vpContent);
            }
        };
        var vpObserver = new MutationObserver(fixViewport);
        vpObserver.observe(document.documentElement || document, { childList: true, subtree: true });
        onReady(function () { fixViewport(); setTimeout(function () { vpObserver.disconnect(); }, 3000); });
    }

    // ---------------- scrolling inside the page ----------------
    // TV remotes move a virtual cursor; a page only scrolls when the cursor reaches the screen edge,
    // so long submenus, lists and side panels in the middle of the screen never scrolled. The
    // browser asks which scrollable box is under the cursor (__lumenScrollProbe) and scrolls it
    // when the cursor pushes against that box's edge (__lumenInnerScroll).
    if (isTop) {
        var innerBox = null; // { el, ox, oy, frameDoc }

        var viewportBox = function () {
            var v = window.visualViewport;
            if (v && v.width > 0) return { x: v.offsetLeft, y: v.offsetTop, w: v.width, h: v.height };
            return { x: 0, y: 0, w: window.innerWidth || 1, h: window.innerHeight || 1 };
        };

        var overflows = function (el, axis) {
            var st;
            try { st = (el.ownerDocument.defaultView || window).getComputedStyle(el); } catch (e) { return false; }
            if (!st) return false;
            var o = axis === 'y' ? st.overflowY : st.overflowX;
            if (!/(auto|scroll|overlay)/.test(o)) return false;
            return axis === 'y' ? el.scrollHeight > el.clientHeight + 4 : el.scrollWidth > el.clientWidth + 4;
        };

        // the scrolling element of a same-origin frame counts as a box of the top page
        var frameScrolls = function (el, axis) {
            return axis === 'y' ? el.scrollHeight > el.clientHeight + 4 : el.scrollWidth > el.clientWidth + 4;
        };

        var boxState = function (b) {
            var el = b.el, vp = viewportBox();
            if (!el.isConnected) return null;
            var r;
            if (b.frameDoc) {
                r = { left: b.ox, top: b.oy, right: b.ox + el.clientWidth, bottom: b.oy + el.clientHeight };
            } else {
                var br = el.getBoundingClientRect();
                r = { left: br.left + b.ox, top: br.top + b.oy, right: br.right + b.ox, bottom: br.bottom + b.oy };
            }
            // only the visible part (so the edges can be reached with the cursor)
            var l = Math.max(r.left, vp.x), t = Math.max(r.top, vp.y);
            var rr = Math.min(r.right, vp.x + vp.w), bb = Math.min(r.bottom, vp.y + vp.h);
            if (rr - l < 24 || bb - t < 24) return null;
            // a box that fills the screen is the page itself; the normal page scrolling handles it
            if ((rr - l) >= vp.w * 0.9 && (bb - t) >= vp.h * 0.9) return null;
            var maxY = el.scrollHeight - el.clientHeight, maxX = el.scrollWidth - el.clientWidth;
            var sy = el.scrollTop, sx = el.scrollLeft;
            var rtl = false;
            try { rtl = (el.ownerDocument.defaultView || window).getComputedStyle(el).direction === 'rtl'; } catch (e) {}
            var left, right;
            if (rtl && sx <= 0) { left = b.x && sx > -maxX + 1; right = b.x && sx < -0.5; }
            else { left = b.x && sx > 0.5; right = b.x && sx < maxX - 1; }
            return JSON.stringify({
                l: (l - vp.x) / vp.w, t: (t - vp.y) / vp.h, r: (rr - vp.x) / vp.w, b: (bb - vp.y) / vp.h,
                up: !!(b.y && sy > 0.5), down: !!(b.y && sy < maxY - 1), left: !!left, right: !!right
            });
        };

        window.__lumenScrollProbe = function (fx, fy) {
            innerBox = null;
            try {
                var vp = viewportBox();
                var x = vp.x + fx * vp.w, y = vp.y + fy * vp.h;
                var doc = document, ox = 0, oy = 0;
                var el = doc.elementFromPoint(x, y);
                // look into same-origin frames
                for (var depth = 0; el && (el.tagName === 'IFRAME' || el.tagName === 'FRAME') && depth < 4; depth++) {
                    var idoc = null;
                    try { idoc = el.contentDocument; } catch (e) { idoc = null; }
                    if (!idoc) break;
                    var fr = el.getBoundingClientRect();
                    var fox = ox + fr.left + el.clientLeft, foy = oy + fr.top + el.clientTop;
                    var inner = idoc.elementFromPoint(x - fox, y - foy);
                    if (!inner) break;
                    doc = idoc; ox = fox; oy = foy; el = inner;
                }
                // and into shadow DOM (web components)
                for (var sd = 0; el && el.shadowRoot && sd < 8; sd++) {
                    var se = el.shadowRoot.elementFromPoint(x - ox, y - oy);
                    if (!se || se === el) break;
                    el = se;
                }
                for (var n = el; n; n = n.parentElement || (n.parentNode && n.parentNode.host) || null) {
                    if (n.nodeType !== 1) continue;
                    var nd = n.ownerDocument;
                    var isDocScroller = (n === nd.scrollingElement || n === nd.documentElement || n === nd.body);
                    if (isDocScroller) {
                        if (nd === document) break; // the page itself
                        var se2 = nd.scrollingElement || nd.documentElement;
                        if (n !== se2) continue;
                        var fy2 = frameScrolls(se2, 'y'), fx2 = frameScrolls(se2, 'x');
                        if (fy2 || fx2) {
                            innerBox = { el: se2, ox: ox, oy: oy, frameDoc: true, x: fx2, y: fy2 };
                            break;
                        }
                        continue;
                    }
                    var ay = overflows(n, 'y'), ax = overflows(n, 'x');
                    if (ay || ax) {
                        innerBox = { el: n, ox: ox, oy: oy, frameDoc: false, x: ax, y: ay };
                        break;
                    }
                }
                if (!innerBox) return '';
                var st = boxState(innerBox);
                if (!st) innerBox = null;
                return st || '';
            } catch (e) {
                innerBox = null;
                return '';
            }
        };

        window.__lumenInnerScroll = function (fdx, fdy) {
            var b = innerBox;
            if (!b) return '';
            try {
                var vp = viewportBox();
                var dx = b.x ? fdx * vp.w : 0, dy = b.y ? fdy * vp.h : 0;
                var el = b.el;
                // 'instant' so a CSS "scroll-behavior: smooth" doesn't swallow the small steps
                try { el.scrollBy({ left: dx, top: dy, behavior: 'instant' }); }
                catch (e) { el.scrollTop += dy; el.scrollLeft += dx; }
                var st = boxState(b);
                if (!st) innerBox = null;
                return st || '';
            } catch (e) {
                innerBox = null;
                return '';
            }
        };

        // ---- auto-hiding top bar ----
        // The browser sees ordinary page scrolling itself. Many web apps scroll a full-size inner
        // box instead (the page itself never moves), so report those boxes' scrolling as well.
        var boxY = (typeof WeakMap === 'function') ? new WeakMap() : null, pendingDy = 0, lastSent = 0;
        document.addEventListener('scroll', function (e) {
            var t = e.target;
            if (!t || t === document || t.nodeType !== 1) return;
            if (!window.TVBro || typeof TVBro.sofaPageScroll !== 'function') return;
            var r = t.getBoundingClientRect();
            var vw = window.innerWidth || 1, vh = window.innerHeight || 1;
            if (r.width < vw * 0.6 || r.height < vh * 0.6) return; // menus, carousels, ...
            var y = t.scrollTop;
            var prev = boxY && boxY.has(t) ? boxY.get(t) : 0;
            if (boxY) boxY.set(t, y);
            pendingDy += y - prev;
            var now = Date.now();
            if (y <= 0 || now - lastSent > 80) {
                lastSent = now;
                try { TVBro.sofaPageScroll(Math.round(y), Math.round(pendingDy)); } catch (err) {}
                pendingDy = 0;
            }
        }, { capture: true, passive: true });
    }

    // ---------------- Wisp: no WebRTC IP leak (Settings → Privacy) ----------------
    // A page can open a WebRTC connection just to read the addresses it gathers - the phone's own
    // ones and the real public address, also past a VPN. Only "relay" connections are allowed:
    // no addresses are gathered (calls still work through a relay server, most video-call sites
    // have one). Runs in every frame except human-verification frames (the script ends earlier
    // there) and Cloudflare's clean mode (no bridge: the script ends earlier too).
    if (cfg.phone && cfg.webrtcRelay) (function () {
        var relay = function (c) {
            var o = {};
            if (c && typeof c === 'object') for (var k in c) o[k] = c[k];
            o.iceTransportPolicy = 'relay';
            return o;
        };
        ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) {
            var Orig = window[n];
            if (typeof Orig !== 'function' || Orig.__wispRelay) return;
            var Wrapped = function RTCPeerConnection(config, constraints) {
                var args = arguments.length > 1 ? [relay(config), constraints] : [relay(config)];
                // "class X extends RTCPeerConnection" keeps working (new.target is the subclass)
                return Reflect.construct(Orig, args, (typeof new.target === 'function' && new.target !== Wrapped) ? new.target : Orig);
            };
            Wrapped.prototype = Orig.prototype;
            try { Object.setPrototypeOf(Wrapped, Orig); } catch (e) {}
            try { Object.defineProperty(Wrapped, '__wispRelay', { value: true }); } catch (e) {}
            try { Object.defineProperty(window, n, { value: Wrapped, writable: true, configurable: true }); } catch (e) { window[n] = Wrapped; }
        });
        try {
            var P = window.RTCPeerConnection && window.RTCPeerConnection.prototype;
            if (P && typeof P.setConfiguration === 'function') {
                var sc = P.setConfiguration;
                P.setConfiguration = function (c) { return sc.call(this, relay(c)); };
            }
        } catch (e) {}
    })();

    // ---------------- Wisp: small animated ads (Settings → Ad blocker) ----------------
    // Banners that slip past the filter lists: other sites' frames in banner sizes, and small moving
    // pictures or videos that link to another site. Video players, maps, embeds of known services and
    // human-verification boxes are left alone.
    if (cfg.phone && cfg.smallAds && isTop && /^https?:$/.test(location.protocol)) (function () {
        var siteOf = function (h) {
            h = (h || '').toLowerCase().replace(/^www\./, '');
            var p = h.split('.');
            // example.co.uk, example.com.br: three parts
            if (p.length > 2 && /^(co|com|net|org|gov|edu|ac|ne|or)$/.test(p[p.length - 2])) return p.slice(-3).join('.');
            return p.slice(-2).join('.');
        };
        var mySite = siteOf(location.hostname);
        var KEEP = /(^|\.)(stripe\.com|stripe\.network|paypal\.com|paypalobjects\.com|braintreegateway\.com|adyen\.com|checkout\.com|klarna\.com|mollie\.com|intercom\.io|zendesk\.com|zdassets\.com|crisp\.chat|tawk\.to|drift\.com|hubspot\.com|microsoft\.com|live\.com|microsoftonline\.com|youtube\.com|youtube-nocookie\.com|vimeo\.com|dailymotion\.com|twitch\.tv|spotify\.com|soundcloud\.com|google\.com|googleapis\.com|gstatic\.com|recaptcha\.net|hcaptcha\.com|cloudflare\.com|twitter\.com|x\.com|facebook\.com|instagram\.com|tiktok\.com|reddit\.com|redditmedia\.com|streamable\.com|giphy\.com|imgur\.com|disqus\.com|openstreetmap\.org|apple\.com|bandcamp\.com|archive\.org)$/i;
        var SIZES = [[300,250],[320,50],[320,100],[300,50],[728,90],[468,60],[160,600],[300,600],[336,280],
                     [970,90],[970,250],[250,250],[200,200],[120,600],[180,150],[234,60],[300,100],[320,480]];
        var bannerSized = function (w, h) {
            for (var i = 0; i < SIZES.length; i++) {
                if (Math.abs(w - SIZES[i][0]) <= 8 && Math.abs(h - SIZES[i][1]) <= 8) return true;
            }
            return false;
        };
        var hostOf = function (u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } };
        var otherSite = function (u) { var h = hostOf(u); return !!h && siteOf(h) !== mySite; };
        var hide = function (el) {
            // the box the banner sits in when it is (almost) the same size
            var n = el;
            for (var i = 0; i < 3; i++) {
                var p = n.parentElement;
                if (!p || p === document.body || p === document.documentElement) break;
                var r = n.getBoundingClientRect(), pr = p.getBoundingClientRect();
                if (Math.abs(pr.width - r.width) > 16 || Math.abs(pr.height - r.height) > 16) break;
                n = p;
            }
            try { n.style.setProperty('display', 'none', 'important'); n.setAttribute('data-wisp-ad', '1'); } catch (e) {}
        };
        var checked = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
        var scan = function () {
            if (!document.body || isWispReader()) return;
            var frames = document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
                var f = frames[i];
                if (checked && checked.has(f)) continue;
                var w = f.offsetWidth, h = f.offsetHeight;
                if (!w || !h) continue; // not laid out yet: next scan
                if (checked) checked.add(f);
                var src = f.getAttribute('src') || '';
                var host = src && !/^(about:|javascript:|data:)/i.test(src) ? hostOf(src) : '';
                if (host && KEEP.test(host)) continue;
                if (host && !otherSite(src)) continue;                      // the site's own frames
                if (f.hasAttribute('allowfullscreen') || /fullscreen/i.test(f.getAttribute('allow') || '')) continue; // players
                // frames without an address (text editors, comment boxes use them) only in banner sizes
                if (bannerSized(w, h) || (host && w <= 400 && h <= 300 && w * h <= 100000)) hide(f);
            }
            var media = document.querySelectorAll('a[href] img, a[href] video, a[href] picture');
            for (var j = 0; j < media.length; j++) {
                var m = media[j];
                if (checked && checked.has(m)) continue;
                var mw = m.offsetWidth, mh = m.offsetHeight;
                if (!mw || !mh) continue;
                if (checked) checked.add(m);
                if (mw > 400 || mh > 300) continue;
                var a = m.closest('a[href]');
                if (!a || !otherSite(a.href) || KEEP.test(hostOf(a.href))) continue;
                var tag = m.tagName;
                var moving = tag === 'VIDEO' ? (m.autoplay || m.loop) && !m.controls
                    : /\.(gif|webp|apng)(\?|#|$)/i.test(m.currentSrc || m.src || (m.querySelector && (m.querySelector('img') || {}).src) || '');
                if (moving || bannerSized(mw, mh)) hide(a);
            }
        };
        var pending = false;
        var later = function () {
            if (pending) return;
            pending = true;
            setTimeout(function () { pending = false; try { scan(); } catch (e) {} }, 700);
        };
        onReady(function () {
            later();
            // banners arrive late; images get their size only once loaded
            var n = 0;
            var t = setInterval(function () { later(); if (++n >= 20) clearInterval(t); }, 1500);
            try { new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
            window.addEventListener('load', later);
        });
    })();

    // ---------------- Wisp: long press on images, links, videos; hidden elements ----------------
    // Many sites stop the browser's own long-press menu on images (they cancel "contextmenu").
    // This listener runs before the page's own ones and asks the app to show its menu with what is
    // under the finger - also images under invisible overlays and background images.
    if (cfg.phone && window.TVBro && typeof TVBro.wispLongPress === 'function') (function () {
        // elements hidden earlier on this site ("Hide element")
        if (isTop) try {
            var hidden = JSON.parse(TVBro.wispHidden(location.hostname) || '[]');
            if (hidden.length) {
                var css = hidden.map(function (h) { return h + '{display:none!important}'; }).join('\n');
                var put = function () { if (!isWispReader()) addStyle('wisp-hidden', css); };
                if (isWispReader() || !addStyle('wisp-hidden', css)) onReady(put);
            }
        } catch (e) {}

        var MEDIA = { IMG: 1, VIDEO: 1, IFRAME: 1, CANVAS: 1, SVG: 1, PICTURE: 1, EMBED: 1, OBJECT: 1 };
        var picked = null;
        var esc = function (t) { return (window.CSS && CSS.escape) ? CSS.escape(t) : t.replace(/[^\w-]/g, '\\$&'); };
        var bgUrl = function (el) {
            try {
                var m = /url\(["']?([^"')]+)["']?\)/.exec(getComputedStyle(el).backgroundImage || '');
                return m ? new URL(m[1], location.href).href : null;
            } catch (e) { return null; }
        };
        // a CSS selector for the element: an id when it has a stable one, otherwise tag, classes and
        // position, going up until it is unique on the page
        var cssPath = function (el) {
            var parts = [];
            for (var n = el, d = 0; n && n.nodeType === 1 && n !== document.body && n !== document.documentElement && d < 6; n = n.parentElement, d++) {
                if (n.id && /^[A-Za-z][\w-]{0,40}$/.test(n.id) && !/\d{4,}/.test(n.id)) {
                    parts.unshift('#' + esc(n.id));
                    break;
                }
                var part = n.tagName.toLowerCase();
                var cls = [].filter.call(n.classList || [], function (c) {
                    return /^[A-Za-z_-][\w-]{0,40}$/.test(c) && !/\d{4,}/.test(c);
                }).slice(0, 3);
                if (cls.length) part += '.' + cls.map(esc).join('.');
                var p = n.parentElement;
                if (p) {
                    var same = [].filter.call(p.children, function (c) { return c.tagName === n.tagName; });
                    if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(n) + 1) + ')';
                }
                parts.unshift(part);
                try { if (document.querySelectorAll(parts.join(' > ')).length === 1) break; } catch (e) { break; }
            }
            return parts.join(' > ');
        };
        // the box an ad sits in: parents of (almost) the same size
        var grow = function (el) {
            var n = el;
            for (var i = 0; i < 4; i++) {
                var p = n.parentElement;
                if (!p || p === document.body || p === document.documentElement) break;
                var r = n.getBoundingClientRect(), pr = p.getBoundingClientRect();
                if (Math.abs(pr.width - r.width) > 12 || Math.abs(pr.height - r.height) > 12) break;
                n = p;
            }
            return n;
        };
        // what is under the finger: { info, media } or null
        var lookAt = function (e) {
            {
                var els = document.elementsFromPoint ? document.elementsFromPoint(e.clientX, e.clientY) : [e.target];
                var image = null, media = null, link = null, label = null;
                var top = els[0];
                if (top && top.closest) {
                    var a = top.closest('a[href]');
                    if (a && /^https?:/i.test(a.href)) link = a.href;
                }
                for (var i = 0; i < els.length && i < 12; i++) {
                    var el = els[i];
                    if (el === document.body || el === document.documentElement) break;
                    var tag = (el.tagName || '').toUpperCase();
                    if (!image && tag === 'IMG') {
                        image = el.currentSrc || el.src;
                        label = el.alt || el.title || null;
                        if (!media) media = el;
                        if (!link && el.closest) {
                            var ia = el.closest('a[href]');
                            if (ia && /^https?:/i.test(ia.href)) link = ia.href;
                        }
                    } else if (!image && tag === 'PICTURE') {
                        var im = el.querySelector('img');
                        if (im) { image = im.currentSrc || im.src; if (!media) media = im; }
                    } else if (MEDIA[tag]) {
                        if (!media) media = el;
                        if (tag === 'VIDEO' && el.poster && !image) image = el.poster;
                    } else if (!image && i < 4) {
                        var b = bgUrl(el);
                        if (b) { image = b; if (!media) media = el; }
                    }
                    if (image && media) break;
                }
                if (!image && !link && !media) return null;
                if (image && !/^(https?:|data:image\/)/i.test(image)) image = null;
                return { info: { link: link, image: image, label: label }, media: media };
            }
        };
        if (!isTop) {
            // inside a frame (ad banners usually are): the page above shows the menu and can hide the
            // whole frame
            window.addEventListener('contextmenu', function (e) {
                try {
                    var r = lookAt(e) || { info: { link: null, image: null, label: null } };
                    window.top.postMessage({ __wispLongPress: r.info }, '*');
                } catch (err) {}
            }, true);
            return;
        }
        window.addEventListener('contextmenu', function (e) {
            try {
                var r = lookAt(e);
                if (!r) return;
                picked = r.media ? grow(r.media) : null;
                r.info.hide = !!picked;
                TVBro.wispLongPress(JSON.stringify(r.info));
            } catch (err) {}
        }, true);
        window.addEventListener('message', function (e) {
            try {
                var d = e.data && e.data.__wispLongPress;
                if (!d || typeof d !== 'object') return;
                // the frame element on this page the message came from (also from frames inside frames)
                var w = e.source;
                for (var i = 0; w && w.parent !== window && i < 10; i++) w = w.parent;
                var frames = document.querySelectorAll('iframe, frame');
                var frame = null;
                for (var j = 0; j < frames.length; j++) if (frames[j].contentWindow === w) { frame = frames[j]; break; }
                if (!frame) return;
                picked = grow(frame);
                TVBro.wispLongPress(JSON.stringify({
                    link: typeof d.link === 'string' ? d.link : null,
                    image: typeof d.image === 'string' ? d.image : null,
                    label: typeof d.label === 'string' ? d.label : null, hide: true }));
            } catch (err) {}
        });
        // "Hide element": hides it now and returns its selector, so the app remembers it for the site
        window.__wispHidePicked = function () {
            if (!picked) return '';
            var sel = cssPath(picked);
            try { picked.style.setProperty('display', 'none', 'important'); } catch (e) {}
            picked = null;
            return sel;
        };
    })();

    // ---------------- start-up ----------------
    onReady(function () {
        challengePage = isChallengePage();
        if (challengePage) {
            // ask the app to reload this page in clean mode (nothing injected, ad blocking off)
            if (isTop) { try { TVBro.sofaChallenge(location.href); } catch (e) {} }
            return;
        }
        if (cfg.maxQuality) {
            applyPlayerApiQuality();
            // single-page sites (YouTube) change videos without reloading
            setInterval(applyPlayerApiQuality, 4000);
        }
    });
})();
