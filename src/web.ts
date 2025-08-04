import { WebPlugin } from "@capacitor/core";

import type {
  BackgroundGeolocationPlugin,
  StartOptions,
  Location,
  CallbackError,
  SetPlannedRouteOptions,
} from "./definitions";

export class BackgroundGeolocationWeb
  extends WebPlugin
  implements BackgroundGeolocationPlugin
{
  private static readonly EARTH_RADIUS_M = 6371000;

  private watchId: number | undefined;
  private plannedRoute: [number, number][] = [];
  private audio: HTMLAudioElement | undefined;
  private isOffRoute = true;
  private distanceThreshold = 50;

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
        if (this.audio && this.plannedRoute.length > 0) {
          const currentPoint: [number, number] = [
            position.coords.longitude,
            position.coords.latitude,
          ];
          const offRoute =
            this.distancePointToRoute(currentPoint) > this.distanceThreshold;
          if (offRoute == true && this.isOffRoute === false) {
            this.audio.play();
          }
          this.isOffRoute = offRoute;
        }
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

  async setPlannedRoute(options: SetPlannedRouteOptions): Promise<void> {
    if (!options.soundFile) {
      throw new Error("Sound file is required");
    }
    if (this.audio) {
      this.audio.pause();
      this.audio.src = '';
      this.audio = undefined;
    }
    this.audio = new Audio(options.soundFile);
    this.plannedRoute = options.route || [];
    this.distanceThreshold = options.distance || 50;
  }

  private toRadians(degrees: number): number {
    return (degrees * Math.PI) / 180;
  }

  private haversine(
    point1: [number, number],
    point2: [number, number],
  ): number {
    const [lon1, lat1] = point1;
    const [lon2, lat2] = point2;
    const dLat = this.toRadians(lat2 - lat1);
    const dLon = this.toRadians(lon2 - lon1);

    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(this.toRadians(lat1)) *
        Math.cos(this.toRadians(lat2)) *
        Math.sin(dLon / 2) *
        Math.sin(dLon / 2);

    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return BackgroundGeolocationWeb.EARTH_RADIUS_M * c;
  }

  private distancePointToLineSegment(
    point: [number, number],
    lineStart: [number, number],
    lineEnd: [number, number],
  ): number {
    // Calculate the distances between the three points using Haversine
    const dist_A_B = this.haversine(point, lineStart);
    const dist_A_C = this.haversine(point, lineEnd);
    const dist_B_C = this.haversine(lineStart, lineEnd);

    // Handle the edge case where the line segment is a single point
    if (dist_B_C === 0) {
      return dist_A_B;
    }

    // Check if the angles at the line segment's endpoints are obtuse.
    // We use the Law of Cosines (c^2 = a^2 + b^2 - 2ab*cos(C))
    // If cos(C) < 0, the angle is obtuse.

    // Angle at B (lineStart)
    // Use a small epsilon to handle floating point inaccuracies in division by zero
    const cos_B =
      (dist_A_B ** 2 + dist_B_C ** 2 - dist_A_C ** 2) /
      (2 * dist_A_B * dist_B_C + Number.EPSILON);
    if (cos_B < 0) {
      return dist_A_B;
    }

    // Angle at C (lineEnd)
    const cos_C =
      (dist_A_C ** 2 + dist_B_C ** 2 - dist_A_B ** 2) /
      (2 * dist_A_C * dist_B_C + Number.EPSILON);
    if (cos_C < 0) {
      return dist_A_C;
    }

    // If both angles are acute, the closest point is on the line segment itself.
    // We can calculate the distance (height of the triangle) using its area.

    // 1. Calculate the semi-perimeter of the triangle ABC
    const s = (dist_A_B + dist_A_C + dist_B_C) / 2;

    // 2. Calculate the area using Heron's formula
    const area = Math.sqrt(
      Math.max(0, s * (s - dist_A_B) * (s - dist_A_C) * (s - dist_B_C)),
    );

    // 3. The distance is the height of the triangle from point A to the base BC
    // Area = 0.5 * base * height  =>  height = 2 * Area / base
    return (2 * area) / (dist_B_C + Number.EPSILON);
  }

  private distancePointToRoute(point: [number, number]): number {
    // If the route has less than 2 points, we can't form a segment.
    if (this.plannedRoute.length < 2) {
      if (this.plannedRoute.length === 1) {
        return this.haversine(point, this.plannedRoute[0]);
      }
      return Infinity; // No line segments to measure against
    }

    let minDistance = Infinity;

    for (let i = 0; i < this.plannedRoute.length - 1; i++) {
      const lineStart = this.plannedRoute[i];
      const lineEnd = this.plannedRoute[i + 1];
      const distance = this.distancePointToLineSegment(
        point,
        lineStart,
        lineEnd,
      );
      if (distance < minDistance) {
        minDistance = distance;
      }
    }

    return minDistance;
  }
}
