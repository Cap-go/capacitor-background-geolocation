import './style.css';
import { BackgroundGeolocation } from '@capgo/background-geolocation';
import { LocalNotifications } from '@capacitor/local-notifications';

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

document.querySelector('#app').innerHTML = '<h1>Background Geolocation</h1><div><ul id="watchers"></ul><button id="addFG">Add FG Watcher</button><button id="addBG">Add BG Watcher</button><button id="requestPerms">Request Permissions</button></div><ul id="log"><li>Init</li></ul>';

document.getElementById('addFG').onclick = () => log('FG watcher clicked');
document.getElementById('addBG').onclick = () => log('BG watcher clicked');
document.getElementById('requestPerms').onclick = async () => {
  try {
    const result = await LocalNotifications.requestPermissions();
    log('Permissions: ' + result.display);
  } catch (e) {
    log('Error: ' + e.message);
  }
};

log('Ready');
