/* Fetchy playlists (desktop app only): video list, selection and download queue. */
(function () {
  'use strict';

  const F = window.Fetchy;
  const panel = document.getElementById('playlistPanel');
  if (!F || !panel) return;

  const PAGE = 100;                      // rows shown at a time ("Load more" adds another page)
  const KBPS = { best: 4500, 1080: 4000, 720: 2200, 480: 1200, audio: 192 };   // rough, for the size estimate
  const QUALITIES = [['best', 'Best quality'], ['1080', '1080p'], ['720', '720p'], ['480', '480p'], ['audio', 'Audio only (MP3)']];
  const OPEN = ['queued', 'starting', 'downloading', 'processing', 'paused'];
  const LABEL = { queued: 'Waiting', starting: 'Starting…', processing: 'Finishing up…', paused: 'Paused', done: 'Done', cancelled: 'Cancelled' };

  const MUTED = 'text-[11.5px] text-slate-400 dark:text-slate-500';
  const BOX = 'rounded-2xl border border-slate-200 bg-slate-50 dark:border-white/10 dark:bg-white/[0.035]';
  const SMALL_BTN = 'rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:border-slate-300 hover:text-slate-900 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 disabled:opacity-50 dark:border-white/10 dark:bg-white/[0.04] dark:text-slate-300 dark:hover:border-white/20 dark:hover:text-white';
  const MAIN_BTN = 'flex h-12 w-full items-center justify-center rounded-xl bg-indigo-600 px-4 text-[14px] font-semibold text-white shadow-sm shadow-indigo-600/20 transition hover:bg-indigo-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60 dark:focus-visible:ring-offset-[#0d1220]';
  const INPUT = 'h-9 rounded-lg border border-slate-200 bg-white px-2.5 text-[12.5px] text-slate-800 outline-none focus:border-indigo-500 dark:border-white/10 dark:bg-[#0b101c] dark:text-slate-100';

  const st = { seq: 0, id: null, title: '', uploader: '', capped: false, cap: 0, entries: [], selected: new Set(), filter: '', shown: PAGE, rows: new Map(), timer: null, status: null, ui: {} };

  function h(tag, cls, text) {
    const el = document.createElement(tag);
    if (cls) el.className = cls;
    if (text != null) el.textContent = text;
    return el;
  }

  function button(label, onClick, cls) {
    const el = h('button', cls || SMALL_BTN, label);
    el.type = 'button';
    el.addEventListener('click', onClick);
    return el;
  }

  function quality() {
    try { return localStorage.getItem('fetchly-playlist-quality') || 'best'; } catch (_) { return 'best'; }
  }

  // ---------------------------------------------------------------------
  // Opening a link
  // ---------------------------------------------------------------------
  function open(url, singleVideo) {
    let u;
    try { u = new URL(url); } catch (_) { return false; }
    if (!u.searchParams.has('list') && !/\/playlist/.test(u.pathname)) return false;
    const hasVideo = u.searchParams.has('v') || (u.hostname === 'youtu.be' && u.pathname.length > 1);
    st.seq += 1;
    stopPolling();
    F.setVideoPhase('playlist');
    if (!hasVideo) { load(url); return true; }

    const box = h('div', BOX + ' p-5 text-center');
    box.append(h('p', 'text-[14px] font-semibold text-slate-800 dark:text-slate-100', 'This link has a video and a playlist.'),
      h('p', 'mt-1 ' + MUTED, 'What do you want to download?'));
    const row = h('div', 'mt-4 flex flex-wrap justify-center gap-2');
    row.append(button('This video only', singleVideo), button('Whole playlist', () => load(url)));
    box.append(row);
    panel.replaceChildren(box);
    return true;
  }

  async function load(url) {
    const seq = ++st.seq;
    const wait = h('div', BOX + ' px-5 py-12 text-center');
    wait.append(h('p', 'soft-pulse text-[14px] font-semibold text-slate-700 dark:text-slate-200', 'Fetching playlist…'),
      h('p', 'mt-1 ' + MUTED, 'Big playlists can take a few seconds.'));
    panel.replaceChildren(wait);
    F.setButtonLoading(F.fetchButton, true, 'Fetching…');
    try {
      const r = await F.api('/api/playlist/info', { method: 'POST', body: { url }, timeout: 250000 });
      if (seq !== st.seq) return;
      Object.assign(st, { id: r.id, title: r.title, uploader: r.uploader, capped: r.capped, cap: r.cap, entries: r.entries, filter: '', shown: PAGE, status: null });
      st.selected = new Set(r.entries.map((e) => e.n));
      renderSelect();
    } catch (error) {
      if (seq !== st.seq) return;
      const message = error.message || 'Could not load this playlist.';
      const box = h('div', 'rounded-2xl border border-rose-200 bg-rose-50/80 p-4 dark:border-rose-400/20 dark:bg-rose-400/10');
      box.append(h('p', 'text-[13.5px] font-semibold text-rose-900 dark:text-rose-100', "Couldn't load that playlist"),
        h('p', 'mt-1 text-[12.5px] leading-5 text-rose-700 dark:text-rose-200/80', message));
      const again = button('Try again', () => load(url));
      again.classList.add('mt-3');
      box.append(again);
      panel.replaceChildren(box);
      F.toast(message, 'error', 5200);
    } finally {
      if (seq === st.seq) F.setButtonLoading(F.fetchButton, false);
    }
  }

  // ---------------------------------------------------------------------
  // Shared row parts
  // ---------------------------------------------------------------------
  function header() {
    const box = h('div', 'mb-3');
    box.append(h('h2', 'line-clamp-2 text-[15px] font-semibold leading-5 text-slate-900 dark:text-white', st.title));
    const cap = st.capped ? ` · Mix playlists never end, so only the first ${st.cap} are listed` : '';
    box.append(h('p', 'mt-1 ' + MUTED, `${st.uploader ? st.uploader + ' · ' : ''}${st.entries.length} videos${cap}`));
    return box;
  }

  function rowParts(e) {
    const num = h('span', 'w-8 shrink-0 text-right text-[11.5px] tabular-nums text-slate-400 dark:text-slate-500', String(e.n));
    const thumb = h('span', 'relative h-9 w-16 shrink-0 overflow-hidden rounded-md bg-slate-200 dark:bg-white/[0.06]');
    if (e.thumb) {
      const img = h('img', 'h-full w-full object-cover');
      img.loading = 'lazy';
      img.referrerPolicy = 'no-referrer';
      img.alt = '';
      img.onerror = () => img.remove();
      img.src = e.thumb;
      thumb.append(img);
    }
    const text = h('span', 'min-w-0 flex-1');
    const title = h('span', 'block truncate text-[13px] font-medium text-slate-800 dark:text-slate-100', e.title);
    title.title = e.title;
    const sub = h('span', 'block truncate ' + MUTED);
    text.append(title, sub);
    const dur = h('span', 'shrink-0 text-[11.5px] tabular-nums text-slate-400 dark:text-slate-500', F.formatDuration(e.duration));
    return { num, thumb, text, sub, dur };
  }

  function listBox() {
    return h('div', 'max-h-[26rem] divide-y divide-slate-200/70 overflow-y-auto rounded-2xl border border-slate-200 bg-slate-50 dark:divide-white/[0.06] dark:border-white/10 dark:bg-white/[0.035]');
  }

  // ---------------------------------------------------------------------
  // Step 1: choose videos
  // ---------------------------------------------------------------------
  function matches() {
    const q = st.filter.trim().toLowerCase();
    return q ? st.entries.filter((e) => e.title.toLowerCase().includes(q)) : st.entries;
  }

  function parseRange(text) {
    const picked = new Set();
    for (const part of text.split(/[,\s]+/).filter(Boolean)) {
      const m = /^(\d+)(?:\s*[-–]\s*(\d+))?$/.exec(part);
      if (!m) return null;
      const a = Number(m[1]);
      const b = m[2] ? Number(m[2]) : a;
      for (let n = Math.min(a, b); n <= Math.max(a, b) && n <= st.entries.length; n += 1) picked.add(n);
    }
    return picked;
  }

  function renderSelect() {
    const ui = st.ui = {};
    const tools = h('div', 'mb-2 flex flex-wrap items-center gap-2');
    const search = h('input', INPUT + ' min-w-0 flex-1');
    search.type = 'search';
    search.placeholder = 'Search titles';
    search.value = st.filter;
    search.addEventListener('input', () => { st.filter = search.value; st.shown = PAGE; drawList(); });
    const range = h('input', INPUT + ' w-24');
    range.placeholder = 'e.g. 5-20';
    range.setAttribute('aria-label', 'Range of video numbers');
    const applyRange = () => {
      const picked = range.value.trim() ? parseRange(range.value) : null;
      if (!picked) { F.toast('Type a range like 5-20 or 1-3, 8.', 'info'); return; }
      st.selected = picked;
      drawList();
    };
    range.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') { ev.preventDefault(); applyRange(); } });
    const pick = (fn) => () => { matches().forEach((e) => fn(e.n)); drawList(); };
    tools.append(search, range, button('Select range', applyRange),
      button('All', pick((n) => st.selected.add(n))),
      button('None', pick((n) => st.selected.delete(n))),
      button('Invert', pick((n) => (st.selected.has(n) ? st.selected.delete(n) : st.selected.add(n)))));

    ui.list = listBox();
    ui.more = button('Load more', () => { st.shown += PAGE; drawList(); });
    ui.more.classList.add('mt-2', 'w-full');

    const qRow = h('div', 'mt-4 flex items-center justify-between gap-3');
    qRow.append(h('label', 'text-[12px] font-semibold text-slate-700 dark:text-slate-300', 'Quality for all videos'));
    ui.count = h('span', 'text-right text-[11.5px] font-medium tabular-nums text-slate-500 dark:text-slate-400');
    qRow.append(ui.count);
    ui.quality = h('select', 'mt-2 h-12 w-full rounded-xl border border-slate-200 bg-white px-3.5 text-[13px] font-medium text-slate-800 outline-none transition hover:border-slate-300 focus:border-indigo-500 dark:border-white/10 dark:bg-[#0b101c] dark:text-slate-100');
    QUALITIES.forEach(([value, label]) => { const o = h('option', '', label); o.value = value; ui.quality.append(o); });
    ui.quality.value = quality();
    if (!ui.quality.value) ui.quality.value = 'best';
    ui.quality.addEventListener('change', () => {
      try { localStorage.setItem('fetchly-playlist-quality', ui.quality.value); } catch (_) {}
      drawCount();
    });
    ui.start = button('Download', start, MAIN_BTN + ' mt-4');

    panel.replaceChildren(header(), tools, ui.list, ui.more, qRow, ui.quality, ui.start);
    drawList();
  }

  function drawList() {
    const ui = st.ui;
    const all = matches();
    const rows = all.slice(0, st.shown).map((e) => {
      const row = h('label', 'flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-slate-100/80 dark:hover:bg-white/[0.04]');
      const box = h('input', 'h-4 w-4 shrink-0 accent-indigo-600');
      box.type = 'checkbox';
      box.checked = st.selected.has(e.n);
      box.addEventListener('change', () => { if (box.checked) st.selected.add(e.n); else st.selected.delete(e.n); drawCount(); });
      const p = rowParts(e);
      const note = e.unavailable || (e.have ? 'Already downloaded' : '');
      if (note) { p.sub.textContent = `${note} · will be skipped`; row.classList.add('opacity-60'); } else p.sub.remove();
      row.append(box, p.num, p.thumb, p.text, p.dur);
      return row;
    });
    if (!rows.length) rows.push(h('p', 'px-4 py-8 text-center ' + MUTED, 'No videos match your search.'));
    ui.list.replaceChildren(...rows);
    const left = all.length - Math.min(all.length, st.shown);
    ui.more.hidden = left <= 0;
    ui.more.textContent = `Load more (${left} left)`;
    drawCount();
  }

  function drawCount() {
    const ui = st.ui;
    const picked = st.entries.filter((e) => st.selected.has(e.n));
    const real = picked.filter((e) => !e.unavailable && !e.have);
    const timed = real.filter((e) => e.duration > 0);
    let size = '';
    if (timed.length) {
      const seconds = timed.reduce((sum, e) => sum + e.duration, 0) * (real.length / timed.length);
      size = ` · about ${F.formatBytes(seconds * KBPS[ui.quality.value] * 125)}`;
    }
    ui.count.textContent = `${picked.length} of ${st.entries.length} selected${size}`;
    ui.start.disabled = !picked.length;
    ui.start.textContent = picked.length ? `Download ${picked.length} video${picked.length === 1 ? '' : 's'}` : 'Select videos to download';
  }

  // ---------------------------------------------------------------------
  // Step 2: the queue
  // ---------------------------------------------------------------------
  async function start() {
    st.ui.start.disabled = true;
    try {
      const status = await F.api(`/api/playlist/${st.id}/start`, {
        method: 'POST', body: { items: [...st.selected], quality: st.ui.quality.value }, timeout: 60000,
      });
      renderQueue(status);
    } catch (error) {
      st.ui.start.disabled = false;
      F.toast(error.message || 'The download could not start.', 'error', 5200);
    }
  }

  function renderQueue(status) {
    const ui = st.ui = {};
    const top = h('div', BOX + ' mb-3 p-4');
    const line = h('div', 'flex items-center justify-between gap-3');
    ui.stage = h('span', 'text-[12.5px] font-semibold text-slate-700 dark:text-slate-200');
    ui.percent = h('span', 'text-[12.5px] font-semibold tabular-nums text-indigo-600 dark:text-indigo-400');
    line.append(ui.stage, ui.percent);
    const track = h('div', 'mt-3 h-2 overflow-hidden rounded-full bg-slate-200 dark:bg-white/10');
    ui.bar = h('div', 'h-full rounded-full bg-indigo-500 transition-[width] duration-300 ease-out dark:bg-indigo-400');
    track.append(ui.bar);
    ui.counts = h('p', 'mt-2 ' + MUTED);
    ui.buttons = h('div', 'mt-3 flex flex-wrap gap-2');
    top.append(line, track, ui.counts, ui.buttons);

    ui.list = listBox();
    st.rows = new Map();
    const byNumber = new Map(st.entries.map((e) => [e.n, e]));
    status.items.forEach((item) => {
      const e = byNumber.get(item.n);
      const row = h('div', 'flex items-center gap-3 px-3 py-2');
      const p = rowParts(e);
      const track2 = h('span', 'mt-1 block h-1 overflow-hidden rounded-full bg-slate-200 dark:bg-white/10');
      const bar = h('span', 'block h-full rounded-full bg-indigo-500 dark:bg-indigo-400');
      track2.append(bar);
      p.text.append(track2);
      const actions = h('span', 'flex shrink-0 gap-1');
      row.append(p.num, p.thumb, p.text, actions);
      ui.list.append(row);
      st.rows.set(item.n, { sub: p.sub, track: track2, bar, actions, state: null });
    });
    panel.replaceChildren(header(), top, ui.list);
    applyStatus(status);
  }

  function act(action, n) {
    return async () => {
      try {
        applyStatus(await F.api(`/api/playlist/${st.id}/action`, { method: 'POST', body: n == null ? { action } : { action, n }, timeout: 30000 }));
      } catch (error) {
        F.toast(error.message || 'That did not work.', 'error');
      }
    };
  }

  function applyStatus(status) {
    const ui = st.ui;
    if (!ui.stage) return;
    st.status = status;
    const count = { done: 0, failed: 0, skipped: 0, cancelled: 0, paused: 0 };
    let open = 0;
    let parts = 0;
    status.items.forEach((item) => {
      const row = st.rows.get(item.n);
      if (!row) return;
      if (item.state in count) count[item.state] += 1;
      if (OPEN.includes(item.state)) { open += 1; parts += (item.percent || 0) / 100; } else parts += 1;

      let text = LABEL[item.state] || '';
      if (item.state === 'downloading') text = `${Math.round(item.percent)}%${item.speed ? ' · ' + F.formatBytes(item.speed) + '/s' : ''}`;
      if (item.state === 'paused' && item.percent > 0) text = `Paused at ${Math.round(item.percent)}%`;
      if (item.state === 'failed') text = `Failed: ${item.error || 'unknown error'}`;
      if (item.state === 'skipped') text = `Skipped: ${item.error || 'not available'}`;
      row.sub.textContent = text;
      row.sub.title = text;
      row.bar.style.width = `${item.percent || 0}%`;
      if (row.state === item.state) return;
      row.state = item.state;
      row.track.hidden = !['starting', 'downloading', 'processing', 'paused'].includes(item.state);
      row.sub.className = 'block truncate text-[11.5px] ' + ({
        done: 'text-emerald-600 dark:text-emerald-400', failed: 'text-rose-600 dark:text-rose-400', skipped: 'text-amber-600 dark:text-amber-400',
      }[item.state] || 'text-slate-400 dark:text-slate-500');
      const buttons = [];
      if (['queued', 'starting', 'downloading'].includes(item.state)) buttons.push(button('Pause', act('pause', item.n)));
      if (item.state === 'paused') buttons.push(button('Resume', act('resume', item.n)));
      if (['queued', 'starting', 'downloading', 'paused'].includes(item.state)) buttons.push(button('Cancel', act('cancel', item.n)));
      if (['failed', 'cancelled'].includes(item.state)) buttons.push(button('Retry', act('retry', item.n)));
      row.actions.replaceChildren(...buttons);
    });

    const total = status.items.length;
    const percent = total ? Math.floor((parts / total) * 100) : 100;
    const finished = open === 0;
    ui.bar.style.width = `${percent}%`;
    ui.percent.textContent = `${percent}%`;
    ui.stage.textContent = finished ? 'Finished' : status.running ? `Downloading · ${total - open} of ${total} finished` : 'Paused';
    const bits = [`${count.done} done`, `${count.failed} failed`, `${count.skipped} skipped`];
    if (count.cancelled) bits.push(`${count.cancelled} cancelled`);
    ui.counts.textContent = bits.join(' · ') + (status.folder ? ` · Saved in ${status.folder}` : '');

    const key = `${finished}|${status.running}|${count.paused > 0}|${count.failed > 0}`;
    if (ui.key !== key) {
      ui.key = key;
      const buttons = [];
      if (!finished && status.running) buttons.push(button('Pause all', act('pause')));
      if (count.paused) buttons.push(button('Resume all', act('resume')));
      if (!finished) buttons.push(button('Cancel all', act('cancel')));
      buttons.push(button('Open folder', () => F.api(`/api/playlist/${st.id}/open`, { method: 'POST' }).catch(() => {})));
      if (count.failed && (finished || !status.running)) buttons.push(button('Retry failed', act('retry')));
      if (finished) buttons.push(button('Back to the list', backToList));
      ui.buttons.replaceChildren(...buttons);
      if (finished) F.toast(`Playlist finished: ${bits.join(', ')}.`, count.failed ? 'info' : 'success', 6000);
    }

    stopPolling();
    if (status.running) st.timer = setTimeout(poll, 800);
  }

  async function poll() {
    const id = st.id;
    try {
      const status = await F.api(`/api/playlist/${id}/status`, { timeout: 20000 });
      if (id === st.id) applyStatus(status);
    } catch (_) {
      if (id === st.id) st.timer = setTimeout(poll, 2500);
    }
  }

  function stopPolling() {
    clearTimeout(st.timer);
    st.timer = null;
  }

  function backToList() {
    const done = new Set((st.status ? st.status.items : []).filter((i) => i.state === 'done').map((i) => i.n));
    st.entries.forEach((e) => { if (done.has(e.n)) e.have = true; });
    st.status = null;
    renderSelect();
  }

  window.FetchyPlaylist = {
    open,
    busy: () => Boolean(st.status && st.status.items.some((i) => OPEN.includes(i.state))),
  };
})();
