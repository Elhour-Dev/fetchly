// Electron wrapper for Fetchy: starts the bundled Python server and shows it in a window.
const { app, BrowserWindow, dialog, shell, net: electronNet } = require('electron');
const { checkForUpdate } = require('./update');
const { spawn, execFile } = require('child_process');
const fs = require('fs'), path = require('path'), net = require('net'), http = require('http');

// Keep the old data folder (login, settings) now that the app is named Fetchy.
app.setPath('userData', path.join(app.getPath('appData'), 'Fetchly'));

let server = null, win = null, logFile = null;

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

  const port = await freePort();
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
  await waitForServer(port);

  win = new BrowserWindow({
    width: 1100, height: 800, title: 'Fetchy', autoHideMenuBar: true,
    icon: path.join(__dirname, 'assets', 'icon.ico'),
  });
  win.setMenuBarVisibility(false);
  const origin = `http://127.0.0.1:${port}`;
  win.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('will-navigate', (e, url) => { if (!url.startsWith(origin)) { e.preventDefault(); shell.openExternal(url); } });
  // Downloads go straight to the user's Downloads folder, no dialog.
  win.webContents.session.on('will-download', (_e, item) => {
    item.setSavePath(uniquePath(app.getPath('downloads'), item.getFilename()));
  });
  win.loadURL(origin);
  checkForUpdate({ dialog, shell, net: electronNet, win, version: app.getVersion(), dataDir: data });
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(() => start().catch(e => { dialog.showErrorBox('Fetchy', String(e.message || e)); killServer(); app.quit(); }));
  app.on('window-all-closed', () => app.quit());
  app.on('before-quit', killServer);
}
