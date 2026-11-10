// Checks GitHub for a newer app version. Failures are silent: the start screen just goes on to the app.
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

// Newest release when it is newer than [version], else null. Gives up after [ms] (slow or no network): never throws.
async function findUpdate(fetchFn, version, ms = 6000) {
  const timer = new Promise(ok => setTimeout(() => ok(null), ms));
  const rel = await Promise.race([latestRelease(fetchFn), timer]);
  return rel && isNewer(rel.tag, version) ? rel : null;
}

module.exports = { isNewer, latestRelease, findUpdate };
