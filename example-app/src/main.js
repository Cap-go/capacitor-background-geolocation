import { CapacitorUpdater } from '@capgo/capacitor-updater';
import { Capacitor } from '@capacitor/core';
import './style.css';
import { BackgroundGeolocation } from '@capgo/background-geolocation';

const plugin = BackgroundGeolocation;
const state = { watchers: {}, startTime: Date.now() };

const colors = ['red', 'green', 'blue', 'yellow', 'pink', 'orange', 'purple', 'cyan'];
let colorIndex = 0;

function timestamp(time) {
  return String(Math.floor((time - state.startTime) / 1000));
}

function log(text, time = Date.now(), color = 'gray') {
  const li = document.createElement('li');
  li.style.color = color;
  li.textContent = 'L' + timestamp(time) + ':W' + timestamp(Date.now()) + ':' + text;
  const container = document.getElementById('log');
  container.insertBefore(li, container.firstChild);
}

document.querySelector('#app').innerHTML =
  '<h1>Background Geolocation</h1><div><ul id="watchers"></ul><button id="addFG">Add FG Watcher</button><button id="addBG">Add BG Watcher</button><button id="requestPerms">Request Permissions</button></div><ul id="log"><li>Init</li></ul>';

document.getElementById('addFG').onclick = () => log('FG watcher clicked');
document.getElementById('addBG').onclick = () => log('BG watcher clicked');
document.getElementById('requestPerms').onclick = async () => {
  try {
    const status = await plugin.checkPermissions();
    log('Check permissions: ' + JSON.stringify(status));
    const requested = await plugin.requestPermissions({ permissions: ['location', 'backgroundLocation', 'notification'] });
    log('Request permissions: ' + JSON.stringify(requested));
  } catch (e) {
    log('Error: ' + e.message);
  }
};

log('Ready');

if (Capacitor.isNativePlatform()) {
  CapacitorUpdater.notifyAppReady().catch((error) => {
    console.error('Capgo notifyAppReady failed', error);
  });
}
