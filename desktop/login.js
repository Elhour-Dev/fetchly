// In-app Google/YouTube login: a window with its own saved session. Returns cookies.txt text, or null if not logged in.
const { BrowserWindow, session } = require('electron');

const START = 'https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F';
const PARTITION = 'persist:fetchy-login';
const HOSTS = /(^|\.)(youtube\.com|google\.com)$/i;

function netscape(cookies) {
  const week = Math.floor(Date.now() / 1000) + 180 * 24 * 3600;
  return cookies.filter(c => HOSTS.test(c.domain.replace(/^\./, ''))).map(c => {
    const dom = (c.httpOnly ? '#HttpOnly_' : '') + c.domain;
    const exp = c.expirationDate ? Math.floor(c.expirationDate) : week;
    return [dom, c.domain.startsWith('.') ? 'TRUE' : 'FALSE', c.path || '/', c.secure ? 'TRUE' : 'FALSE', exp, c.name, String(c.value).replace(/[\t\r\n]/g, '')].join('\t');
  });
}

async function exportCookies() {
  const all = await session.fromPartition(PARTITION).cookies.get({});
  const names = new Set(all.filter(c => HOSTS.test(c.domain.replace(/^\./, ''))).map(c => c.name));
  if (!names.has('SAPISID') && !names.has('__Secure-3PSID')) return null;   // no login
  return '# Netscape HTTP Cookie File\n' + netscape(all).join('\n') + '\n';
}

function youtubeLogin(parent) {
  return new Promise(ok => {
    const ses = session.fromPartition(PARTITION);
    // Google refuses sign-in from "Electron": send a plain Chrome user agent.
    const ua = ses.getUserAgent().replace(/\s?Electron\/\S+/i, '').replace(/\s?fetchly\/\S+/i, '').replace(/\s?Fetchy\/\S+/i, '');
    ses.setUserAgent(ua);
    const w = new BrowserWindow({
      width: 520, height: 720, parent, modal: true, title: 'Log in to YouTube', autoHideMenuBar: true,
      webPreferences: { partition: PARTITION },
    });
    w.setMenuBarVisibility(false);
    let done = false;
    const finish = async () => {
      if (done) return; done = true;
      let text = null; try { text = await exportCookies(); } catch {}
      if (!w.isDestroyed()) w.destroy();
      ok(text);
    };
    // Logged in once the browser lands back on youtube.com: close by itself.
    w.webContents.on('did-navigate', async (_e, url) => {
      try { if (/^https:\/\/(www|m)\.youtube\.com\//.test(url) && await exportCookies()) finish(); } catch {}
    });
    w.on('close', e => { if (!done) { e.preventDefault(); finish(); } });
    w.loadURL(START, { userAgent: ua });
  });
}

module.exports = { youtubeLogin };
