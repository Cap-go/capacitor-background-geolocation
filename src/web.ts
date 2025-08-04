import { WebPlugin } from "@capacitor/core";

import type {
  BackgroundGeolocationPlugin,
  StartOptions,
  Location,
  CallbackError,
  PlaySoundOptions,
} from "./definitions";

export class BackgroundGeolocationWeb
  extends WebPlugin
  implements BackgroundGeolocationPlugin
{
  private watchId: number | undefined;

  async start(
    options: StartOptions,
    callback: (position?: Location, error?: CallbackError) => void,
  ): Promise<void> {
    if (!navigator.geolocation) {
      callback(undefined, {
        name: "GeolocationError",
        message: "Geolocation is not supported by this browser",
        code: "NOT_SUPPORTED",
      });
      return;
    }

    if (this.watchId) {
      callback(undefined, {
        name: "GeolocationError",
        message: "Geolocation already started",
        code: "ALREADY_STARTED",
      });
      return;
    }

    this.watchId = navigator.geolocation.watchPosition(
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
  }

  async stop(): Promise<void> {
    if (this.watchId) {
      navigator.geolocation.clearWatch(this.watchId);
      delete this.watchId;
    }
  }

  async openSettings(): Promise<void> {
    console.log("openSettings: Web implementation cannot open native settings");
    window.alert("Please enable location permissions in your browser settings");
  }

  async playSound(options: PlaySoundOptions): Promise<void> {
    if (!options.soundFile) {
      throw new Error("Sound file is required");
    }
    const audio = new Audio(options.soundFile);
    try {
      await audio.play();
    } catch (error) {
      throw new Error(`Failed to play sound: ${(error as Error).message}`);
    }
  }
}
