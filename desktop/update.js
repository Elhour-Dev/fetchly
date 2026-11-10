// Checks GitHub for a newer app version. Failures are silent: the app never waits for or depends on this.
const API = 'https://api.github.com/repos/Elhour-Dev/fetchly/releases/latest';

// "v1.2.0" vs "1.0.0": true when latest is higher than current.
function isNewer(latest, current) {
  const parts = v => String(v).trim().replace(/^v/, '').split('.').map(x => parseInt(x, 10) || 0);
  const a = parts(latest), b = parts(current);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i] || 0, y = b[i] || 0;
    if (x !== y) return x > y;
  }
  return false;
}

// Returns { tag, url, file } or null (offline, rate limited, draft/prerelease...). file: direct .exe link, or null. fetchFn: fetch-like function.
async function latestRelease(fetchFn) {
  try {
    const r = await fetchFn(API, { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Fetchy-Desktop' } });
    if (!r.ok) return null;
    const j = await r.json();
    if (j.draft || j.prerelease || !j.tag_name || !j.html_url) return null;
    const exe = (j.assets || []).find(a => /\.exe$/i.test(a.name || '') && a.browser_download_url);
    return { tag: j.tag_name, url: j.html_url, file: exe ? exe.browser_download_url : null, notes: String(j.body || '').trim() };
  } catch { return null; }
}

// Shows the pop-up on every start while a newer version exists. Call after the window is up; never throws, never installs.
async function checkForUpdate({ dialog, shell, net, win, version }) {
  try {
    const rel = await latestRelease((u, o) => net.fetch(u, o));
    if (!rel || !isNewer(rel.tag, version)) return;
    const { response } = await dialog.showMessageBox(win, {
      type: 'info', title: 'Fetchy', message: `New version ${rel.tag.replace(/^v/, '')}`,
      detail: (rel.notes ? `What's new:\n${rel.notes.slice(0, 1200)}\n\n` : '') + (rel.file ? 'Download saves the installer to your Downloads folder.' : 'Download opens the release page.'),
      buttons: ['Download', 'Later'], defaultId: 0, cancelId: 1,
    });
    if (response !== 0) return;
    if (!rel.file) return shell.openExternal(rel.url);
    // Download inside the app (main.js saves it to Downloads), then show the file.
    win.webContents.session.once('will-download', (_e, item) => {
      item.once('done', (_ev, state) => {
        if (state === 'completed') shell.showItemInFolder(item.getSavePath());
        else shell.openExternal(rel.url);
      });
    });
    win.webContents.downloadURL(rel.file);
  } catch {}
}

module.exports = { isNewer, latestRelease, checkForUpdate };
