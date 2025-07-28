import { WebPlugin } from "@capacitor/core";

import type {
  BackgroundGeolocationPlugin,
  WatcherOptions,
  Location,
  CallbackError,
} from "./definitions";

export class BackgroundGeolocationWeb
  extends WebPlugin
  implements BackgroundGeolocationPlugin
{
  private watchers = new Map<
    string,
    {
      watchId: number;
      callback: (position?: Location, error?: CallbackError) => void;
    }
  >();
  private watcherCounter = 0;

  async addWatcher(
    options: WatcherOptions,
    callback: (position?: Location, error?: CallbackError) => void,
  ): Promise<string> {
    const watcherId = `watcher_${++this.watcherCounter}`;

    if (!navigator.geolocation) {
      callback(undefined, {
        name: "GeolocationError",
        message: "Geolocation is not supported by this browser",
        code: "NOT_SUPPORTED",
      });
      return watcherId;
    }

    const watchId = navigator.geolocation.watchPosition(
      (position) => {
        const location: Location = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          accuracy: position.coords.accuracy,
          altitude: position.coords.altitude,
          altitudeAccuracy: position.coords.altitudeAccuracy,
          simulated: false,
          bearing: position.coords.heading,
          speed: position.coords.speed,
          time: position.timestamp,
        };
        callback(location);
      },
      (error) => {
        const callbackError: CallbackError = {
          name: "GeolocationError",
          message: error.message,
          code: error.code.toString(),
        };
        callback(undefined, callbackError);
      },
      {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: options.stale ? 300000 : 0,
      },
    );

    this.watchers.set(watcherId, { watchId, callback });
    return watcherId;
  }

  async removeWatcher(options: { id: string }): Promise<void> {
    const watcher = this.watchers.get(options.id);
    if (watcher) {
      navigator.geolocation.clearWatch(watcher.watchId);
      this.watchers.delete(options.id);
    }
  }

  async openSettings(): Promise<void> {
    console.log("openSettings: Web implementation cannot open native settings");
    window.alert("Please enable location permissions in your browser settings");
  }
}
