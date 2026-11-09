/* Fetchly Help guide. Plain JS, no libraries, works offline.
   Adds: Help button, Help panel, "How to fix" links and "Copy error details". */
(function () {
  'use strict';

  // Each topic: id, title, see (what you see), why, fix (steps), and match (simple regex on error text).
  var TOPICS = [
    {
      id: 'bot', title: 'YouTube says "Sign in to confirm you\'re not a bot"',
      match: /not a bot|confirm you.?re not/i,
      see: 'YouTube blocks the download and asks you to sign in.',
      why: 'YouTube thinks the request comes from a bot. This is common on VPNs, shared networks and servers.',
      fix: [
        'Install a JavaScript runtime. Recent yt-dlp needs one for YouTube. Deno is the one yt-dlp suggests (get it from deno.com, version 2.3 or newer). Node 22+ also works. Put it on your PATH.',
        'Press "Update tools" so yt-dlp and its helper scripts (yt-dlp-ejs) are current.',
        'Log in to YouTube in Firefox, then close the app and start it again with FETCHLY_COOKIES_BROWSER=firefox (this is the yt-dlp option --cookies-from-browser firefox)',
        'Chrome on Windows often fails to give cookies (the cookie file is locked or encrypted). Firefox usually works.',
        'Other way: export a cookies.txt file (Netscape format) and save it as cookies.txt in the Fetchly folder (Fetchly uses it by itself). yt-dlp says: open a private window, log in to YouTube, open youtube.com/robots.txt in that same tab, export the youtube.com cookies, then close the private window.',
        'Keep cookies.txt private. It holds your login. Never share it or commit it. Fetchly ignores cookies*.txt in git.'
      ],
      warn: 'Warning: using your main Google account can get it limited or banned (yt-dlp warns about this). A spare account is safer.'
    },
    {
      id: 'login', title: 'Instagram and other sites that need a login',
      match: /log ?in|sign ?in|login required|authenticat|cookies|rate-limit|empty media response|requires an account|members?[- ]only|need to log/i,
      see: 'A message like "Instagram needs a login", "login required", "empty media response" for an Instagram video link.',
      why: 'The site only shows this content to signed-in users.',
      fix: [
        'Log in to the site in Firefox.',
        'Open Help and find the "Cookies" box (below the topics). Pick Firefox and press "Use browser cookies".',
        'Or export a cookies.txt file from your browser and press "Upload cookies.txt" in the same box.',
        'Try the link again. For Instagram, the post must open in Firefox while you are logged in.',
        'Chrome on Windows often fails to give cookies. Firefox usually works.'
      ],
      warn: 'Warning: automated use of your main account can get it blocked. Use a spare account.'
    },
    {
      id: 'private', title: 'Private, deleted or age-restricted content',
      match: /private|deleted|removed|no longer available|age[- ]restrict|confirm your age|inappropriate|video unavailable|has been terminated/i,
      see: 'The video is private, removed, or asks you to confirm your age.',
      why: 'The owner hid or deleted it, or the site wants a signed-in adult account.',
      fix: [
        'Open the link in your browser. If you cannot watch it there, Fetchly cannot download it.',
        'Private video: only the owner can allow access.',
        'Deleted video: it is gone. Nothing can fix this.',
        'Age-restricted: log in in Firefox and use your cookies (see the YouTube bot topic).'
      ]
    },
    {
      id: 'country', title: '"Video not available in your country"',
      match: /country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your/i,
      see: 'The site says the video is not available where you are.',
      why: 'The owner limited it to some countries.',
      fix: [
        'Check that the video plays in your browser.',
        'If you use a VPN, turn it on with a country where the video is allowed (or turn it off if it causes the block).',
        'Use only places and content you are allowed to use.'
      ]
    },
    {
      id: 'unsupported', title: 'Link not supported, or image links',
      match: /unsupported url|aren.t allowed|private network|local and private|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported/i,
      see: '"Unsupported URL", "No video found" or "Fetchly downloads videos only."',
      why: 'The link is not a video page (Fetchly downloads videos only), is cut off, or the site is not supported.',
      fix: [
        'Copy the full link from the address bar again. Remove extra text before or after it.',
        'Use the page link of the video or post, not a link to a search or home page.',
        'Image links do not work. Fetchly downloads videos only.',
        'Press "Update tools". New site support comes with new versions.'
      ]
    },
    {
      id: 'ffmpeg', title: '"ffmpeg not found" or a video with no sound',
      match: /ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound/i,
      see: 'An error that names ffmpeg, or the video plays with no sound.',
      why: 'Many sites give video and sound as two files. ffmpeg joins them. Without it you get only one.',
      fix: [
        'Install ffmpeg (for Windows: winget install ffmpeg, or download from ffmpeg.org).',
        'Close and open the app again so it can find ffmpeg on your PATH.',
        'Check in a terminal: ffmpeg -version',
        'Try the download again.'
      ]
    },
    {
      id: 'port', title: '"Port already in use"',
      match: /port.{0,30}in use|address already in use|10048|only one usage of each socket/i,
      see: 'The app does not start and says the port is in use.',
      why: 'Another copy of Fetchly (or another program) already uses that port.',
      fix: [
        'Close the other Fetchly window or terminal.',
        'Or start with a different port, for example: set PORT=8002 (Windows) then run the app.',
        'Open the new address in your browser.'
      ]
    },
    {
      id: 'network', title: 'Slow or failing downloads, VPN or proxy errors',
      match: /timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|too many requests|temporar/i,
      see: 'The download is very slow, stops, or says "connection error" or "timed out".',
      why: 'Your network, VPN or proxy is slow or blocks the site. Sites also slow you down if you ask too often.',
      fix: [
        'Check your internet works in the browser.',
        'Turn the VPN or proxy off, or switch to another server, then try again.',
        'Wait a few minutes if you made many requests (error 429).',
        'Try again later.'
      ]
    },
    {
      id: 'update', title: 'The tools are outdated: press "Update tools"',
      match: /unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response/i,
      see: 'Errors like "Unable to extract" or "please update yt-dlp".',
      why: 'Sites change often. Old tool versions stop working.',
      fix: [
        'Press the "Update tools" button at the bottom of the page.',
        'Wait for it to finish.',
        'Try the link again.'
      ]
    },
    {
      id: 'format', title: '"Format not available" errors',
      match: /format.{0,20}(is )?not available|requested format|no such format|no formats/i,
      see: '"Requested format is not available".',
      why: 'The quality you picked does not exist for this video, or the site needs a login or newer tools to show formats.',
      fix: [
        'Pick another quality (or "Best").',
        'Press "Update tools" and try again.',
        'If the video needs a login, use cookies (see the YouTube bot topic).',
        'Install ffmpeg if the format needs joining (see the ffmpeg topic).'
      ]
    }
  ];

  // Order matters: more exact checks first.
  var ORDER = ['bot', 'format', 'ffmpeg', 'port', 'country', 'unsupported', 'private', 'login', 'update', 'network'];

  function byId(id) { for (var i = 0; i < TOPICS.length; i++) if (TOPICS[i].id === id) return TOPICS[i]; return null; }

  function findTopic(text) {
    for (var i = 0; i < ORDER.length; i++) {
      var t = byId(ORDER[i]);
      if (t.match.test(text || '')) return t.id;
    }
    return null;
  }

  var css = '' +
    '#fhOverlay{position:fixed;inset:0;z-index:100;background:rgba(0,0,0,.55);display:none;align-items:center;justify-content:center;padding:12px}' +
    '#fhOverlay.open{display:flex}' +
    '#fhBox{background:#fff;color:#0f172a;max-width:680px;width:100%;max-height:90vh;overflow:auto;border-radius:16px;padding:20px;font:15px/1.5 system-ui,sans-serif}' +
    '@media (prefers-color-scheme:dark){#fhBox{background:#0f172a;color:#e2e8f0}#fhBox details{border-color:#334155}}' +
    '#fhBox h2{margin:0 0 8px;font-size:20px}#fhBox h3{margin:0;font-size:15px;display:inline}' +
    '#fhBox details{border:1px solid #cbd5e1;border-radius:10px;padding:8px 12px;margin:8px 0}' +
    '#fhBox summary{cursor:pointer}#fhBox details[open]{border-color:#6366f1}' +
    '#fhBox ol{margin:6px 0 6px 20px;padding:0}#fhBox p{margin:6px 0}' +
    '.fhWarn{background:#fef3c7;color:#78350f;padding:8px;border-radius:8px}' +
    '.fhBtn{border:1px solid #94a3b8;border-radius:8px;background:transparent;color:inherit;padding:4px 10px;font:inherit;font-size:13px;cursor:pointer}' +
    '.fhLink{margin-left:8px;font-size:13px;font-weight:600;text-decoration:underline;cursor:pointer;background:none;border:0;color:#4f46e5;padding:0}' +
    '.fhRow{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:6px}';

  function el(tag, props, kids) {
    var e = document.createElement(tag);
    if (props) for (var k in props) { if (k === 'text') e.textContent = props[k]; else e.setAttribute(k, props[k]); }
    (kids || []).forEach(function (c) { e.appendChild(c); });
    return e;
  }

  var overlay, lastError = '';

  function build() {
    var st = el('style'); st.textContent = css; document.head.appendChild(st);
    var box = el('div', { id: 'fhBox', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Help guide' });
    box.appendChild(el('h2', { text: 'Help guide' }));
    box.appendChild(el('p', { text: 'Pick your problem. Each one shows: what you see, why it happens, and how to fix it.' }));
    TOPICS.forEach(function (t) {
      var d = el('details', { id: 'help-' + t.id });
      d.appendChild(el('summary', null, [el('h3', { text: t.title })]));
      d.appendChild(el('p', { text: 'What you see: ' + t.see }));
      d.appendChild(el('p', { text: 'Why it happens: ' + t.why }));
      d.appendChild(el('p', { text: 'How to fix:' }));
      var ol = el('ol');
      t.fix.forEach(function (s) { ol.appendChild(el('li', { text: s })); });
      d.appendChild(ol);
      if (t.warn) d.appendChild(el('p', { 'class': 'fhWarn', text: t.warn }));
      box.appendChild(d);
    });
    box.appendChild(buildCookies());
    var row = el('div', { 'class': 'fhRow' });
    var copy = el('button', { type: 'button', 'class': 'fhBtn', text: 'Copy error details' });
    copy.addEventListener('click', function () { copyDetails(copy); });
    var close = el('button', { type: 'button', 'class': 'fhBtn', text: 'Close' });
    close.addEventListener('click', closeHelp);
    row.appendChild(copy); row.appendChild(close);
    box.appendChild(row);
    overlay = el('div', { id: 'fhOverlay' }, [box]);
    overlay.addEventListener('click', function (e) { if (e.target === overlay) closeHelp(); });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeHelp(); });
    document.body.appendChild(overlay);
  }

  // ---- Cookies box: use browser cookies or upload cookies.txt (local use only) ----
  function api(method, path, body) {
    return fetch(path, {
      method: method,
      headers: body ? { 'Content-Type': 'application/json' } : {},
      body: body ? JSON.stringify(body) : undefined
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) throw new Error(j.message || 'Request failed');
        return j;
      });
    });
  }

  function buildCookies() {
    var wrap = el('details', { id: 'help-cookies' });
    wrap.appendChild(el('summary', null, [el('h3', { text: 'Cookies (login for Instagram and other sites)' })]));
    wrap.appendChild(el('p', { text: 'Log in to the site in your browser first. Cookies stay on this computer, in cookies.txt, and are never committed to git. Use a spare account if you can.' }));
    var status = el('p', { 'class': 'fhStatus', role: 'status' });
    var sel = el('select', { 'aria-label': 'Browser', 'class': 'fhBtn' });
    ['firefox', 'chrome', 'edge', 'brave', 'opera', 'chromium', 'vivaldi'].forEach(function (b) {
      sel.appendChild(el('option', { value: b, text: b === 'firefox' ? 'Firefox (best)' : b.charAt(0).toUpperCase() + b.slice(1) }));
    });
    var useBtn = el('button', { type: 'button', 'class': 'fhBtn', text: 'Use browser cookies' });
    var file = el('input', { type: 'file', accept: '.txt,text/plain', 'aria-label': 'cookies.txt file' });
    var upBtn = el('button', { type: 'button', 'class': 'fhBtn', text: 'Upload cookies.txt' });
    var clrBtn = el('button', { type: 'button', 'class': 'fhBtn', text: 'Remove cookies' });
    function show(j) {
      var parts = [];
      if (j.file) parts.push('cookies.txt is in use');
      if (j.browser) parts.push('browser cookies: ' + j.browser);
      status.textContent = 'Now: ' + (parts.length ? parts.join(', ') : 'no cookies');
    }
    function fail(e) { status.textContent = 'Error: ' + e.message; }
    useBtn.addEventListener('click', function () { api('POST', '/api/cookies/browser', { browser: sel.value }).then(show, fail); });
    clrBtn.addEventListener('click', function () { api('DELETE', '/api/cookies').then(show, fail); });
    upBtn.addEventListener('click', function () {
      var f = file.files && file.files[0];
      if (!f) { status.textContent = 'Choose a cookies.txt file first.'; return; }
      f.text().then(function (t) { return api('POST', '/api/cookies/file', { text: t }); }).then(function (j) { file.value = ''; show(j); }, fail);
    });
    wrap.addEventListener('toggle', function () { if (wrap.open) api('GET', '/api/cookies').then(show, fail); });
    wrap.appendChild(el('div', { 'class': 'fhRow' }, [sel, useBtn]));
    wrap.appendChild(el('div', { 'class': 'fhRow' }, [file, upBtn]));
    wrap.appendChild(el('div', { 'class': 'fhRow' }, [clrBtn]));
    wrap.appendChild(status);
    return wrap;
  }

  // Remember the technical detail the server sends with an error, for "Copy error details".
  var lastDetail = '';
  if (window.fetch) {
    var origFetch = window.fetch;
    window.fetch = function () {
      return origFetch.apply(this, arguments).then(function (resp) {
        if (!resp.ok) {
          resp.clone().json().then(function (j) { lastDetail = (j && j.detail) ? String(j.detail) : ''; }).catch(function () {});
        }
        return resp;
      });
    };
  }

  function openHelp(topicId) {
    var all = overlay.querySelectorAll('details');
    for (var i = 0; i < all.length; i++) all[i].open = false;
    overlay.classList.add('open');
    if (topicId) {
      var d = document.getElementById('help-' + topicId);
      if (d) { d.open = true; d.scrollIntoView({ block: 'start' }); }
    }
  }
  function closeHelp() { overlay.classList.remove('open'); }

  function details(msg) {
    var tv = document.getElementById('toolVersions');
    return [
      'Fetchly error details',
      'Time: ' + new Date().toISOString(),
      'Error: ' + (msg || lastError || '(none shown)'),
      'Technical: ' + (lastDetail || '(none)'),
      'Tools: ' + (tv ? tv.textContent.trim() : '(unknown)'),
      'Browser: ' + navigator.userAgent
    ].join('\n');
  }

  function copyDetails(btn, msg) {
    var text = details(msg), old = btn.textContent;
    function done(ok) { btn.textContent = ok ? 'Copied!' : 'Copy failed'; setTimeout(function () { btn.textContent = old; }, 1800); }
    function fallback() {
      var ta = el('textarea'); ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      var ok = false; try { ok = document.execCommand('copy'); } catch (e) {}
      ta.remove(); done(ok);
    }
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(text).then(function () { done(true); }, fallback);
    else fallback();
  }

  // Watch the app's error text boxes and add the two small buttons next to them.
  function watch(textId) {
    var node = document.getElementById(textId);
    if (!node || !node.parentNode) return;
    var holder = el('span', { 'class': 'fhHolder' });
    node.parentNode.insertBefore(holder, node.nextSibling);
    function refresh() {
      var msg = node.textContent.trim();
      holder.textContent = '';
      if (!msg) return;
      lastError = msg;
      var id = findTopic(msg);
      if (id) {
        var a = el('button', { type: 'button', 'class': 'fhLink', 'data-help-topic': id, text: 'How to fix' });
        a.addEventListener('click', function () { openHelp(id); });
        holder.appendChild(a);
      }
      var c = el('button', { type: 'button', 'class': 'fhLink', text: 'Copy error details' });
      c.addEventListener('click', function () { copyDetails(c, msg); });
      holder.appendChild(c);
    }
    new MutationObserver(refresh).observe(node, { childList: true, characterData: true, subtree: true });
    refresh();
  }

  function init() {
    build();
    var upd = document.getElementById('updateTools');
    var help = el('button', { type: 'button', id: 'helpButton', 'class': 'fhBtn', text: 'Help' });
    help.addEventListener('click', function () { openHelp(); });
    if (upd && upd.parentNode) upd.parentNode.insertBefore(help, upd);
    else { help.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:50'; document.body.appendChild(help); }
    watch('videoErrorText');
    window.fetchlyHelp = { open: openHelp, find: findTopic };
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
