/**
 * The options for configuring for location updates.
 *
 * @since 7.0.9
 */
export interface StartOptions {
  /**
   * If the "backgroundMessage" option is defined, the plugin will
   * provide location updates whether the app is in the background or the
   * foreground. If it is not defined, location updates are only
   * guaranteed in the foreground. This is true on both platforms.
   *
   * On Android, a notification must be shown to continue receiving
   * location updates in the background. This option specifies the text of
   * that notification.
   *
   * @since 7.0.9
   * @example "Getting your location to provide better service"
   */
  backgroundMessage?: string;
  /**
   * The title of the notification mentioned above.
   *
   * @since 7.0.9
   * @default "Using your location"
   * @example "Location Service"
   */
  backgroundTitle?: string;
  /**
   * Whether permissions should be requested from the user automatically,
   * if they are not already granted.
   *
   * @since 7.0.9
   * @default true
   * @example
   * // Auto-request permissions
   * requestPermissions: true
   *
   * // Don't auto-request, handle manually
   * requestPermissions: false
   */
  requestPermissions?: boolean;
  /**
   * If "true", stale locations may be delivered while the device
   * obtains a GPS fix. You are responsible for checking the "time"
   * property. If "false", locations are guaranteed to be up to date.
   *
   * @since 7.0.9
   * @default false
   * @example
   * // Allow stale locations for faster initial response
   * stale: true
   *
   * // Only fresh locations
   * stale: false
   */
  stale?: boolean;
  /**
   * The distance in meters that the device must move before a new location update is triggered.
   * This is used to filter out small movements and reduce the number of updates.
   *
   * @since 7.0.9
   * @default 0
   * @example
   * // Update every 10 meters
   * distanceFilter: 10
   *
   * // Update on any movement
   * distanceFilter: 0
   */
  distanceFilter?: number;
}

/**
 * Represents a geographical location with various attributes.
 * Contains all the standard location properties returned by GPS/network providers.
 *
 * @since 7.0.0
 */
export interface Location {
  /**
   * Latitude in degrees.
   * Range: -90.0 to +90.0
   *
   * @since 7.0.0
   * @example 40.7128
   */
  latitude: number;
  /**
   * Longitude in degrees.
   * Range: -180.0 to +180.0
   *
   * @since 7.0.0
   * @example -74.0060
   */
  longitude: number;
  /**
   * Radius of horizontal uncertainty in metres, with 68% confidence.
   * Lower values indicate more accurate location.
   *
   * @since 7.0.0
   * @example 5.0
   */
  accuracy: number;
  /**
   * Metres above sea level (or null if not available).
   *
   * @since 7.0.0
   * @example 10.5
   */
  altitude: number | null;
  /**
   * Vertical uncertainty in metres, with 68% confidence (or null if not available).
   *
   * @since 7.0.0
   * @example 3.0
   */
  altitudeAccuracy: number | null;
  /**
   * `true` if the location was simulated by software, rather than GPS.
   * Useful for detecting mock locations in development or testing.
   *
   * @since 7.0.0
   * @example false
   */
  simulated: boolean;
  /**
   * Deviation from true north in degrees (or null if not available).
   * Range: 0.0 to 360.0
   *
   * @since 7.0.0
   * @example 45.5
   */
  bearing: number | null;
  /**
   * Speed in metres per second (or null if not available).
   *
   * @since 7.0.0
   * @example 2.5
   */
  speed: number | null;
  /**
   * Time the location was produced, in milliseconds since the unix epoch.
   * Use this to check if a location is stale when using stale: true.
   *
   * @since 7.0.0
   * @example 1640995200000
   */
  time: number | null;
}

/**
 * Error object that may be passed to the location start callback.
 * Extends the standard Error with optional error codes.
 *
 * @since 7.0.0
 */
export interface CallbackError extends Error {
  /**
   * Optional error code for more specific error handling.
   *
   * @since 7.0.0
   * @example "PERMISSION_DENIED"
   */
  code?: string;
}

export interface SetPlannedRouteOptions {
  /**
   * The name of the sound file to play.
   * Must be a valid sound relative path in the app's public folder to work for both web and native platforms.
   * There's no need to include the public folder in the path.
   * @since 7.0.10
   * @example "notification.mp3"
   * */
  soundFile: string;
  /**
   * The planned route as an array of longitude and latitude pairs.
   * Each pair represents a point on the route.
   * This is used to define a route that the user can follow.
   * The route is used to play a sound when the user deviates from it.
   * @since 7.0.11
   * @example [[-74.0060, 40.7128], [-118.2437, 34.0522]]
   */
  route: [number, number][];

  /**
   * The distance in meters that the user must deviate from the planned route to trigger the sound.
   * This is used to determine how far off the route the user can be before the sound is played.
   * If not specified, a default value of 50 meters is used.
   * @since 7.0.11
   * @default 50
   * @example 50
   */
  distance: number;
}

/**
 * Main plugin interface for background geolocation functionality.
 * Provides methods to manage location updates and access device settings.
 *
 * @since 7.0.0
 */
export interface BackgroundGeolocationPlugin {
  /**
   * To start listening for changes in the device's location, call this method.
   * A Promise is returned to indicate that it finished the call. The callback will be called every time a new location
   * is available, or if there was an error when calling this method. Don't rely on promise rejection for this.
   *
   * @param options The configuration options
   * @param callback The callback function invoked when a new location is available or an error occurs
   * @returns A promise that resolves when the method is successfully called
   *
   * @since 7.0.9
   * @example
   * await BackgroundGeolocation.start(
   *   {
   *     backgroundMessage: "App is using your location in the background",
   *     backgroundTitle: "Location Service",
   *     requestPermissions: true,
   *     stale: false,
   *     distanceFilter: 10
   *   },
   *   (location, error) => {
   *     if (error) {
   *       console.error('Location error:', error);
   *       return;
   *     }
   *     if (location) {
   *       console.log('New location:', location.latitude, location.longitude);
   *     }
   *   }
   * );
   */
  start(options: StartOptions, callback: (position?: Location, error?: CallbackError) => void): Promise<void>;

  /**
   * Stops location updates.
   *
   * @returns A promise that resolves when the plugin stops successfully removed
   *
   * @since 7.0.9
   * @example
   * await BackgroundGeolocation.stop();
   */
  stop(): Promise<void>;

  /**
   * Opens the device's location settings page.
   * Useful for directing users to enable location services or adjust permissions.
   *
   * @returns A promise that resolves when the settings page is opened
   *
   * @since 7.0.0
   * @example
   * // Direct user to location settings
   * await BackgroundGeolocation.openSettings();
   */
  openSettings(): Promise<void>;

  /**
   * Plays a sound file when the user deviates from the planned route.
   * This should be used to play a sound (in the background too, only for native).
   *
   * @param options The options for setting the planned route and sound file
   * @returns A promise that resolves when the route is set successfully
   *
   * @since 7.0.11
   * @example
   * await BackgroundGeolocation.setPlannedRoute({
   *   soundFile: "notification.mp3",
   *   route: [[-74.0060, 40.7128], [-118.2437, 34.0522]]
   * });
   */
  setPlannedRoute(options: SetPlannedRouteOptions): Promise<void>;
}
