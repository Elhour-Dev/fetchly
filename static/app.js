/* Fetchly frontend - vanilla JS, no build step. */
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
    videoPaste: $('videoPaste'),
    videoClear: $('videoClear'),
    videoHint: $('videoHint'),
    videoUrlError: $('videoUrlError'),
    videoFetch: $('videoFetch'),
    videoEmpty: $('videoEmpty'),
    videoSkeleton: $('videoSkeleton'),
    videoErrorCard: $('videoErrorCard'),
    videoErrorText: $('videoErrorText'),
    videoRetry: $('videoRetry'),
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
    videoProgress: $('videoProgress'),
    progressStage: $('progressStage'),
    progressPercent: $('progressPercent'),
    progressTrack: $('progressTrack'),
    progressBar: $('progressBar'),
    progressSpeed: $('progressSpeed'),
    progressEta: $('progressEta'),
    videoComplete: $('videoComplete'),
    completeMeta: $('completeMeta'),
    downloadLink: $('downloadLink'),
    ffmpegWarning: $('ffmpegWarning'),
    updateTools: $('updateTools'),
    updateStatus: $('updateStatus'),
    toolVersions: $('toolVersions'),
    videoLive: $('videoLive'),

    toasts: $('toasts'),
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
      stream: null,
      pollTimer: null,
      pollToken: 0,
      terminal: false,
      autoDownloadedFor: null,
    },
  };

  const INITIAL_BUTTON_LABELS = {
    videoFetch: 'Fetch video',
    videoDownload: 'Download video',
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

  function updateFetchButtons() {
    els.videoFetch.disabled = state.video.phase === 'fetching' || !els.videoUrl.value.trim();
  }

  function setupUrlField(input, pasteButton, clearButton, errorEl) {
    input.addEventListener('input', () => {
      clearFieldError(input, errorEl);
      refreshClearButton(input, clearButton);
      updateFetchButtons();
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
      updateFetchButtons();
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
        updateFetchButtons();
        input.focus();
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
    const showResult = phase === 'ready' || phase === 'downloading' || phase === 'complete';
    els.videoEmpty.hidden = phase !== 'idle';
    els.videoSkeleton.hidden = phase !== 'fetching';
    els.videoErrorCard.hidden = phase !== 'error';
    els.videoResult.hidden = !showResult;

    if (showResult) {
      els.videoQualityBlock.hidden = phase !== 'ready';
      els.videoDownload.hidden = phase !== 'ready';
      els.videoProgress.hidden = phase !== 'downloading';
      els.videoComplete.hidden = phase !== 'complete';
    }
    updateFetchButtons();
  }

  // ---------------------------------------------------------------------
  // Video flow
  // ---------------------------------------------------------------------
  function closeVideoStream() {
    if (state.video.stream) {
      state.video.stream.close();
      state.video.stream = null;
    }
    if (state.video.pollTimer) {
      clearTimeout(state.video.pollTimer);
      state.video.pollTimer = null;
    }
    state.video.pollToken += 1;
  }

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
    updateFormatHint();
    els.videoComplete.hidden = true;
    els.videoProgress.hidden = true;
    els.videoQualityBlock.hidden = false;
    els.videoDownload.hidden = false;
    els.videoLive.textContent = `Video ready: ${video.title}. Choose a quality and download.`;
  }

  function currentFormat() {
    const key = els.videoQuality.value;
    return (state.video.data?.formats || []).find((format) => format.key === key) || null;
  }

  function updateFormatHint() {
    const format = currentFormat();
    if (!format) return;
    if (format.size_bytes) {
      els.formatSize.textContent = `≈ ${formatBytes(format.size_bytes)}`;
    } else {
      els.formatSize.textContent = 'Size available on download';
    }
    els.formatDetail.textContent = format.kind === 'audio'
      ? 'MP3 audio · 192 kbps · Converted with FFmpeg'
      : `${format.detail || 'Video'} · Merged at the best available quality`;
    const label = els.videoDownload.querySelector('[data-btn-label]');
    if (label && state.video.phase === 'ready') {
      label.textContent = format.kind === 'audio' ? 'Download audio' : 'Download video';
    }
  }

  function updateVideoProgress(progress = {}) {
    const stage = progress.stage || 'downloading';
    const percent = Math.max(0, Math.min(100, Number(progress.percent) || 0));
    els.progressBar.style.width = `${percent}%`;
    els.progressPercent.textContent = `${percent.toFixed(percent > 0 && percent < 10 ? 1 : 0)}%`;
    els.progressTrack.setAttribute('aria-valuenow', String(Math.round(percent)));

    if (stage === 'processing' || stage === 'packaging') {
      els.progressStage.textContent = 'Merging video & audio…';
      els.progressPercent.textContent = '100%';
    } else if (stage === 'starting') {
      els.progressStage.textContent = 'Preparing download…';
    } else if (stage === 'complete') {
      els.progressStage.textContent = 'Finishing up…';
    } else {
      els.progressStage.textContent = 'Downloading…';
    }

    els.progressSpeed.textContent = progress.speed ? `${formatBytes(progress.speed)}/s` : '—';
    els.progressEta.textContent = stage === 'downloading' && progress.eta != null
      ? `${formatEta(progress.eta)} left`
      : '—';
    els.progressBar.classList.toggle('soft-pulse', stage === 'processing');
  }

  async function handleVideoSubmit(event) {
    event.preventDefault();
    if (state.video.phase === 'fetching') return;

    const value = normalizeUrlInput(els.videoUrl.value);
    const validationMessage = validateUrl(value);
    if (validationMessage) {
      setFieldError(els.videoUrl, els.videoUrlError, validationMessage);
      els.videoUrl.focus();
      return;
    }
    if (value !== els.videoUrl.value.trim()) els.videoUrl.value = value;
    clearFieldError(els.videoUrl, els.videoUrlError);

    closeVideoStream();
    state.video.terminal = false;
    state.video.autoDownloadedFor = null;
    state.video.jobId = null;
    state.video.data = null;
    const seq = ++state.video.requestSeq;
    const controller = new AbortController();
    state.video.controller = controller;

    setVideoPhase('fetching');
    setButtonLoading(els.videoFetch, true, 'Fetching…');
    setButtonLoading(els.videoDownload, false);
    els.videoLive.textContent = 'Fetching video details.';

    try {
      const result = await api('/api/video/info', {
        method: 'POST',
        body: { url: value },
        signal: controller.signal,
        timeout: 95000,
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
        const message = error.message || 'We could not fetch that video.';
        showVideoError(message);
      }
    } finally {
      if (seq === state.video.requestSeq) {
        setButtonLoading(els.videoFetch, false);
        updateFetchButtons();
      }
    }
  }

  function showVideoError(message) {
    closeVideoStream();
    setVideoPhase('error');
    els.videoErrorText.textContent = message;
    els.videoLive.textContent = `Video error: ${message}`;
    toast(message, 'error', 5200);
  }

  async function startVideoDownload() {
    if (state.video.phase !== 'ready' || !state.video.jobId) return;
    const jobId = state.video.jobId;
    const quality = els.videoQuality.value;
    state.video.terminal = false;
    setVideoPhase('downloading');
    updateVideoProgress({ stage: 'starting', percent: 0 });
    els.videoLive.textContent = 'Download started.';
    setButtonLoading(els.videoDownload, true, 'Starting…');

    try {
      await api('/api/video/download', {
        method: 'POST',
        body: { job_id: jobId, quality },
        timeout: 60000,
      });
      if (state.video.jobId !== jobId) return;
      openVideoStream(jobId);
    } catch (error) {
      if (state.video.jobId !== jobId) return;
      setVideoPhase('ready');
      setButtonLoading(els.videoDownload, false);
      updateFormatHint();
      toast(error.message || 'The download could not start.', 'error', 5200);
    }
  }

  function openVideoStream(jobId) {
    closeVideoStream();
    if (!('EventSource' in window)) {
      pollVideoJob(jobId);
      return;
    }
    const stream = new EventSource(`/api/jobs/${encodeURIComponent(jobId)}/events`);
    state.video.stream = stream;
    stream.onmessage = (event) => {
      let payload;
      try {
        payload = JSON.parse(event.data);
      } catch (_) {
        return;
      }
      handleVideoJob(payload);
    };
    stream.onerror = () => {
      if (state.video.terminal) return;
      stream.close();
      if (state.video.stream === stream) state.video.stream = null;
      pollVideoJob(jobId);
    };
  }

  function handleVideoJob(payload) {
    if (!payload || !payload.state) return;
    if (payload.state === 'downloading') {
      setVideoPhase('downloading');
      updateVideoProgress(payload.progress || {});
    } else if (payload.state === 'complete') {
      state.video.terminal = true;
      closeVideoStream();
      finishVideo(payload);
    } else if (payload.state === 'error') {
      state.video.terminal = true;
      closeVideoStream();
      showVideoError(payload.error || 'The download failed. Please try again.');
    }
  }

  async function pollVideoJob(jobId) {
    const token = ++state.video.pollToken;
    const started = Date.now();
    const tick = async () => {
      if (token !== state.video.pollToken || state.video.terminal) return;
      try {
        const payload = await api(`/api/jobs/${encodeURIComponent(jobId)}`, { timeout: 20000 });
        if (token !== state.video.pollToken || state.video.terminal) return;
        handleVideoJob(payload);
        if (!state.video.terminal && Date.now() - started < 30 * 60 * 1000) {
          state.video.pollTimer = setTimeout(tick, 1500);
        }
      } catch (error) {
        if (token !== state.video.pollToken || state.video.terminal) return;
        if (Date.now() - started < 30 * 60 * 1000) {
          state.video.pollTimer = setTimeout(tick, 2500);
        } else {
          showVideoError(error.message || 'Lost connection to the download.');
        }
      }
    };
    tick();
  }

  function finishVideo(payload) {
    setVideoPhase('complete');
    els.progressBar.style.width = '100%';
    els.progressPercent.textContent = '100%';
    els.progressTrack.setAttribute('aria-valuenow', '100');
    const url = payload.download_url || `/api/download/${encodeURIComponent(state.video.jobId)}`;
    els.downloadLink.href = url;
    const name = payload.file_name || 'your file';
    els.completeMeta.textContent = `${name} · available for 1 hour`;
    els.videoLive.textContent = 'Download complete. Your file is ready.';

    if (state.video.autoDownloadedFor !== state.video.jobId) {
      state.video.autoDownloadedFor = state.video.jobId;
      triggerDownload(url);
      toast('Download complete — your file is ready.', 'success');
    }
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
      if (!health.ffmpeg?.available) {
        els.ffmpegWarning.hidden = false;
      }
    } catch (_) {
      // Health is advisory; don't block the UI if it fails.
    }
  }

  function init() {
    setupTheme();
    setupUrlField(els.videoUrl, els.videoPaste, els.videoClear, els.videoUrlError);

    els.videoForm.addEventListener('submit', handleVideoSubmit);
    els.videoRetry.addEventListener('click', () => els.videoForm.requestSubmit());
    els.videoDownload.addEventListener('click', startVideoDownload);
    els.videoQuality.addEventListener('change', updateFormatHint);
    els.updateTools.addEventListener('click', updateTools);

    setVideoPhase('idle');
    updateFetchButtons();
    loadHealth();
  }

  init();
})();
