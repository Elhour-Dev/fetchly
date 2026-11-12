// Lets the Fetchy page ask the app to open the in-app YouTube login.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('fetchyDesktop', {
  youtubeLogin: () => ipcRenderer.invoke('youtube-login'),
});
