// Electron wrapper for Fetchy: starts the bundled Python server and shows it in a window.
const { app, BrowserWindow, dialog, shell, ipcMain, nativeTheme, session, net: electronNet } = require('electron');
const { findUpdate } = require('./update');
const { spawn, execFile } = require('child_process');
const fs = require('fs'), path = require('path'), net = require('net'), http = require('http');

// Keep the old data folder (login, settings) now that the app is named Fetchy.
app.setPath('userData', path.join(app.getPath('appData'), 'Fetchly'));

app.setAppUserModelId('app.fetchly.desktop');   // needed for Windows "download finished" notifications

let server = null, win = null, splash = null, logFile = null;

const res = app.isPackaged ? process.resourcesPath : path.join(__dirname, 'vendor');
const appDir = app.isPackaged ? path.join(res, 'app') : path.join(__dirname, '..');
const pythonExe = path.join(res, 'python', 'python.exe');
const ffmpegDir = path.join(res, 'ffmpeg');

function freePort() {
  return new Promise((ok, bad) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => ok(p)); });
    s.on('error', bad);
  });
}

// Same port on every start when it is free: the page keeps its settings and history (the browser saves them per address).
const HOME_PORT = 47815;
function portFree(p) {
  return new Promise(ok => {
    const s = net.createServer();
    s.once('error', () => ok(false));
    s.listen(p, '127.0.0.1', () => s.close(() => ok(true)));
  });
}

function waitForServer(port, tries = 120) {
  return new Promise((ok, bad) => {
    const t = () => {
      http.get({ host: '127.0.0.1', port, path: '/', timeout: 1000 }, r => { r.resume(); ok(); })
        .on('error', () => (--tries > 0 ? setTimeout(t, 500) : bad(new Error('Server did not start.'))));
    };
    t();
  });
}

function uniquePath(dir, name) {
  const ext = path.extname(name), base = path.basename(name, ext);
  let p = path.join(dir, name), i = 1;
  while (fs.existsSync(p)) p = path.join(dir, `${base} (${i++})${ext}`);
  return p;
}

// Start screen: shown at once, checks for a new version, then shows "Starting…" until the app is ready.
function openSplash() {
  splash = new BrowserWindow({
    width: 440, height: 480, resizable: false, maximizable: false, title: 'Fetchy', autoHideMenuBar: true, show: false,
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#080b14' : '#f8fafc',
    icon: path.join(__dirname, 'assets', 'icon.ico'),
    webPreferences: { preload: path.join(__dirname, 'splash-preload.js') },
  });
  splash.setMenuBarVisibility(false);
  splash.once('ready-to-show', () => splash && splash.show());
  splash.on('closed', () => { splash = null; });   // closing it quits Fetchy (no other window yet)
  const loaded = new Promise(ok => splash.webContents.once('did-finish-load', ok));
  splash.loadFile(path.join(__dirname, 'splash.html'));
  return loaded;
}

function splashState(s) { if (splash) splash.webContents.send('splash-state', s); }
function splashChoice() { return new Promise(ok => ipcMain.once('splash-choice', (_e, c) => ok(c))); }

// Downloads the installer with a progress bar and opens it. True: the installer runs, so Fetchy closes. False: go on to the app.
function installUpdate(rel) {
  if (!rel.file) { shell.openExternal(rel.url); return Promise.resolve(false); }
  return new Promise(done => {
    splashState({ step: 'downloading', percent: -1 });
    session.defaultSession.once('will-download', (_e, item) => {   // the save path is set by the handler in start()
      item.on('updated', () => {
        const total = item.getTotalBytes();
        splashState({ step: 'downloading', percent: total ? Math.round(item.getReceivedBytes() * 100 / total) : -1 });
      });
      item.once('done', async (_ev, state) => {
        if (state !== 'completed') { shell.openExternal(rel.url); return done(false); }
        const err = await shell.openPath(item.getSavePath());
        if (err) { shell.showItemInFolder(item.getSavePath()); return done(false); }
        done(true);
      });
    });
    splash.webContents.downloadURL(rel.file);
  });
}

