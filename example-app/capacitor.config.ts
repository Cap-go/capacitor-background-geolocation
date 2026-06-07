import type { CapacitorConfig } from '@capacitor/cli';

import pkg from './package.json';

const config: CapacitorConfig = {
  appId: 'com.capgo.backgroundgeolocation.example',
  appName: 'Background Geolocation Example',
  webDir: 'dist',
  plugins: {
    CapacitorUpdater: {
      appId: 'com.capgo.backgroundgeolocation.example',
      autoUpdate: true,
      autoSplashscreen: true,
      directUpdate: 'always',
      defaultChannel: 'production',
      version: pkg.version,
    },
  },
};

export default config;
