// Gets Python (embeddable), Python packages and ffmpeg into desktop/vendor.
const fs = require('fs'), path = require('path'), https = require('https');
const { execFileSync } = require('child_process');
const vendor = path.join(__dirname, '..', 'vendor');
const root = path.join(__dirname, '..', '..');
const PY = '3.12.8';

function download(url, dest) {
  return new Promise((resolve, reject) => {
    https.get(url, r => {
      if (r.statusCode >= 300 && r.statusCode < 400 && r.headers.location) return resolve(download(r.headers.location, dest));
      if (r.statusCode !== 200) return reject(new Error(url + ' -> ' + r.statusCode));
      r.pipe(fs.createWriteStream(dest)).on('finish', resolve).on('error', reject);
    }).on('error', reject);
  });
}

(async () => {
  fs.mkdirSync(vendor, { recursive: true });
  const pyDir = path.join(vendor, 'python');
  if (!fs.existsSync(path.join(pyDir, 'python.exe'))) {
    const zip = path.join(vendor, 'py.zip');
    await download(`https://www.python.org/ftp/python/${PY}/python-${PY}-embed-amd64.zip`, zip);
    fs.mkdirSync(pyDir, { recursive: true });
    execFileSync(path.join(process.env.SystemRoot, 'System32', 'tar.exe'), ['-xf', zip, '-C', pyDir]);
    fs.unlinkSync(zip);
    // Let the embedded Python see our packages folder (resources/pylib).
    fs.writeFileSync(path.join(pyDir, 'python312._pth'), ['python312.zip', '.', '../pylib', '../app', ''].join(String.fromCharCode(10)));
  }
  const lib = path.join(vendor, 'pylib');
  if (!fs.existsSync(path.join(lib, 'yt_dlp'))) {
    execFileSync('python', ['-m', 'pip', 'install', '--target', lib, '--only-binary', ':all:',
      '--disable-pip-version-check', 'pip', '-r', path.join(root, 'requirements.txt')], { stdio: 'inherit' });
  }
  const ff = path.join(vendor, 'ffmpeg');
  fs.mkdirSync(ff, { recursive: true });
  // Use the ffmpeg.exe found on this PC (or set FFMPEG_EXE to another file).
  const where = path.join(process.env.SystemRoot, 'System32', 'where.exe');
  const src = process.env.FFMPEG_EXE || execFileSync(where, ['ffmpeg']).toString().split(String.fromCharCode(10))[0].trim();
  fs.copyFileSync(fs.realpathSync(src), path.join(ff, 'ffmpeg.exe'));
  console.log('vendor ready');
})().catch(e => { console.error(e); process.exit(1); });
