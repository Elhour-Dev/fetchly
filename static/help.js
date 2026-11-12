/* Fetchy Help guide. Plain JS, no libraries, works offline.
   One section per topic, each with short numbered steps: "How to" first, then "Fix a problem".
   Same content and structure as the mobile app.
   Also adds: Help button, "How to fix" links, "Copy error details", the Cookies box (in the login section),
   and the one-time "Log in to YouTube first?" question. */
(function () {
  'use strict';

  var TIP = 'Tip: use a second account, not your main one. It is safer if the site flags the login.';
  var COOKIE_NOTE = 'Cookies are like a password. Never share them.';

  // "How to" sections: short steps, and a note under them. The login section also holds the Cookies box.
  var GUIDES = [
    {
      id: 'download', title: 'Download a video',
      steps: ['Copy the video link from your browser\'s address bar.', 'Click Paste in Fetchy.', 'Pick a quality.', 'Click "Download video".'],
      note: 'The file goes to your Downloads folder.'
    },
    {
      id: 'playlist', title: 'Download a playlist',
      steps: ['Paste a playlist link.', 'Tick the videos you want.', 'Pick one quality, then click Download.', 'If some fail, click "Retry failed" at the end.'],
      note: 'Each playlist gets its own folder in Downloads. Videos you already have are skipped.'
    },
    {
      id: 'login', title: 'Log in to YouTube or Instagram', cookies: true,
      steps: ['Log in to the site in Firefox on this computer.', 'Below, pick Firefox and click "Use browser cookies".', 'Paste the link again, or click "Try again".'],
      note: 'Chrome and Edge often cannot share their cookies (close the browser first). Firefox works best. You can also upload a cookies.txt file. ' + TIP + ' ' + COOKIE_NOTE
    },
    {
      id: 'find', title: 'Find your videos',
      steps: ['Open File Explorer.', 'Go to Downloads.', 'Sort by date, newest first.'],
      note: 'A name that already exists gets a number, like "video (1).mp4".'
    },
    {
      id: 'tools', title: 'Keep downloads working',
      steps: ['Click "Update tools" at the bottom of the page.', 'Wait until it finishes.', 'Try the link again.'],
      note: 'Sites change often. Updating the tools fixes most new errors.'
    }
  ];

  // One problem, one section: the title is what you see, why is one plain sentence, fix are short steps.
  // match: regex on the error text (picks the "How to fix" link).
  var PROBLEMS = [
    {
      id: 'g_browser_cookies', title: '"Could not copy cookie database" (Edge, Chrome, Brave)',
      match: /cookie database|decrypt|dpapi|cannot read .{0,20}cookies/i,
      why: 'Edge, Chrome and Brave lock their cookies while they are open, and newer versions hide them from other programs. The message says "Chrome" even for Edge.',
      fix: ['Best: log in with Firefox and pick Firefox in the Cookies box below.', 'Or upload a cookies.txt file.', 'Or click "Remove cookies" to go on without a login, then "Try again".']
    },
    {
      id: 'yt_bot', title: 'YouTube: "Sign in to confirm you\'re not a bot"',
      match: /not a bot|confirm you.?re not/i,
      why: 'YouTube does not trust the connection. A VPN makes it worse.',
      fix: ['Turn off any VPN or proxy.', 'Log in to YouTube in Firefox, then use browser cookies (see "Log in to YouTube or Instagram").', 'Click "Update tools", then "Try again".']
    },
    {
      id: 'yt_unavail', title: 'YouTube: "Video unavailable"',
      match: /video unavailable|age[- ]restrict|confirm your age|inappropriate|has been terminated|country|geo[- ]?(restrict|block)|your region|not available in your|blocked it in your/i,
      why: 'The video is private, removed, blocked where you are, or for adults only.',
      fix: ['Open the link in your browser. If it does not play there, Fetchy cannot get it.', 'For adults only: log in with an adult account and use browser cookies, then try again.', 'Removed or private: only the owner can fix that.']
    },
    {
      id: 'ig_login', title: '"Login required"',
      match: /log ?in|sign ?in|login required|authenticat|cookies|rate-limit|requires an account|members?[- ]only|need to log/i,
      why: 'The site only shows this to people who are logged in.',
      fix: ['Log in to the site in Firefox.', 'Use browser cookies (see "Log in to YouTube or Instagram").', 'Click "Try again".']
    },
    {
      id: 'ig_empty', title: 'Instagram: empty result',
      match: /empty media response|no video in this post|there is no video/i,
      why: 'You are not logged in, or the post has only photos.',
      fix: ['Log in and use browser cookies, then try again.', 'Check that the post is a video or a Reel.', 'Wait a few minutes, then try again.']
    },
    {
      id: 'ig_private', title: 'Private or deleted post',
      match: /private|deleted|removed|no longer available|content is not available/i,
      why: 'The owner made it private or deleted it.',
      fix: ['Open the link in your browser. If you cannot see it, Fetchy cannot either.', 'Private: log in with an account that can see it, use browser cookies, then try again.', 'Deleted: it is gone for good.']
    },
    {
      id: 'g_unsupported', title: 'Link not supported',
      match: /unsupported url|aren.t allowed|private network|local and private|not supported|no video|videos only|could not find|no media|not a valid|invalid url|no supported/i,
      why: 'The link is not a video page, is cut off, or the site is not supported.',
      fix: ['Copy the link of the video itself from the address bar.', 'Paste the whole link. It starts with https://', 'Photos do not work. Fetchy downloads videos.']
    },
    {
      id: 'g_ffmpeg', title: 'No sound',
      match: /ffmpeg|ffprobe|postprocess|merg(e|ing)|no audio|no sound/i,
      why: 'Sites often send video and sound apart, and joining them failed.',
      fix: ['Click "Try again".', 'Free some disk space.', 'Click "Update tools".', 'Still broken? Install the latest Fetchy.']
    },
    {
      id: 'g_network', title: 'Slow or failed download',
      match: /timed out|timeout|too long|connection|proxy|ssl|certificate|network|failed to fetch|unreachable|429|too many requests|temporar|resolve/i,
      why: 'Weak connection, a VPN, or the site is limiting you.',
      fix: ['Check that the internet works in your browser.', 'Turn off VPN or proxy.', 'Wait a few minutes, then click "Try again".', 'Try another network.']
    },
    {
      id: 'g_update', title: '"Unable to extract" or "update yt-dlp"',
      match: /unable to extract|update yt-dlp|outdated|nsig|signature|extractor error|please report this issue|sabr|player response/i,
      why: 'The site changed, and the tools need an update.',
      fix: ['Click "Update tools" at the bottom of the page.', 'Wait until it finishes.', 'Click "Try again".']
    },
    {
      id: 'g_format', title: '"Format not available"',
      match: /format.{0,20}(is )?not available|requested format|no such format|no formats/i,
      why: 'The site gave no format Fetchy can use. Often it needs a login.',
      fix: ['Pick another quality, or the best one.', 'Click "Update tools".', 'Log in (see "Log in to YouTube or Instagram").', 'Click "Try again".']
    }
  ];

  // Order matters: the first match wins.
  var ORDER = ['g_browser_cookies', 'yt_bot', 'g_format', 'g_ffmpeg', 'yt_unavail', 'g_unsupported', 'ig_private', 'ig_empty', 'ig_login', 'g_update', 'g_network'];

  function problemById(id) {
    for (var i = 0; i < PROBLEMS.length; i++) if (PROBLEMS[i].id === id) return PROBLEMS[i];
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
    '#fhOverlay,#fhAsk,#helpButton{--bg:#f8fafc;--card:#fff;--border:#e2e8f0;--text:#0f172a;--muted:#64748b;--accent:#4f46e5;--tonal:#eef2ff;--tonalText:#4338ca;--tipBg:#fffbeb;--tipBorder:#fde68a;--tipText:#92400e}' +
    '#fhOverlay,#fhAsk{position:fixed;inset:0;z-index:100;background:rgba(15,23,42,.55);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;padding:16px;font-family:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}' +
    'html.dark #fhOverlay,html.dark #fhAsk,html.dark #helpButton{--bg:#080b14;--card:rgba(255,255,255,.04);--border:rgba(255,255,255,.1);--text:#f1f5f9;--muted:#94a3b8;--accent:#818cf8;--tonal:rgba(99,102,241,.15);--tonalText:#c7d2fe;--tipBg:rgba(245,158,11,.1);--tipBorder:rgba(245,158,11,.25);--tipText:#fcd34d}' +
    '#fhOverlay.open,#fhAsk.open{display:flex}' +
    '#fhAsk{z-index:110}' +
    '.fhAskBox{background:var(--bg);color:var(--text);max-width:420px;width:100%;border-radius:20px;border:1px solid var(--border);box-shadow:0 28px 70px -24px rgba(15,23,42,.5);padding:22px;font-size:14.5px;line-height:1.55}' +
    '.fhAskBox h2{margin:0;font-size:19px;font-weight:700}.fhAskBox p{margin:8px 0 0;color:var(--muted)}' +
    '.fhAskBox .fhFoot{margin-top:18px}.fhAskBox .fhBtn{padding:9px 16px}' +
    '#fhBox{background:var(--bg);color:var(--text);max-width:720px;width:100%;max-height:92vh;overflow-y:auto;overflow-x:hidden;border-radius:20px;border:1px solid var(--border);box-shadow:0 28px 70px -24px rgba(15,23,42,.5);font-size:14.5px;line-height:1.55}' +
    '#fhBox *{box-sizing:border-box}' +
    '.fhHead{position:sticky;top:0;z-index:1;display:flex;align-items:flex-start;gap:12px;padding:20px 20px 14px;background:var(--bg);border-bottom:1px solid var(--border)}' +
    '.fhHead h2{margin:0;font-size:22px;line-height:1.2;font-weight:700;letter-spacing:-.01em}' +
    '.fhHead p{margin:4px 0 0;color:var(--muted);font-size:13.5px}' +
    '.fhX{margin-inline-start:auto;flex:none;width:36px;height:36px;border-radius:12px;border:1px solid var(--border);background:var(--card);color:var(--muted);cursor:pointer;display:flex;align-items:center;justify-content:center}' +
    '.fhX:hover{color:var(--text)}' +
    '.fhBody{padding:16px 20px 20px;display:flex;flex-direction:column;gap:8px}' +
    '.fhGroup{margin:10px 0 2px;font-size:11.5px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--muted)}' +
    '.fhGroup:first-child{margin-top:0}' +
    '.fhCard{border:1px solid var(--border);border-radius:16px;background:var(--card);overflow:hidden}' +
    '.fhCard{scroll-margin-top:96px}' +
    '.fhCard.open{border-color:var(--accent)}' +
    '.fhCardHead{width:100%;display:flex;align-items:center;gap:12px;padding:14px 16px;background:none;border:0;color:inherit;font:inherit;text-align:start;cursor:pointer}' +
    '.fhCardHead:focus-visible,.fhX:focus-visible,.fhBtn:focus-visible,.fhLink:focus-visible,.fhAskBox .fhBtn:focus-visible{outline:2px solid var(--accent);outline-offset:2px}' +
    '.fhCardTitle{display:block;font-size:15px;font-weight:700}' +
    '.fhChev{margin-inline-start:auto;flex:none;color:var(--muted);transition:transform .15s}' +
    '.fhCard.open .fhChev{transform:rotate(180deg)}' +
    '.fhCardBody{padding:0 16px 16px}' +
    '.fhWhy{margin:0;color:var(--muted)}' +
    '.fhCardBody ol{margin:6px 0 0;padding-inline-start:22px;list-style:decimal}.fhCardBody li{margin:3px 0;display:list-item}' +
    '.fhCardBody[hidden]{display:none}' +
    '.fhTip{background:var(--tipBg);border:1px solid var(--tipBorder);color:var(--tipText);border-radius:12px;padding:10px 12px;font-size:13px;margin-top:12px}' +
    '.fhCard.flash{animation:fhFlash 1.4s ease-out}' +
    '@keyframes fhFlash{0%{background:var(--tonal)}100%{background:var(--card)}}' +
    '.fhCookies{border:1px solid var(--border);border-radius:12px;padding:14px;margin-top:12px}' +
    '.fhCookies h3{margin:0;font-size:15px;font-weight:700}.fhCookies p{margin:4px 0 0;color:var(--muted);font-size:13px}' +
    '.fhRow{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:10px}' +
    '.fhRow input[type=file]{font-size:12.5px;max-width:100%;min-width:0;flex:1 1 200px}' +
    '.fhBtn{border:1px solid var(--border);border-radius:10px;background:var(--card);color:var(--text);padding:7px 12px;font:inherit;font-size:13px;font-weight:600;cursor:pointer}' +
    '.fhBtn:hover{border-color:var(--accent)}' +
    '#helpButton{height:40px;border-radius:12px;padding:0 16px;font-size:13px;color:#475569}html.dark #helpButton{color:#cbd5e1}' +
    '.fhBtn.primary{background:var(--accent);border-color:var(--accent);color:#fff}' +
    'html.dark .fhBtn.primary{color:#0b101c}' +
    '.fhStatus{margin-top:10px!important;font-size:12.5px!important}' +
    '.fhFoot{display:flex;gap:8px;flex-wrap:wrap}' +
    '.fhHolder{display:flex;align-items:center;gap:8px;margin-top:10px}' +
    '.fhHolder:empty{display:none}' +
    '.fhLink{font-size:12px;font-weight:600;cursor:pointer;border:0;border-radius:8px;padding:6px 12px;background:#eef2ff;color:#4338ca}' +
    '.fhLink:hover{background:#e0e7ff}' +
    '.fhLink.fhGhost{margin-inline-start:auto;background:none;color:#64748b}.fhLink.fhGhost:hover{background:rgba(100,116,139,.12)}' +
    'html.dark .fhLink{background:rgba(99,102,241,.15);color:#c7d2fe}html.dark .fhLink:hover{background:rgba(99,102,241,.25)}' +
    'html.dark .fhLink.fhGhost{background:none;color:#94a3b8}html.dark .fhLink.fhGhost:hover{background:rgba(255,255,255,.08)}' +
    '@media (max-width:520px){#fhOverlay{padding:0}#fhBox{max-height:100vh;border-radius:0;height:100%}}';

  function el(tag, props, kids) {
    var e = document.createElement(tag);
    if (props) for (var k in props) { if (k === 'text') e.textContent = props[k]; else e.setAttribute(k, props[k]); }
    (kids || []).forEach(function (c) { e.appendChild(c); });
    return e;
  }

  var CHEVRON = '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';
  var CLOSE = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18"/></svg>';

  var overlay, box, ask, lastError = '';
  var afterClose = null;   // runs when Help closes (the link waiting for the login question)

  function setOpen(card, open) {
    card.classList.toggle('open', open);
    card.querySelector('.fhCardHead').setAttribute('aria-expanded', open ? 'true' : 'false');
    card.querySelector('.fhCardBody').hidden = !open;
  }

  // One topic: a title button that opens and closes it, then why (problems), the steps, a note, and extra content.
  function buildSection(id, title, why, steps, note, extra) {
    var card = el('section', { 'class': 'fhCard', id: 'help-' + id });
    var head = el('button', { type: 'button', 'class': 'fhCardHead', 'aria-expanded': 'false' }, [
      el('span', { 'class': 'fhCardTitle', text: title })
    ]);
    var chev = el('span', { 'class': 'fhChev', 'aria-hidden': 'true' }); chev.innerHTML = CHEVRON;
    head.appendChild(chev);
    var body = el('div', { 'class': 'fhCardBody', hidden: '' });
    if (why) body.appendChild(el('p', { 'class': 'fhWhy', text: why }));
    var ol = el('ol');
    steps.forEach(function (s) { ol.appendChild(el('li', { text: s })); });
    body.appendChild(ol);
    if (note) body.appendChild(el('div', { 'class': 'fhTip', text: note }));
    if (extra) body.appendChild(extra);
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
      el('div', null, [el('h2', { text: 'Help' }), el('p', { text: 'Click a topic to open it.' })]),
      x
    ]));

    var body = el('div', { 'class': 'fhBody' });
    body.appendChild(el('h3', { 'class': 'fhGroup', text: 'How to' }));
    GUIDES.forEach(function (g) { body.appendChild(buildSection(g.id, g.title, null, g.steps, g.note, g.cookies ? buildCookies() : null)); });
    body.appendChild(el('h3', { 'class': 'fhGroup', text: 'Fix a problem' }));
    PROBLEMS.forEach(function (p) { body.appendChild(buildSection(p.id, p.title, p.why, p.fix, null, null)); });

    var copy = el('button', { type: 'button', 'class': 'fhBtn', text: 'Copy error details' });
    copy.addEventListener('click', function () { copyDetails(copy); });
    var close = el('button', { type: 'button', 'class': 'fhBtn', text: 'Close' });
    close.addEventListener('click', closeHelp);
    body.appendChild(el('div', { 'class': 'fhFoot', style: 'margin-top:8px' }, [copy, close]));
    box.appendChild(body);

    overlay = el('div', { id: 'fhOverlay' }, [box]);
    overlay.addEventListener('click', function (e) { if (e.target === overlay) closeHelp(); });
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      if (ask.classList.contains('open')) closeAsk(true); else closeHelp();
    });
    document.body.appendChild(overlay);
    buildAsk();
  }

  // ---- "Log in to YouTube first?" Asked for a YouTube link while no login is set up (once per link) ----
  var YT_HOST = /(^|\.)(youtube\.com|youtu\.be)$/i;
  var askCont = null, askBusy = false, noCookieApi = false, askedLink = '';

  function buildAsk() {
    var yes = el('button', { type: 'button', 'class': 'fhBtn primary', text: 'Log in' });
    var no = el('button', { type: 'button', 'class': 'fhBtn', text: 'Not now' });
    yes.addEventListener('click', function () {
      if (window.fetchyDesktop && window.fetchyDesktop.youtubeLogin) {   // the app: log in inside a window, then retry
        yes.disabled = true;
        window.fetchyDesktop.youtubeLogin().then(function (text) {
          if (!text) return;
          return api('DELETE', '/api/cookies').then(function () { return api('POST', '/api/cookies/file', { text: text }); });
        }).catch(function () {}).then(function () { yes.disabled = false; closeAsk(true); });
        return;
      }
      afterClose = askCont; askCont = null;   // the link goes on when Help closes
      closeAsk(false);
      openHelp('login');
    });
    no.addEventListener('click', function () { closeAsk(true); });
    ask = el('div', { id: 'fhAsk', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'fhAskTitle' }, [
      el('div', { 'class': 'fhAskBox' }, [
        el('h2', { id: 'fhAskTitle', text: 'Log in to YouTube first?' }),
        el('p', { text: 'YouTube often blocks downloads when you are not logged in. Set up your login once now, and your downloads work later.' }),
        el('p', { text: TIP }),
        el('div', { 'class': 'fhFoot' }, [yes, no])
      ])
    ]);
    document.body.appendChild(ask);
  }

  function closeAsk(goOn) {
    ask.classList.remove('open');
    var f = askCont; askCont = null;
    if (goOn && f) f();
  }

  // True when Fetchy asks first: then cont() runs after the answer. False: go on now.
  function askLogin(url, cont) {
    var host = '';
    try { host = new URL(url).hostname; } catch (e) { return false; }
    if (noCookieApi || !YT_HOST.test(host)) return false;
    if (url === askedLink) return false;   // already asked for this link
    askCont = cont;   // a newer link replaces the waiting one
    if (askBusy || ask.classList.contains('open')) return true;
    askBusy = true;
    api('GET', '/api/cookies').then(function (j) {
      askBusy = false;
      if (j.file || j.browser) { closeAsk(true); return; }   // a login is already set up
      askedLink = url;
      ask.classList.add('open');
      ask.querySelector('.primary').focus();
    }, function () {   // no login settings here (for example the web version): just go on
      askBusy = false; noCookieApi = true; closeAsk(true);
    });
    return true;
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
    var hint = el('p', { 'class': 'fhStatus', text: '' });
    function showHint() { hint.textContent = /^(chrome|edge|brave|chromium|opera|vivaldi)$/.test(sel.value) ? 'Newer versions of this browser block this, even when it is closed. Firefox works best.' : ''; }
    sel.addEventListener('change', showHint);
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
    wrap.appendChild(hint);
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

  // Open the guide. With a topic id, open that section and scroll to it.
  function openHelp(topicId) {
    var cards = overlay.querySelectorAll('.fhCard');
    for (var i = 0; i < cards.length; i++) setOpen(cards[i], false);
    overlay.classList.add('open');
    box.scrollTop = 0;
    refreshCookies();
    var d = topicId && document.getElementById('help-' + topicId);
    if (!d) { box.querySelector('.fhX').focus(); return; }
    setOpen(d, true);
    d.scrollIntoView({ block: 'start' });
    d.querySelector('.fhCardHead').focus({ preventScroll: true });
    d.classList.remove('flash'); void d.offsetWidth; d.classList.add('flash');
  }
  function closeHelp() {
    if (!overlay.classList.contains('open')) return;
    overlay.classList.remove('open');
    var f = afterClose; afterClose = null;
    if (f) f();
  }

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
    var node = typeof textId === 'string' ? document.getElementById(textId) : textId;   // an id, or the element itself
    if (!node || !node.parentNode) return;
    var holder = el('div', { 'class': 'fhHolder' });
    node.parentNode.insertBefore(holder, node.nextSibling);
    function refresh() {
      var msg = (window.i18n ? window.i18n.orig(node) : node.textContent).trim();   // English text, even when the page is translated
      holder.textContent = '';
      if (!msg || /^Not supported:/.test(msg)) return;   // nothing to fix for a site Fetchy cannot download from
      lastError = msg;
      var id = findTopic(msg);
      if (id) {
        var a = el('button', { type: 'button', 'class': 'fhLink', 'data-help-topic': id, text: 'How to fix' });
        a.addEventListener('click', function () { openHelp(id); });
        holder.appendChild(a);
      }
      var c = el('button', { type: 'button', 'class': 'fhLink fhGhost', text: 'Copy error details' });
      c.addEventListener('click', function () { copyDetails(c, msg); });
      holder.appendChild(c);
    }
    new MutationObserver(refresh).observe(node, { childList: true, characterData: true, subtree: true });
    refresh();
  }

  function init() {
    build();
    var help = el('button', { type: 'button', id: 'helpButton', 'class': 'fhBtn', text: 'Help' });
    help.addEventListener('click', function () { openHelp(); });
    var theme = document.getElementById('themeToggle');
    if (theme && theme.parentNode) {
      var wrap = el('div', { style: 'display:flex;align-items:center;gap:8px' });
      theme.parentNode.insertBefore(wrap, theme);
      wrap.appendChild(help); wrap.appendChild(theme);
    } else { help.style.cssText = 'position:fixed;bottom:12px;right:12px;z-index:50'; document.body.appendChild(help); }
    watch('videoErrorText');
    window.fetchlyHelp = { open: openHelp, find: findTopic, askLogin: askLogin, watch: watch };
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
