// Draws a simple icon (blue square, white down-arrow) as assets/icon.png using Python Pillow.
const { execFileSync } = require('child_process');
const path = require('path');
const out = path.join(__dirname, '..', 'assets', 'icon.png');
execFileSync('python', ['-c', `
from PIL import Image, ImageDraw
S=1024
im=Image.new('RGBA',(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
d.rounded_rectangle((0,0,S-1,S-1),radius=220,fill=(37,99,235,255))
w=(255,255,255,255)
d.rectangle((432,200,592,560),fill=w)
d.polygon([(250,520),(774,520),(512,810)],fill=w)
d.rounded_rectangle((250,860,774,920),radius=30,fill=w)
im.resize((512,512),Image.LANCZOS).save(r'${out}')
`], { stdio: 'inherit' });
