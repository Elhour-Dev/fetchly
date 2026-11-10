// Bridge for the start screen: the page shows what main.js sends and reports the button the user pressed.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fetchy', {
  onState: fn => ipcRenderer.on('splash-state', (_e, s) => fn(s)),
  choose: choice => ipcRenderer.send('splash-choice', choice),
});