// Only web links go to the browser, never file:// or other app links.
function openWeb(url) { if (/^https?:\/\//i.test(String(url))) shell.openExternal(url); }

function killServer() {
  if (server && server.pid) execFile('taskkill', ['/pid', String(server.pid), '/T', '/F'], () => {});
  server = null;
}

async function start() {
  const data = app.getPath('userData');
  const dataDir = path.join(data, 'data');
  const toolsDir = path.join(data, 'tools');
  fs.mkdirSync(dataDir, { recursive: true });
  // After an app upgrade, drop old downloaded tools so the new bundled ones are used.
  const marker = path.join(data, 'tools-for-app-version.txt');
  let seen = ''; try { seen = fs.readFileSync(marker, 'utf8'); } catch {}
  if (seen !== app.getVersion()) {
    fs.rmSync(toolsDir, { recursive: true, force: true });
    fs.writeFileSync(marker, app.getVersion());
  }
  fs.mkdirSync(toolsDir, { recursive: true });

  // Downloads go straight to the user's Downloads folder, no dialog.
  session.defaultSession.on('will-download', (_e, item) => {
    item.setSavePath(uniquePath(app.getPath('downloads'), item.getFilename()));
  });

  const port = (await portFree(HOME_PORT)) ? HOME_PORT : await freePort();
  logFile = fs.createWriteStream(path.join(data, 'server.log'), { flags: 'w' });
  server = spawn(pythonExe, ['main.py'], {
    cwd: appDir,
    windowsHide: true,
    env: {
      ...process.env,
      HOST: '127.0.0.1', PORT: String(port),
      FETCHLY_DATA_DIR: dataDir, FETCHLY_TOOLS_DIR: toolsDir,
      FETCHLY_SAVE_DIR: app.getPath('downloads'),   // playlists are saved here, in a folder per playlist
      PATH: ffmpegDir + path.delimiter + process.env.PATH,
      PYTHONUTF8: '1', PYTHONDONTWRITEBYTECODE: '1',
    },
  });
  server.stdout.pipe(logFile, { end: false });
  server.stderr.pipe(logFile, { end: false });
  server.on('exit', code => { if (server) { dialog.showErrorBox('Fetchy', `The engine stopped (code ${code}). See server.log in ${data}`); app.quit(); } });
  const ready = waitForServer(port);
  ready.catch(() => {});   // handled below, after the start screen

  await openSplash();
  splashState({ step: 'checking' });
  const rel = await findUpdate((u, o) => electronNet.fetch(u, o), app.getVersion());
  if (!splash) return;
  if (rel) {
    splashState({ step: 'update', version: rel.tag.replace(/^v/, ''), notes: rel.notes.slice(0, 1200) });
    if (await splashChoice() === 'update' && await installUpdate(rel)) { app.quit(); return; }
    if (!splash) return;
  }
  splashState({ step: 'starting' });
  await ready;
  if (!splash) return;

  win = new BrowserWindow({
    width: 1100, height: 800, title: 'Fetchy', autoHideMenuBar: true, show: false,
    icon: path.join(__dirname, 'assets', 'icon.ico'),
  });
  win.setMenuBarVisibility(false);
  const origin = `http://127.0.0.1:${port}`;
  win.webContents.setWindowOpenHandler(({ url }) => { openWeb(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith(origin + '/') && url !== origin) { e.preventDefault(); openWeb(url); } });
  win.once('ready-to-show', () => { win.show(); if (splash) splash.close(); });
  win.loadURL(origin);
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => {
    const w = win || splash;
    if (w) { if (w.isMinimized()) w.restore(); w.focus(); }
  });
  app.whenReady().then(() => start().catch(e => { dialog.showErrorBox('Fetchy', String(e.message || e)); killServer(); app.quit(); }));
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', killServer);
}
