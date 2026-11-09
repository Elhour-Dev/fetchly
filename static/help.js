/* Fetchy Help guide. Plain JS, no libraries, works offline.
   Three expandable cards (Instagram, YouTube, General problems). Each problem says
   what you see, why it happens, and how to fix it. Same content and structure as the mobile app.
   Also adds: Help button, "How to fix" links, "Copy error details", and the Cookies box. */
(function () {
  'use strict';

  var TIP = "Use a secondary account, not your main one. It's safer for your main account if the site flags the login. " +
    "It lowers the risk, it doesn't remove it.";
  var COOKIE_NOTE = 'Cookies are like a password. Never share them.';

  // One problem: id, title, see (what you see), why, fix (2-4 steps), match (regex on the error text).
  var CARDS = [
    {
      id: 'instagram', title: 'Instagram', subtitle: 'Login required, empty result, private post', tip: true,
      problems: [
        {
          id: 'ig_login', title: 'Login required',
          match: /log ?in|sign ?in|login required|authenticat|cookies|rate-limit|requires an account|members?[- ]only|need to log/i,
          see: '"Login required", "Instagram needs a login" or "rate-limit reached". Other sites can say the same.',
          why: 'The site only shows this to logged-in people.',
          fix: [
            'Log in to the site in Firefox on this computer.',
            'Scroll down to "Cookies" in this guide. Pick Firefox and press "Use browser cookies".',
            'Press "Try again".',
            'No luck? Export a cookies.txt file from your browser and press "Upload cookies.txt".'
          ]
        },
        {
          id: 'ig_empty', title: 'Empty result',
          match: /empty media response|no video in this post|there is no video/i,
          see: '"Empty media response", or no video found in a post.',
          why: 'You are probably not logged in, or the post has only photos.',
          fix: [
            'Log in in Firefox, then press "Use browser cookies" (see "Cookies" below).',
            'Check that the post is a video or a Reel.',
            'Wait a few minutes and try again.'
          ]
        },
        {
          id: 'ig_private', title: 'Private or deleted post',
          match: /private|deleted|removed|no longer available|content is not available/i,
          see: '"This content is not available", "private" or "deleted".',
          why: 'The owner made it private or deleted it.',
          fix: [
            'Open the link in your browser. If you cannot see it there, Fetchy cannot either.',
            'Private: log in in Firefox with an account that may see it, press "Use browser cookies", then try again.',
            'Deleted: it is gone for good.'
          ]
        }
      ]
    },
    {
      id: 'youtube', title: 'YouTube', subtitle: 'Bot check, video unavailable', tip: true,
      problems: [
        {
          id: 'yt_bot', title: '"Sign in to confirm you\'re not a bot"',
          match: /not a bot|confirm you.?re not/i,
          see: 'YouTube asks you to sign in to prove you are not a bot.',
          why: 'YouTube blocks requests it does not trust. VPNs and busy networks make it worse.',
          fix: [
            'If a VPN or proxy is on, turn it off.',
            'Log in to YouTube in Firefox. Then press "Use browser cookies" (see "Cookies" below).',
            'Chrome on Windows often fails to share cookies. Firefox usually works. Or upload a cookies.txt file.',
            'Press "Update tools", then "Try again".'
          ]
        },
        {
          id: 'yt_unavail', title: 'Video unavailable',
          match: /video unavailable|age[- ]restrict|confirm your age|inappropriate|has been terminated|country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your/i,
          see: '"Video unavailable", "Private video" or "not available in your country".',
          why: 'The owner hid or removed it, it is blocked where you are, or it needs a logged-in adult.',
          fix: [
            'Open the link in your browser. If it does not play there, Fetchy cannot get it.',
            'Age limit: log in in Firefox with an adult account, press "Use browser cookies", then try again.',
            'Blocked in your country: a VPN may help, but it can also trigger the bot check.',
            'Removed or private: only the owner can fix that.'
          ]
        }
      ]
    },
    {
      id: 'general', title: 'General problems', subtitle: 'Sound, speed, links, tools, files', tip: false,
      problems: [
        {
          id: 'g_unsupported', title: 'Link not supported',
          match: /unsupported url|aren.t allowed|private network|local and private|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported/i,
          see: '"Unsupported URL" or "No video found".',
          why: 'The link is not a video page, is cut off, or the site is not supported.',
          fix: [
            'Copy the link of the video itself from the address bar.',
            'Paste the whole link. It starts with https://',
            'Photos do not work. Fetchy downloads videos.',
            'Press "Update tools", then try again.'
          ]
        },
        {
          id: 'g_ffmpeg', title: 'No sound, or ffmpeg missing',
          match: /ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound/i,
          see: 'The video has no sound, or an error names ffmpeg.',
          why: 'Many sites keep video and sound apart. Fetchy joins them with ffmpeg.',
          fix: [
            'Press "Try again".',
            'Free some space on your disk.',
            'Press "Update tools".',
            'Still broken? Install Fetchy again with the latest installer.'
          ]
        },
        {
          id: 'g_network', title: 'Slow or failed download',
          match: /timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|too many requests|temporar|resolve/i,
          see: 'The download is very slow, stops, times out, or says "too many requests" (429).',
          why: 'Weak connection, a VPN, or the site is limiting you.',
          fix: [
            'Check that the internet works in your browser.',
            'Turn off VPN or proxy, then press "Try again".',
            '"Too many requests": wait a few minutes.',
            'Try another network.'
          ]
        },
        {
          id: 'g_update', title: 'Outdated tools',
          match: /unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response/i,
          see: '"Unable to extract" or "please update yt-dlp".',
          why: 'Sites change often. Old tools stop working.',
          fix: [
            'Press "Update tools" at the bottom of the page.',
            'Wait until it finishes.',
            'Press "Try again". If the update fails, the old version keeps working.'
          ]
        },
        {
          id: 'g_format', title: '"Format not available"',
          match: /format.{0,20}(is )?not available|requested format|no such format|no formats/i,
          see: '"Requested format is not available".',
          why: 'The site gave no video and sound mix that Fetchy can use. Often it needs a login.',
          fix: [
            'Pick another quality, or "Best quality".',
            'Press "Update tools".',
            'Log in or use cookies (see the Instagram or YouTube card).',
            'Press "Try again".'
          ]
        },
        {
          id: 'g_storage', title: 'File not found in Downloads', match: null,
          see: 'The download finished, but you cannot find the video.',
          why: 'Fetchy saves finished videos in your Downloads folder.',
          fix: [
            'Open File Explorer and go to Downloads.',
            'Sort by date, newest first.',
            'A name that already exists gets a number, like "video (1).mp4".'
          ]
        }
      ]
    }
  ];

  // Order matters: the first match wins.
  var ORDER = ['yt_bot', 'g_format', 'g_ffmpeg', 'yt_unavail', 'g_unsupported', 'ig_private', 'ig_empty', 'ig_login', 'g_update', 'g_network'];

  function problemById(id) {
    for (var c = 0; c < CARDS.length; c++) for (var p = 0; p < CARDS[c].problems.length; p++)
      if (CARDS[c].problems[p].id === id) return CARDS[c].problems[p];
    return null;
  }

  function findTopic(text) {
    for (var i = 0; i < ORDER.length; i++) {
      var p = problemById(ORDER[i]);
      if (p && p.match && p.match.test(text || '')) return p.id;
    }
    return null;
  }

  // Look: same palette as the app (slate neutrals, indigo accent, rounded cards). Dark mode follows the app's "dark" class.
  var css = '' +
    '#fhOverlay,#helpButton{--bg:#f8fafc;--card:#fff;--border:#e2e8f0;--text:#0f172a;--muted:#64748b;--accent:#4f46e5;--tonal:#eef2ff;--tonalText:#4338ca;--tipBg:#fffbeb;--tipBorder:#fde68a;--tipText:#92400e}' +
    '#fhOverlay{position:fixed;inset:0;z-index:100;background:rgba(15,23,42,.55);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;padding:16px;font-family:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}' +
    'html.dark #fhOverlay,html.dark #helpButton{--bg:#080b14;--card:rgba(255,255,255,.04);--border:rgba(255,255,255,.1);--text:#f1f5f9;--muted:#94a3b8;--accent:#818cf8;--tonal:rgba(99,102,241,.15);--tonalText:#c7d2fe;--tipBg:rgba(245,158,11,.1);--tipBorder:rgba(245,158,11,.25);--tipText:#fcd34d}' +
    '#fhOverlay.open{display:flex}' +
    '#fhBox{background:var(--bg);color:var(--text);max-width:720px;width:100%;max-height:92vh;overflow-y:auto;overflow-x:hidden;border-radius:20px;border:1px solid var(--border);box-shadow:0 28px 70px -24px rgba(15,23,42,.5);font-size:14.5px;line-height:1.55}' +
    '#fhBox *{box-sizing:border-box}' +
    '.fhHead{position:sticky;top:0;z-index:1;display:flex;align-items:flex-start;gap:12px;padding:20px 20px 14px;background:var(--bg);border-bottom:1px solid var(--border)}' +
    '.fhHead h2{margin:0;font-size:22px;line-height:1.2;font-weight:700;letter-spacing:-.01em}' +
    '.fhHead p{margin:4px 0 0;color:var(--muted);font-size:13.5px}' +
    '.fhX{margin-left:auto;flex:none;width:36px;height:36px;border-radius:12px;border:1px solid var(--border);background:var(--card);color:var(--muted);cursor:pointer;display:flex;align-items:center;justify-content:center}' +
    '.fhX:hover{color:var(--text)}' +
    '.fhBody{padding:16px 20px 20px;display:flex;flex-direction:column;gap:12px}' +
    '.fhNote{margin:0;font-weight:600;font-size:13.5px}' +
    '.fhCard{border:1px solid var(--border);border-radius:16px;background:var(--card);overflow:hidden}' +
    '.fhCard.open{border-color:var(--accent)}' +
    '.fhCardHead{width:100%;display:flex;align-items:center;gap:12px;padding:16px;background:none;border:0;color:inherit;font:inherit;text-align:left;cursor:pointer}' +
    '.fhCardHead:focus-visible,.fhX:focus-visible,.fhBtn:focus-visible,.fhLink:focus-visible{outline:2px solid var(--accent);outline-offset:2px}' +
    '.fhCardTitle{display:block;font-size:17px;font-weight:700}' +
    '.fhCardSub{display:block;color:var(--muted);font-size:13px}' +
    '.fhChev{margin-left:auto;flex:none;color:var(--muted);transition:transform .15s}' +
    '.fhCard.open .fhChev{transform:rotate(180deg)}' +
    '.fhCardBody{padding:0 16px 18px}' +
    '.fhCardBody[hidden]{display:none}' +
    '.fhTip{background:var(--tipBg);border:1px solid var(--tipBorder);color:var(--tipText);border-radius:12px;padding:10px 12px;font-size:13px;margin-bottom:6px}' +
    '.fhTip b{display:block;margin-top:4px}' +
    '.fhProb{padding-top:16px}' +
    '.fhProb+.fhProb{border-top:1px solid var(--border);margin-top:16px}' +
    '.fhProb h3{margin:0;font-size:15px;font-weight:700;color:var(--accent)}' +
    '.fhProb h4{margin:10px 0 2px;font-size:11.5px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--muted)}' +
    '.fhProb p{margin:0}' +
    '.fhProb ol{margin:0;padding-left:22px;list-style:decimal}.fhProb li{margin:2px 0;display:list-item}' +
    '.fhProb.flash{animation:fhFlash 1.4s ease-out}' +
    '@keyframes fhFlash{0%{background:var(--tonal)}100%{background:transparent}}' +
    '.fhCookies{border:1px solid var(--border);border-radius:16px;background:var(--card);padding:16px}' +
    '.fhCookies h3{margin:0;font-size:15px;font-weight:700}.fhCookies p{margin:4px 0 0;color:var(--muted);font-size:13px}' +
    '.fhRow{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:10px}' +
    '.fhRow input[type=file]{font-size:12.5px;max-width:100%;min-width:0;flex:1 1 200px}' +
    '.fhBtn{border:1px solid var(--border);border-radius:10px;background:var(--card);color:var(--text);padding:7px 12px;font:inherit;font-size:13px;font-weight:600;cursor:pointer}' +
    '.fhBtn:hover{border-color:var(--accent)}' +
    '#helpButton{border-radius:8px;padding:6px 12px;font-size:12px;margin-right:8px;color:#475569}html.dark #helpButton{color:#cbd5e1}' +
    '.fhBtn.primary{background:var(--accent);border-color:var(--accent);color:#fff}' +
    'html.dark .fhBtn.primary{color:#0b101c}' +
    '.fhStatus{margin-top:10px!important;font-size:12.5px!important}' +
    '.fhFoot{display:flex;gap:8px;flex-wrap:wrap}' +
    '.fhLink{margin-left:8px;font-size:12.5px;font-weight:600;text-decoration:underline;cursor:pointer;background:none;border:0;color:#4f46e5;padding:0}' +
    'html.dark .fhLink{color:#a5b4fc}' +
    '@media (max-width:520px){#fhOverlay{padding:0}#fhBox{max-height:100vh;border-radius:0;height:100%}}';

  function el(tag, props, kids) {
    var e = document.createElement(tag);
    if (props) for (var k in props) { if (k === 'text') e.textContent = props[k]; else e.setAttribute(k, props[k]); }
    (kids || []).forEach(function (c) { e.appendChild(c); });
    return e;
  }

  var CHEVRON = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';
  var CLOSE = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';

  var overlay, box, lastError = '';

  function setOpen(card, open) {
    card.classList.toggle('open', open);
    card.querySelector('.fhCardHead').setAttribute('aria-expanded', open ? 'true' : 'false');
    card.querySelector('.fhCardBody').hidden = !open;
  }

  function buildCard(c) {
    var card = el('section', { 'class': 'fhCard', id: 'help-card-' + c.id });
    var head = el('button', { type: 'button', 'class': 'fhCardHead', 'aria-expanded': 'false' }, [
      el('span', null, [el('span', { 'class': 'fhCardTitle', text: c.title }), el('span', { 'class': 'fhCardSub', text: c.subtitle })])
    ]);
    var chev = el('span', { 'class': 'fhChev', 'aria-hidden': 'true' }); chev.innerHTML = CHEVRON;
    head.appendChild(chev);
    var body = el('div', { 'class': 'fhCardBody', hidden: '' });
    if (c.tip) {
      var tip = el('div', { 'class': 'fhTip' }, [document.createTextNode(TIP), el('b', { text: COOKIE_NOTE })]);
      body.appendChild(tip);
    }
    c.problems.forEach(function (p) {
      var d = el('div', { 'class': 'fhProb', id: 'help-' + p.id });
      d.appendChild(el('h3', { text: p.title }));
      d.appendChild(el('h4', { text: 'What you see' })); d.appendChild(el('p', { text: p.see }));
      d.appendChild(el('h4', { text: 'Why' })); d.appendChild(el('p', { text: p.why }));
      d.appendChild(el('h4', { text: 'How to fix' }));
      var ol = el('ol');
      p.fix.forEach(function (s) { ol.appendChild(el('li', { text: s })); });
      d.appendChild(ol);
      body.appendChild(d);
    });
    head.addEventListener('click', function () { setOpen(card, !card.classList.contains('open')); });
    card.appendChild(head); card.appendChild(body);
    return card;
  }

  function build() {
    var st = el('style'); st.textContent = css; document.head.appendChild(st);
    box = el('div', { id: 'fhBox', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'Help' });

    var x = el('button', { type: 'button', 'class': 'fhX', 'aria-label': 'Close help' }); x.innerHTML = CLOSE;
    x.addEventListener('click', closeHelp);
    box.appendChild(el('div', { 'class': 'fhHead' }, [
      el('div', null, [el('h2', { text: 'Help' }), el('p', { text: 'Click a card to open it. Each problem says what you see, why it happens, and how to fix it.' })]),
      x
    ]));

    var body = el('div', { 'class': 'fhBody' });
    body.appendChild(el('p', { 'class': 'fhNote', text: COOKIE_NOTE }));
    ['Playlists: paste a playlist link, tick the videos you want, pick one quality, then press Download.',
      'They are saved in Downloads, in a folder named after the playlist, with a number in front of each name.',
      'Videos you already have, and private or deleted ones, are skipped. Use "Retry failed" at the end.'
    ].forEach(function (line) { body.appendChild(el('p', { style: 'margin:0;font-size:13.5px;color:var(--muted)', text: line })); });
    CARDS.forEach(function (c) { body.appendChild(buildCard(c)); });
    body.appendChild(buildCookies());

    var copy = el('button', { type: 'button', 'class': 'fhBtn', text: 'Copy error details' });
    copy.addEventListener('click', function () { copyDetails(copy); });
    var close = el('button', { type: 'button', 'class': 'fhBtn', text: 'Close' });
    close.addEventListener('click', closeHelp);
    body.appendChild(el('div', { 'class': 'fhFoot' }, [copy, close]));
    box.appendChild(body);

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

  var refreshCookies = function () {};

  function buildCookies() {
    var wrap = el('section', { 'class': 'fhCookies', id: 'help-cookies' });
    wrap.appendChild(el('h3', { text: 'Cookies' }));
    wrap.appendChild(el('p', { text: 'Only needed when a site asks for a login. Log in to the site in your browser first. Cookies stay on this computer.' }));
    var status = el('p', { 'class': 'fhStatus', role: 'status' });
    var sel = el('select', { 'aria-label': 'Browser', 'class': 'fhBtn' });
    ['firefox', 'chrome', 'edge', 'brave', 'opera', 'chromium', 'vivaldi'].forEach(function (b) {
      sel.appendChild(el('option', { value: b, text: b === 'firefox' ? 'Firefox (best)' : b.charAt(0).toUpperCase() + b.slice(1) }));
    });
    var useBtn = el('button', { type: 'button', 'class': 'fhBtn primary', text: 'Use browser cookies' });
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
    refreshCookies = function () { api('GET', '/api/cookies').then(show, fail); };
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

  // Open the guide. With a problem id, open its card and scroll to it.
  function openHelp(topicId) {
    var cards = overlay.querySelectorAll('.fhCard');
    for (var i = 0; i < cards.length; i++) setOpen(cards[i], false);
    overlay.classList.add('open');
    box.scrollTop = 0;
    refreshCookies();
    if (!topicId) return;
    var d = document.getElementById('help-' + topicId);
    if (!d) return;
    setOpen(d.closest('.fhCard'), true);
    d.scrollIntoView({ block: 'start' });
    d.classList.remove('flash'); void d.offsetWidth; d.classList.add('flash');
  }
  function closeHelp() { overlay.classList.remove('open'); }

  function details(msg) {
    var tv = document.getElementById('toolVersions');
    return [
      'Fetchy error details',
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
