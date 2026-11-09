// Checks GitHub for a newer app version. Failures are silent: the app never waits for or depends on this.
const fs = require('fs'), path = require('path');

const API = 'https://api.github.com/repos/Elhour-Dev/fetchly/releases/latest';
const DAY = 24 * 60 * 60 * 1000;

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

// Returns { tag, url } or null (offline, rate limited, draft/prerelease...). fetchFn: fetch-like function.
async function latestRelease(fetchFn) {
  try {
    const r = await fetchFn(API, { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'Fetchy-Desktop' } });
    if (!r.ok) return null;
    const j = await r.json();
    if (j.draft || j.prerelease || !j.tag_name || !j.html_url) return null;
    return { tag: j.tag_name, url: j.html_url };
  } catch { return null; }
}

// At most one check per day: remember when we last asked GitHub (even if it failed).
function dueToday(file) {
  try { return Date.now() - JSON.parse(fs.readFileSync(file, 'utf8')).last >= DAY; } catch { return true; }
}
function markChecked(file) {
  try { fs.writeFileSync(file, JSON.stringify({ last: Date.now() })); } catch {}
}

// Shows the pop-up when a newer version exists. Call after the window is up; never throws, never installs.
async function checkForUpdate({ dialog, shell, net, win, version, dataDir }) {
  try {
    const file = path.join(dataDir, 'update-check.json');
    if (!dueToday(file)) return;
    markChecked(file);
    const rel = await latestRelease((u, o) => net.fetch(u, o));
    if (!rel || !isNewer(rel.tag, version)) return;
    const { response } = await dialog.showMessageBox(win, {
      type: 'info', title: 'Fetchy', message: `New version ${rel.tag.replace(/^v/, '')}`,
      detail: 'A newer version of Fetchy is available. Download opens the release page.',
      buttons: ['Download', 'Later'], defaultId: 0, cancelId: 1,
    });
    if (response === 0) shell.openExternal(rel.url);
  } catch {}
}

module.exports = { isNewer, latestRelease, checkForUpdate };
