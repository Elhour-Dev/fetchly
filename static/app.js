/* Fetchy frontend - vanilla JS, no build step. */
(function () {
  'use strict';

  // ---------------------------------------------------------------------
  // Element references
  // ---------------------------------------------------------------------
  const $ = (id) => document.getElementById(id);
  const els = {
    themeToggle: $('themeToggle'),
    videoPanel: $('videoPanel'),

    videoForm: $('videoForm'),
    videoUrl: $('videoUrl'),
    videoPlaylist: $('videoPlaylist'),
    videoPlaylistRow: $('videoPlaylistRow'),
    videoPaste: $('videoPaste'),
    videoClear: $('videoClear'),
    videoHint: $('videoHint'),
    videoUrlError: $('videoUrlError'),
    videoEmpty: $('videoEmpty'),
    videoSkeleton: $('videoSkeleton'),
    videoErrorCard: $('videoErrorCard'),
    videoErrorText: $('videoErrorText'),
    videoRetry: $('videoRetry'),
    videoErrorDownload: $('videoErrorDownload'),
    videoResult: $('videoResult'),
    videoThumb: $('videoThumb'),
    videoThumbFallback: $('videoThumbFallback'),
    videoDuration: $('videoDuration'),
    videoTitle: $('videoTitle'),
    videoUploader: $('videoUploader'),
    videoMetaDot: $('videoMetaDot'),
    videoSource: $('videoSource'),
    videoQuality: $('videoQuality'),
    videoQualityBlock: $('videoQualityBlock'),
    formatSize: $('formatSize'),
    formatDetail: $('formatDetail'),
    videoDownload: $('videoDownload'),
    ffmpegWarning: $('ffmpegWarning'),
    updateTools: $('updateTools'),
    updateStatus: $('updateStatus'),
    toolVersions: $('toolVersions'),
    videoLive: $('videoLive'),

    presetRow: $('presetRow'),
    subsRow: $('subsRow'),
    subsCheck: $('subsCheck'),
    subsLang: $('subsLang'),
    notifyCheck: $('notifyCheck'),
    historySection: $('historySection'),
    historyCount: $('historyCount'),
    historyList: $('historyList'),
    historyClear: $('historyClear'),

    toasts: $('toasts'),
  };

  // Small per-device settings (quick quality, subtitles, notify). Never fatal if storage is blocked.
  const pref = {
    get(key, fallback) {
      try { const v = localStorage.getItem(`fetchly-${key}`); return v === null ? fallback : v; } catch (_) { return fallback; }
    },
    set(key, value) {
      try { localStorage.setItem(`fetchly-${key}`, value); } catch (_) {}
    },
  };

  // ---------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------
  const state = {
    health: null,
    video: {
      phase: 'idle',
      requestSeq: 0,
      controller: null,
      jobId: null,
      data: null,
      fetchTimer: null,     // pending auto-fetch after typing
      lastFetched: null,    // the link the current result or error belongs to
    },
  };

  const INITIAL_BUTTON_LABELS = {
    videoDownload: 'Download video',
    videoErrorDownload: 'Download best quality',
  };

  // ---------------------------------------------------------------------
  // Tiny API client with timeout + friendly errors
  // ---------------------------------------------------------------------
  async function api(path, options = {}) {
    const { method = 'GET', body, signal, timeout = 90000 } = options;
    const controller = new AbortController();
    let timedOut = false;
    const relayAbort = () => controller.abort();
    if (signal) {
      if (signal.aborted) controller.abort();
      else signal.addEventListener('abort', relayAbort, { once: true });
    }
    const timer = setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, timeout);

    try {
      const headers = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      const response = await fetch(path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
      let data = {};
      const contentType = response.headers.get('content-type') || '';
      if (contentType.includes('application/json')) {
        data = await response.json().catch(() => ({}));
      }
      if (!response.ok) {
        const message = data.message || data.detail || 'That request did not work. Please try again.';
        const error = new Error(message);
        error.status = response.status;
        error.data = data;
        throw error;
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError' && timedOut) {
        const timeoutError = new Error('That request took too long. Please try again.');
        timeoutError.name = 'TimeoutError';
        throw timeoutError;
      }
      throw error;
    } finally {
      clearTimeout(timer);
      if (signal) signal.removeEventListener('abort', relayAbort);
    }
  }

  // ---------------------------------------------------------------------
  // Formatting
  // ---------------------------------------------------------------------
  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes < 0) return '';
    if (bytes < 1024) return `${Math.round(bytes)} B`;
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = bytes / 1024;
    let index = 0;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index += 1;
    }
    const precision = value >= 100 ? 0 : value >= 10 ? 1 : 1;
    return `${value.toFixed(precision)} ${units[index]}`;
  }

  function formatDuration(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return '';
    const total = Math.round(seconds);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;
    if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
    return `${minutes}:${String(secs).padStart(2, '0')}`;
  }

  function formatEta(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return '';
    if (seconds < 60) return `${Math.max(1, Math.round(seconds))}s`;
    const minutes = Math.floor(seconds / 60);
    const secs = Math.round(seconds % 60);
    return secs ? `${minutes}m ${secs}s` : `${minutes}m`;
  }

  function shortenHost(url) {
    try {
      return new URL(url).hostname.replace(/^www\./, '');
    } catch (_) {
      return '';
    }
  }

  function normalizeUrlInput(value) {
    const trimmed = value.trim();
    if (!trimmed) return '';
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) return trimmed;
    if (/^[\w.-]+\.[a-z]{2,}(?::\d+)?(?:[/?#]|$)/i.test(trimmed)) return `https://${trimmed}`;
    return trimmed;
  }

  function validateUrl(value) {
    if (!value) return 'Paste a link first.';
    let parsed;
    try {
      parsed = new URL(value);
    } catch (_) {
      return "That doesn't look like a valid URL.";
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return 'Use an http:// or https:// link.';
    }
    if (!parsed.hostname) return 'That URL is missing a domain name.';
    if (!parsed.hostname.includes('.') && !/^\d{1,3}(?:\.\d{1,3}){3}$/.test(parsed.hostname)) {
      return 'Enter a full public web address.';
    }
    return '';
  }

  // Sites Fetchy cannot download from: say so at once instead of trying (same text as fetchy/urls.py).
  function unsupportedLink(value) {
    let parsed;
    try { parsed = new URL(value); } catch (_) { return ''; }
    const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
    const on = (site) => host === site || host.endsWith(`.${site}`);
    if (on('snapchat.com')) return "Not supported: Snapchat links can't be downloaded with Fetchy.";
    if (on('instagram.com') && /^\/(stories|s)\//i.test(parsed.pathname)) {
      return "Not supported: Instagram stories can't be downloaded with Fetchy.";
    }
    return '';
  }

  // ---------------------------------------------------------------------
  // Toasts
  // ---------------------------------------------------------------------
  const TOAST_ICONS = {
    success: '<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 12 4.5 4.5L19 7"/></svg>',
    error: '<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="M12 8v5m0 3h.01"/><circle cx="12" cy="12" r="9"/></svg>',
    info: '<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M12 11v5m0-8h.01"/></svg>',
  };

  const TOAST_STYLES = {
    success: {
      box: 'border-emerald-200 bg-white/95 text-slate-800 dark:border-emerald-400/20 dark:bg-[#111a2c]/95 dark:text-slate-100',
      icon: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-400/15 dark:text-emerald-300',
    },
    error: {
      box: 'border-rose-200 bg-white/95 text-slate-800 dark:border-rose-400/20 dark:bg-[#1a1018]/95 dark:text-slate-100',
      icon: 'bg-rose-100 text-rose-600 dark:bg-rose-400/15 dark:text-rose-300',
    },
    info: {
      box: 'border-slate-200 bg-white/95 text-slate-800 dark:border-white/10 dark:bg-[#111a2c]/95 dark:text-slate-100',
      icon: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-400/15 dark:text-indigo-300',
    },
  };

  function toast(message, type = 'info', duration = 4200) {
    if (!els.toasts) return;
    const style = TOAST_STYLES[type] || TOAST_STYLES.info;
    const item = document.createElement('div');
    item.className = `pointer-events-auto toast-in flex items-start gap-3 rounded-xl border px-3.5 py-3 shadow-lg shadow-slate-900/5 backdrop-blur-xl ${style.box}`;
    item.setAttribute('role', type === 'error' ? 'alert' : 'status');

    const icon = document.createElement('span');
    icon.className = `mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full ${style.icon}`;
    icon.innerHTML = TOAST_ICONS[type] || TOAST_ICONS.info;

    const text = document.createElement('p');
    text.className = 'min-w-0 flex-1 text-[12.5px] font-medium leading-5';
    text.textContent = message;

    const close = document.createElement('button');
    close.type = 'button';
    close.className = 'ml-1 mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-slate-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 dark:hover:bg-white/10 dark:hover:text-white';
    close.setAttribute('aria-label', 'Dismiss notification');
    close.innerHTML = '<svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>';

    item.append(icon, text, close);
    els.toasts.appendChild(item);
    while (els.toasts.children.length > 4) els.toasts.firstElementChild.remove();

    let timer = setTimeout(dismiss, duration);
    function dismiss() {
      clearTimeout(timer);
      item.classList.add('opacity-0');
      item.style.transition = 'opacity 150ms ease, transform 150ms ease';
      item.style.transform = 'translateY(-4px)';
      setTimeout(() => item.remove(), 160);
    }
    close.addEventListener('click', dismiss);
    item.addEventListener('mouseenter', () => clearTimeout(timer));
    item.addEventListener('mouseleave', () => {
      timer = setTimeout(dismiss, 1400);
    });
  }

  // ---------------------------------------------------------------------
  // Theme
  // ---------------------------------------------------------------------
  function applyTheme(theme, persist = true) {
    const dark = theme === 'dark';
    document.documentElement.classList.toggle('dark', dark);
    const label = dark ? 'Switch to light theme' : 'Switch to dark theme';
    els.themeToggle.setAttribute('aria-label', label);
    els.themeToggle.setAttribute('title', label);
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', dark ? '#080b14' : '#f8fafc');
    if (persist) {
      try {
        localStorage.setItem('fetchly-theme', dark ? 'dark' : 'light');
      } catch (_) {}
    }
  }

  function setupTheme() {
    applyTheme(document.documentElement.classList.contains('dark') ? 'dark' : 'light', false);
    els.themeToggle.addEventListener('click', () => {
      const next = document.documentElement.classList.contains('dark') ? 'light' : 'dark';
      applyTheme(next);
    });
  }

  // ---------------------------------------------------------------------
  // URL fields
  // ---------------------------------------------------------------------
  function refreshClearButton(input, button) {
    const hasValue = Boolean(input.value.trim());
    button.classList.toggle('hidden', !hasValue);
    button.classList.toggle('flex', hasValue);
  }

  function setFieldError(input, errorEl, message) {
    input.classList.add('url-invalid');
    input.setAttribute('aria-invalid', 'true');
    errorEl.textContent = message;
    errorEl.classList.remove('hidden');
  }

  function clearFieldError(input, errorEl) {
    input.classList.remove('url-invalid');
    input.removeAttribute('aria-invalid');
    errorEl.textContent = '';
    errorEl.classList.add('hidden');
  }

  // There is no "Fetch" button: a pasted or typed link is fetched by itself after a short pause.
  const FETCH_DELAY_MS = 600;

  function requestFetch(force = false) {
    clearTimeout(state.video.fetchTimer);
    state.video.fetchTimer = null;
    const value = normalizeUrlInput(els.videoUrl.value);
    if (!value || validateUrl(value)) return;   // not a link yet: wait, the blur check explains if needed
    if (!force && value === state.video.lastFetched && state.video.phase !== 'idle') return;
    handleVideoSubmit({ preventDefault() {} });
  }

  function scheduleFetch() {
    clearTimeout(state.video.fetchTimer);
    state.video.fetchTimer = setTimeout(() => requestFetch(), FETCH_DELAY_MS);
  }

  // The link was cleared: stop a fetch in progress and go back to the start. A running download is left alone.
  function resetVideo() {
    clearTimeout(state.video.fetchTimer);
    state.video.fetchTimer = null;
    state.video.lastFetched = null;
    if (['fetching', 'ready', 'error', 'playlist'].includes(state.video.phase)) {
      if (state.video.controller) state.video.controller.abort();
      state.video.requestSeq += 1;
      state.video.jobId = null;
      state.video.data = null;
      setVideoPhase('idle');
    }
  }

  function setupUrlField(input, pasteButton, clearButton, errorEl) {
    input.addEventListener('input', () => {
      clearFieldError(input, errorEl);
      refreshClearButton(input, clearButton);
      if (input.value.trim()) scheduleFetch();
      else resetVideo();
    });
    input.addEventListener('blur', () => {
      if (!input.value.trim()) {
        clearFieldError(input, errorEl);
        return;
      }
      const value = normalizeUrlInput(input.value);
      const message = validateUrl(value);
      if (message) setFieldError(input, errorEl, message);
      else clearFieldError(input, errorEl);
    });
    clearButton.addEventListener('click', () => {
      input.value = '';
      clearFieldError(input, errorEl);
      refreshClearButton(input, clearButton);
      resetVideo();
      input.focus();
    });
    pasteButton.addEventListener('click', async () => {
      try {
        if (!navigator.clipboard || !navigator.clipboard.readText) {
          throw new Error('Clipboard API unavailable');
        }
        const text = (await navigator.clipboard.readText()).trim();
        if (!text) {
          toast('Your clipboard is empty.', 'info');
          return;
        }
        input.value = text;
        clearFieldError(input, errorEl);
        refreshClearButton(input, clearButton);
        input.focus();
        requestFetch();
      } catch (_) {
        toast('Clipboard access was blocked. Use Ctrl/Cmd+V instead.', 'info');
        input.focus();
      }
    });
    refreshClearButton(input, clearButton);
  }

  // ---------------------------------------------------------------------
  // Buttons / phases
  // ---------------------------------------------------------------------
  function setButtonLoading(button, loading, loadingLabel) {
    if (!button) return;
    button.disabled = Boolean(loading);
    button.setAttribute('aria-busy', loading ? 'true' : 'false');
    const label = button.querySelector('[data-btn-label]');
    if (label) {
      if (loading) {
        if (!button.dataset.idleLabel) button.dataset.idleLabel = label.textContent;
        label.textContent = loadingLabel || 'Working…';
      } else {
        label.textContent = button.dataset.idleLabel || INITIAL_BUTTON_LABELS[button.id] || 'Continue';
      }
    }
    const spinner = button.querySelector('[data-spinner]');
    if (spinner) spinner.classList.toggle('hidden', !loading);
    button.querySelectorAll('[data-arrow], [data-download-icon]').forEach((icon) => {
      icon.classList.toggle('hidden', Boolean(loading));
    });
  }

  function setVideoPhase(phase) {
    state.video.phase = phase;
    const showResult = phase === 'ready';
    els.videoEmpty.hidden = phase !== 'idle';
    els.videoSkeleton.hidden = phase !== 'fetching';
    els.videoErrorCard.hidden = phase !== 'error';
    els.videoResult.hidden = !showResult;
    const playlistPanel = $('playlistPanel');
    if (playlistPanel) playlistPanel.hidden = phase !== 'playlist';
  }

  // ---------------------------------------------------------------------
  // Video flow
  // ---------------------------------------------------------------------
  function renderVideo(video) {
    els.videoTitle.textContent = video.title || 'Untitled video';
    const uploader = video.uploader || '';
    const source = shortenHost(video.webpage_url);
    els.videoUploader.textContent = uploader;
    els.videoSource.textContent = source;
    els.videoMetaDot.hidden = !uploader || !source;

    const duration = formatDuration(video.duration);
    els.videoDuration.textContent = duration;
    els.videoDuration.hidden = !duration;

    els.videoThumbFallback.hidden = false;
    els.videoThumb.hidden = false;
    els.videoThumb.onload = () => {
      els.videoThumbFallback.hidden = true;
    };
    els.videoThumb.onerror = () => {
      els.videoThumb.hidden = true;
      els.videoThumbFallback.hidden = false;
    };
    if (video.has_thumbnail && state.video.jobId) {
      els.videoThumb.src = `/api/video/${encodeURIComponent(state.video.jobId)}/thumbnail`;
      els.videoThumb.alt = '';
    } else {
      els.videoThumb.removeAttribute('src');
      els.videoThumb.hidden = true;
      els.videoThumbFallback.hidden = false;
    }

    els.videoQuality.replaceChildren();
    (video.formats || []).forEach((format) => {
      const option = document.createElement('option');
      option.value = format.key;
      const size = format.size_bytes ? ` · ${formatBytes(format.size_bytes)}` : '';
      const detail = format.detail ? ` — ${format.detail}` : '';
      option.textContent = `${format.label}${detail}${size}`;
      els.videoQuality.appendChild(option);
    });
    const recommended = (video.formats || []).find((format) => format.recommended) || (video.formats || [])[0];
    if (recommended) els.videoQuality.value = recommended.key;
    // Start from the quick choice the user picked last time (when this video offers it).
    const wanted = presetKey(pref.get('preset', 'best'));
    if (wanted) els.videoQuality.value = wanted;
    renderPresets();
    renderSubtitles(video.subtitles || []);
    updateFormatHint();
    els.videoQualityBlock.hidden = false;
    els.videoDownload.hidden = false;
    els.videoLive.textContent = `Video ready: ${video.title}. Choose a quality and download.`;
  }

  function currentFormat() {
    const key = els.videoQuality.value;
    return (state.video.data?.formats || []).find((format) => format.key === key) || null;
  }

  // ---------------------------------------------------------------------
  // Quick quality choices, subtitles, notifications
  // ---------------------------------------------------------------------
  const PRESETS = [['best', 'Best'], ['1080', '1080p'], ['720', '720p'], ['480', '480p'], ['audio', 'Audio (MP3)']];

  // The quality option this video has for a quick choice: the best one that is not above it.
  function presetKey(preset) {
    const formats = state.video.data?.formats || [];
    if (preset === 'audio') return formats.some((f) => f.key === 'audio') ? 'audio' : null;
    if (preset === 'best') return formats.some((f) => f.key === 'best') ? 'best' : null;
    const limit = Number(preset);
    const heights = formats.filter((f) => f.kind === 'video' && /^\d+$/.test(f.key)).map((f) => Number(f.key));
    const fit = Math.max(0, ...heights.filter((h) => h <= limit));
    return fit ? String(fit) : presetKey('best');
  }

  function renderPresets() {
    const formats = state.video.data?.formats || [];
    const active = els.videoQuality.value;
    els.presetRow.replaceChildren();
    if (formats.length < 2) return;
    const label = document.createElement('span');
    label.className = 'mr-1 text-[12px] font-semibold text-slate-700 dark:text-slate-300';
    label.textContent = 'Quick choice';
    els.presetRow.appendChild(label);
    PRESETS.forEach(([preset, text]) => {
      // A height chip only appears when this video really has that height.
      const key = /^\d+$/.test(preset) ? (formats.some((f) => f.key === preset) ? preset : null) : presetKey(preset);
      if (!key) return;
      const on = key === active;
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.textContent = text;
      chip.setAttribute('aria-pressed', on ? 'true' : 'false');
      chip.className = 'rounded-full border px-3 py-1 text-[12px] font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 ' + (on
        ? 'border-indigo-500 bg-indigo-50 text-indigo-700 dark:border-indigo-400 dark:bg-indigo-400/10 dark:text-indigo-200'
        : 'border-slate-200 text-slate-600 hover:bg-slate-50 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/5');
      chip.addEventListener('click', () => {
        pref.set('preset', preset);
        els.videoQuality.value = key;
        renderPresets();
        updateFormatHint();
      });
      els.presetRow.appendChild(chip);
    });
  }

  function renderSubtitles(subs) {
    els.subsLang.replaceChildren();
    subs.forEach((s) => {
      const option = document.createElement('option');
      option.value = s.code;
      option.textContent = s.name === s.code ? s.code : `${s.name} (${s.code})`;
      els.subsLang.appendChild(option);
    });
    const saved = pref.get('sublang', '');
    const device = (navigator.language || 'en').toLowerCase();
    const pick = subs.find((s) => s.code === saved)
      || subs.find((s) => s.code.toLowerCase() === device)
      || subs.find((s) => s.code.toLowerCase().split('-')[0] === device.split('-')[0])
      || subs.find((s) => s.code.toLowerCase().startsWith('en'))
      || subs[0];
    if (pick) els.subsLang.value = pick.code;
    els.subsCheck.checked = pref.get('subs', '0') === '1';
    els.subsRow.dataset.has = subs.length ? '1' : '';
  }

  // Which subtitle language to embed, or null (no choice made, audio-only, or none offered).
  function chosenSubtitles() {
    const format = currentFormat();
    if (!format || format.kind === 'audio' || els.subsRow.dataset.has !== '1' || !els.subsCheck.checked) return null;
    return els.subsLang.value || null;
  }

  function notifyDone(title, body) {
    if (pref.get('notify', '1') !== '1' || !('Notification' in window) || Notification.permission !== 'granted') return;
    if (!document.hidden && document.hasFocus()) return;   // the user is looking at the app already
    try {
      const n = new Notification(title, { body });
      n.onclick = () => { window.focus(); n.close(); };
    } catch (_) {}
  }

  function askNotifyPermission() {
    if (pref.get('notify', '1') === '1' && 'Notification' in window && Notification.permission === 'default') {
      try { Notification.requestPermission(); } catch (_) {}
    }
  }

  // ---------------------------------------------------------------------
  // Download history (kept on this device only)
  // ---------------------------------------------------------------------
  const HISTORY_MAX = 50;

  function readHistory() {
    try {
      const list = JSON.parse(pref.get('history', '[]'));
      return Array.isArray(list) ? list : [];
    } catch (_) { return []; }
  }

  function addHistory(entry) {
    const list = readHistory().filter((item) => item.id !== entry.id);
    list.unshift({ ...entry, ts: Date.now() });
    pref.set('history', JSON.stringify(list.slice(0, HISTORY_MAX)));
    renderHistory();
  }

  function timeAgo(ts) {
    const s = Math.max(0, (Date.now() - ts) / 1000);
    if (s < 60) return 'just now';
    if (s < 3600) return `${Math.floor(s / 60)} min ago`;
    if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
    return `${Math.floor(s / 86400)} d ago`;
  }

  function renderHistory() {
    const list = readHistory();
    els.historySection.hidden = list.length === 0;
    els.historyCount.textContent = `History (${list.length})`;
    els.historyList.replaceChildren();
    list.forEach((item) => {
      const row = document.createElement('li');
      row.className = 'flex items-center gap-2 px-2 py-2.5';
      const text = document.createElement('div');
      text.className = 'min-w-0 flex-1';
      const title = document.createElement('p');
      title.className = 'truncate text-[13px] font-medium text-slate-800 dark:text-slate-100';
      title.textContent = item.title || 'Untitled video';
      title.title = item.title || '';
      const meta = document.createElement('p');
      meta.className = 'truncate text-[11.5px] text-slate-400 dark:text-slate-500';
      meta.textContent = [item.label, shortenHost(item.url), timeAgo(item.ts)].filter(Boolean).join(' · ');
      text.append(title, meta);

      const again = document.createElement('button');
      again.type = 'button';
      again.textContent = 'Fetch again';
      again.className = 'shrink-0 rounded-lg border border-slate-200 px-2.5 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50 dark:border-white/10 dark:text-slate-300 dark:hover:bg-white/5';
      again.addEventListener('click', () => {
        els.videoUrl.value = item.url;
        els.videoUrl.dispatchEvent(new Event('input', { bubbles: true }));
        window.scrollTo({ top: 0, behavior: 'smooth' });
        els.videoForm.requestSubmit();
      });
      const remove = document.createElement('button');
      remove.type = 'button';
      remove.setAttribute('aria-label', 'Remove from history');
      remove.className = 'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-slate-400 transition hover:bg-slate-100 hover:text-slate-900 dark:hover:bg-white/10 dark:hover:text-white';
      remove.innerHTML = '<svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg>';
      remove.addEventListener('click', () => {
        pref.set('history', JSON.stringify(readHistory().filter((h) => h.id !== item.id)));
        renderHistory();
      });
      row.append(text, again, remove);
      els.historyList.appendChild(row);
    });
  }

  function updateFormatHint() {
    const format = currentFormat();
    if (!format) return;
    renderPresets();
    els.subsRow.hidden = format.kind === 'audio' || els.subsRow.dataset.has !== '1';
    if (format.size_bytes) {
      els.formatSize.textContent = `≈ ${formatBytes(format.size_bytes)}`;
    } else {
      els.formatSize.textContent = 'Size available on download';
    }
    els.formatDetail.textContent = format.kind === 'audio'
      ? `${format.detail || 'Audio'} · Made with FFmpeg`
      : `${format.detail || 'Video'} · Merged at the best available quality`;
    const label = els.videoDownload.querySelector('[data-btn-label]');
    if (label && state.video.phase === 'ready') {
      label.textContent = format.kind === 'audio' ? 'Download audio' : 'Download video';
    }
  }

  function updateRowProgress(row, progress = {}) {
    const stage = progress.stage || 'downloading';
    const percent = Math.max(0, Math.min(100, Number(progress.percent) || 0));
    row.q('bar').style.width = `${percent}%`;
    row.q('percent').textContent = `${percent.toFixed(percent > 0 && percent < 10 ? 1 : 0)}%`;
    row.q('track').setAttribute('aria-valuenow', String(Math.round(percent)));

    let text = 'Downloading…';
    if (stage === 'queued') text = 'Waiting for other downloads…';
    else if (stage === 'processing' || stage === 'packaging') text = 'Merging video & audio…';
    else if (stage === 'starting') text = 'Preparing download…';
    else if (stage === 'complete') text = 'Finishing up…';
    row.q('stage').textContent = text;
    if (stage === 'processing' || stage === 'packaging') row.q('percent').textContent = '100%';

    row.q('speed').textContent = progress.speed ? `${formatBytes(progress.speed)}/s` : '—';
    row.q('eta').textContent = stage === 'downloading' && progress.eta != null
      ? `${formatEta(progress.eta)} left`
      : '—';
    row.q('bar').classList.toggle('soft-pulse', stage === 'processing');
    row.stage = stage;
  }

  async function handleVideoSubmit(event) {
    event.preventDefault();
    // YouTube link without a login: ask first (static/help.js, once per link). The fetch goes on after the answer.
    const link = normalizeUrlInput(els.videoUrl.value);
    if (!event.loginAsked && !validateUrl(link) &&
        window.fetchlyHelp?.askLogin(link, () => handleVideoSubmit({ preventDefault() {}, loginAsked: true }))) return;
    clearTimeout(state.video.fetchTimer);
    state.video.fetchTimer = null;
    // A new link while one is still being fetched: the older request is dropped below (requestSeq).
    if (state.video.phase === 'fetching' && state.video.controller) state.video.controller.abort();

    const value = normalizeUrlInput(els.videoUrl.value);
    const validationMessage = validateUrl(value);
    if (validationMessage) {
      setFieldError(els.videoUrl, els.videoUrlError, validationMessage);
      els.videoUrl.focus();
      return;
    }
    if (value !== els.videoUrl.value.trim()) els.videoUrl.value = value;
    clearFieldError(els.videoUrl, els.videoUrlError);
    state.video.lastFetched = value;

    const notSupported = unsupportedLink(value);
    if (notSupported) {
      state.video.requestSeq += 1;   // an older fetch still running must not replace this message
      state.video.jobId = null;
      state.video.data = null;
      showVideoError(notSupported, false, true);
      return;
    }

    // Desktop app: playlist links open the playlist list instead (static/playlist.js).
    const playlists = state.health?.playlists && window.FetchyPlaylist;
    if (playlists && playlists.busy()) {
      toast('A playlist is still downloading. Wait for it, or cancel it first.', 'info');
      return;
    }
    if (playlists && !event.singleVideo && playlists.open(value, () => handleVideoSubmit({ preventDefault() {}, singleVideo: true }))) return;

    state.video.jobId = null;
    state.video.data = null;
    const seq = ++state.video.requestSeq;
    const controller = new AbortController();
    state.video.controller = controller;

    setVideoPhase('fetching');
    setButtonLoading(els.videoDownload, false);
    setButtonLoading(els.videoErrorDownload, false);
    els.videoLive.textContent = 'Fetching video details.';

    try {
      const result = await api('/api/video/info', {
        method: 'POST',
        body: { url: value, playlist: !!els.videoPlaylist.checked },
        signal: controller.signal,
        timeout: els.videoPlaylist.checked ? 180000 : 95000,
      });
      if (seq !== state.video.requestSeq) return;
      state.video.jobId = result.job_id;
      state.video.data = result.video;
      renderVideo(result.video);
      setVideoPhase('ready');
    } catch (error) {
      if (seq !== state.video.requestSeq) return;
      if (error.name === 'AbortError') {
        showVideoError('The request was canceled.');
      } else {
        // The details could not be read, but the engine kept a job: offer a best-quality download anyway.
        const jobId = error.data?.job_id || null;
        if (jobId) {
          state.video.jobId = jobId;
          state.video.data = { title: '', webpage_url: value, formats: [{ key: 'best', label: 'Best quality', kind: 'video' }], no_info: true };
        }
        showVideoError(error.message || 'We could not fetch that video.', Boolean(jobId));
      }
    }
  }

  // canDownload: show the "Download best quality" button (the details failed, but a download may still work).
  // notSupported: a site Fetchy cannot download from: no "Try again".
  function showVideoError(message, canDownload = false, notSupported = false) {
    setVideoPhase('error');
    $('videoErrorTitle').textContent = notSupported ? 'Not supported' : "Couldn't fetch that video";
    els.videoRetry.hidden = notSupported;
    els.videoErrorText.textContent = message;
    els.videoErrorDownload.hidden = !canDownload;
    els.videoLive.textContent = `Video error: ${message}`;
    toast(message, 'error', 5200);
  }

  async function startVideoDownload() {
    const noInfo = Boolean(state.video.data?.no_info);
    const phaseOk = state.video.phase === 'ready' || (noInfo && state.video.phase === 'error');
    if (!phaseOk || !state.video.jobId || state.video.starting) return;
    const jobId = state.video.jobId;
    const video = state.video.data || {};
    const quality = noInfo ? 'best' : els.videoQuality.value;
    const subs = noInfo ? null : chosenSubtitles();
    const label = noInfo ? 'Best quality' : (currentFormat()?.label || '');
    const url = video.webpage_url || normalizeUrlInput(els.videoUrl.value);
    askNotifyPermission();
    state.video.starting = true;
    setButtonLoading(els.videoDownload, true, 'Starting…');
    setButtonLoading(els.videoErrorDownload, true, 'Starting…');

    try {
      await api('/api/video/download', {
        method: 'POST',
        body: { job_id: jobId, quality, subs },
        timeout: 60000,
      });
      addDownloadRow({
        id: jobId,
        title: video.title || '',
        url,
        label: [label, subs ? `subtitles: ${subs}` : ''].filter(Boolean).join(' + '),
      });
      if (state.video.jobId === jobId) {   // still showing this video: clear it for the next link
        els.videoUrl.value = '';
        refreshClearButton(els.videoUrl, els.videoClear);
        resetVideo();
      }
      toast('Download started. You can paste another link now.', 'success');
      els.videoLive.textContent = 'Download started. You can paste another link now.';
    } catch (error) {
      if (state.video.jobId === jobId) toast(error.message || 'The download could not start.', 'error', 5200);
    } finally {
      state.video.starting = false;
      setButtonLoading(els.videoDownload, false);
      setButtonLoading(els.videoErrorDownload, false);
      if (state.video.phase === 'ready') updateFormatHint();
    }
  }

  // ---------------------------------------------------------------------
  // Downloads list: one row per started download, each with its own progress.
  // The engine runs a few at a time; the others wait in its queue ("queued").
  // ---------------------------------------------------------------------
  const downloads = new Map();   // job id -> row
  let downloadsTimer = null;
  const ROW_STYLE = {
    active: 'border-slate-200 bg-slate-50/80 dark:border-white/10 dark:bg-white/[0.035]',
    done: 'border-emerald-200 bg-emerald-50/80 dark:border-emerald-400/20 dark:bg-emerald-400/10',
    error: 'border-rose-200 bg-rose-50/80 dark:border-rose-400/20 dark:bg-rose-400/10',
  };

  function addDownloadRow(info) {
    const node = $('dlRowTemplate').content.firstElementChild.cloneNode(true);
    const row = { ...info, node, finished: false, fails: 0, q: (name) => node.querySelector(`[data-${name}]`) };
    row.q('title').textContent = info.title || 'Best quality download';
    row.q('sub').textContent = [info.label, shortenHost(info.url)].filter(Boolean).join(' · ');
    row.q('cancel').addEventListener('click', () => cancelDownload(row));
    row.q('close').addEventListener('click', () => {
      downloads.delete(row.id);
      node.remove();
      refreshDownloads();
    });
    row.q('retry').addEventListener('click', () => {
      els.videoUrl.value = row.url;
      refreshClearButton(els.videoUrl, els.videoClear);
      els.videoUrl.scrollIntoView({ behavior: 'smooth', block: 'center' });
      requestFetch(true);
    });
    node.className = `rounded-2xl border p-4 ${ROW_STYLE.active}`;
    updateRowProgress(row, { stage: 'queued', percent: 0 });
    downloads.set(row.id, row);
    $('downloadsList').prepend(node);
    window.fetchlyHelp?.watch?.(row.q('error-text'));   // adds "How to fix" and "Copy error details"
    refreshDownloads();
    pollDownloads();
  }

  function refreshDownloads() {
    const rows = [...downloads.values()];
    $('downloadsSection').hidden = rows.length === 0;
    const active = rows.filter((row) => !row.finished);
    const waiting = active.filter((row) => row.stage === 'queued').length;
    const running = active.length - waiting;
    const bits = [running ? `${running} running` : '', waiting ? `${waiting} waiting` : ''].filter(Boolean);
    $('downloadsCount').textContent = bits.length ? `Downloads · ${bits.join(', ')}` : 'Downloads';
  }

  // One timer asks about all running rows (not one open stream each: browsers allow only a few per site).
  async function pollDownloads() {
    clearTimeout(downloadsTimer);
    downloadsTimer = null;
    const active = [...downloads.values()].filter((row) => !row.finished);
    if (!active.length) return;
    await Promise.all(active.map(async (row) => {
      try {
        const payload = await api(`/api/jobs/${encodeURIComponent(row.id)}`, { timeout: 20000 });
        row.fails = 0;
        handleRowJob(row, payload);
      } catch (error) {
        row.fails += 1;
        if (error.status === 404 || row.fails > 40) endRow(row, 'error', error.message || 'Lost connection to the download.');
      }
    }));
    refreshDownloads();
    if (!downloadsTimer && [...downloads.values()].some((row) => !row.finished)) {
      downloadsTimer = setTimeout(pollDownloads, 1000);
    }
  }

  function handleRowJob(row, payload) {
    if (!payload || row.finished) return;
    if (payload.state === 'downloading') updateRowProgress(row, payload.progress || {});
    else if (payload.state === 'complete') finishRow(row, payload);
    else if (payload.state === 'error') endRow(row, 'error', payload.error || 'The download failed. Please try again.');
    else if (payload.state === 'cancelled') endRow(row, 'cancelled');
  }

  async function cancelDownload(row) {
    const button = row.q('cancel');
    button.disabled = true;
    button.textContent = 'Cancelling…';
    try {
      handleRowJob(row, await api(`/api/video/${encodeURIComponent(row.id)}/cancel`, { method: 'POST', timeout: 20000 }));
      refreshDownloads();
    } catch (error) {
      button.disabled = false;
      button.textContent = 'Cancel';
      toast(error.message || 'Could not cancel. Try again.', 'error');
    }
  }

  // Stop showing progress: 'error' (red, with Try again) or 'cancelled'.
  function endRow(row, kind, message = '') {
    row.finished = true;
    row.q('progress').hidden = true;
    row.q('cancel').hidden = true;
    row.q('close').hidden = false;
    if (kind === 'error') {
      row.node.className = `rounded-2xl border p-4 ${ROW_STYLE.error}`;
      row.q('error').hidden = false;
      row.q('error-text').textContent = message;
      els.videoLive.textContent = `Download failed: ${message}`;
    } else {
      row.q('stopped').hidden = false;
      els.videoLive.textContent = 'Download cancelled.';
      closeRowLater(row);
    }
    refreshDownloads();
  }

  // Finished and cancelled rows close by themselves after a while, to make room (errors stay: they need a look).
  function closeRowLater(row) {
    setTimeout(() => {
      if (downloads.get(row.id) !== row) return;
      downloads.delete(row.id);
      row.node.remove();
      refreshDownloads();
    }, 10000);
  }

  function finishRow(row, payload) {
    row.finished = true;
    row.node.className = `rounded-2xl border p-4 ${ROW_STYLE.done}`;
    row.q('progress').hidden = true;
    row.q('cancel').hidden = true;
    row.q('close').hidden = false;
    row.q('done').hidden = false;
    const url = payload.download_url || `/api/download/${encodeURIComponent(row.id)}`;
    row.q('link').href = url;
    const name = payload.file_name || 'your file';
    row.q('meta').textContent = `${name} · ${state.health?.playlists ? 'saved to your Downloads folder' : 'available for 1 hour'}`;
    els.videoLive.textContent = 'Download complete. Your file is ready.';
    triggerDownload(url);
    toast('Download complete — your file is ready.', 'success');
    addHistory({
      id: row.id,
      title: row.title || name.replace(/\.[a-z0-9]{2,5}$/i, ''),   // no details were read: use the file name
      url: row.url,
      label: row.label,
    });
    notifyDone('Download finished', name);
    refreshDownloads();
    closeRowLater(row);
  }

  function triggerDownload(url) {
    const link = document.createElement('a');
    link.href = url;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  // ---------------------------------------------------------------------
  // Health + init
  // ---------------------------------------------------------------------
  function showToolVersions(ytdlp) {
    els.toolVersions.textContent = `yt-dlp ${ytdlp || '?'}`;
  }

  async function updateTools() {
    els.updateTools.disabled = true;
    els.updateTools.textContent = 'Updating…';
    els.updateStatus.textContent = '';
    try {
      const result = await api('/api/tools/update', { method: 'POST', timeout: 330000 });
      els.updateStatus.textContent = result.message;
      showToolVersions(result.yt_dlp);
    } catch (error) {
      els.updateStatus.textContent = error.message || 'Could not update. Try again later.';
    } finally {
      els.updateTools.disabled = false;
      els.updateTools.textContent = 'Update tools';
    }
  }

  async function loadHealth() {
    try {
      const health = await api('/api/health', { timeout: 10000 });
      state.health = health;
      showToolVersions(health.yt_dlp?.version);
      els.ffmpegWarning.hidden = Boolean(health.ffmpeg?.available);
      if (health.playlists) {   // desktop app: files are saved on this computer, nothing is deleted after an hour
        $('heroBadgeText').textContent = 'No sign-up · Saved to your Downloads folder';
        $('footKeepText').textContent = 'Saved to Downloads';
      }
    } catch (_) {
      // Health is advisory; don't block the UI if it fails.
    }
  }

  function init() {
    setupTheme();
    setupUrlField(els.videoUrl, els.videoPaste, els.videoClear, els.videoUrlError);
    const syncPlaylist = () => {
      const isList = !state.health?.playlists && (/[?&]list=/.test(els.videoUrl.value) || /\/playlist/.test(els.videoUrl.value));
      els.videoPlaylistRow.classList.toggle('hidden', !isList);
      els.videoPlaylistRow.classList.toggle('flex', isList);
      if (!isList) els.videoPlaylist.checked = false;
    };
    els.videoUrl.addEventListener('input', syncPlaylist);
    els.videoUrl.addEventListener('change', syncPlaylist);
    setInterval(syncPlaylist, 700);

    els.videoForm.addEventListener('submit', handleVideoSubmit);
    els.videoRetry.addEventListener('click', () => requestFetch(true));
    els.videoPlaylist.addEventListener('change', () => requestFetch(true));
    els.videoDownload.addEventListener('click', startVideoDownload);
    els.videoErrorDownload.addEventListener('click', startVideoDownload);
    els.videoQuality.addEventListener('change', updateFormatHint);
    els.subsCheck.addEventListener('change', () => pref.set('subs', els.subsCheck.checked ? '1' : '0'));
    els.subsLang.addEventListener('change', () => pref.set('sublang', els.subsLang.value));
    els.notifyCheck.checked = pref.get('notify', '1') === '1';
    els.notifyCheck.addEventListener('change', () => {
      pref.set('notify', els.notifyCheck.checked ? '1' : '0');
      askNotifyPermission();
    });
    els.historyClear.addEventListener('click', () => { pref.set('history', '[]'); renderHistory(); });
    renderHistory();
    els.updateTools.addEventListener('click', updateTools);

    setVideoPhase('idle');
    loadHealth();
  }

  // Shared with static/playlist.js.
  // A finished playlist is recorded and announced like a single video (see playlist.js).
  function playlistFinished(title, url, summary) {
    addHistory({ id: `pl-${Date.now()}`, title, url, label: `Playlist · ${summary}` });
    notifyDone('Playlist finished', `${title} — ${summary}`);
  }

  window.Fetchy = { api, toast, formatBytes, formatDuration, setVideoPhase, setButtonLoading, playlistFinished };

  init();
})();
