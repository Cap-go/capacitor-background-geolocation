# Background Geolocation
<a href="https://capgo.app/"><img src="https://capgo.app/readme-banner.svg?repo=Cap-go/capacitor-background-geolocation" alt="Capgo - Instant updates for Capacitor" /></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin_background_geolocation"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin_background_geolocation"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

A Capacitor plugin for accurate background location tracking and native geofencing on iOS and Android.
Use it to stream precise location updates, monitor circular geofence regions, react to enter/exit events in JavaScript, and POST geofence transitions natively while the WebView is suspended.

## Features

- Accurate foreground and background geolocation without a paid license.
- Native geofencing on iOS and Android for circular regions.
- Enter and exit events through `geofenceTransition` while the app is alive.
- Native webhook delivery for geofence transitions when the WebView is suspended.
- A web fallback for development and browser-based testing.

## This plugin's history

Interestingly enough, this plugin has a lot of history. The initial solution from [Transistorsoft](https://github.com/transistorsoft) was a great piece of software, and I ([HarelM](https://github.com/HarelM)) encourage using it if it fits your needs.  
I tried it and understood that it prioritizes battery life over accuracy, which wasn't the right fit for my hiking app.  
There was a very good fork maintained by **mauron85** specifically for that use case, and I was happy to help maintain it.  
But at some point, **mauron85** stopped responding to messages on GitHub, and no one could continue maintaining it.  
I hope mauron85 is safe and sound somewhere.  

So I created a fork and started maintaining it [here](https://github.com/HaylLtd/cordova-background-geolocation-plugin).  
It served me well for over half a decade, but I felt it was hard to maintain due to all its history, features, and bug fixes.  
I also felt like there was a barrier to introducing new features because of its complexity.

So I started exploring what it would take to reduce that complexity—at the same time, I was envious of how small [`@capacitor-community/background-geolocation`](https://github.com/capacitor-community/background-geolocation) is.  
I took the best of both worlds: tried to reduce the codebase in the original Cordova plugin and add some robustness to the Capacitor plugin.  

That's how I ended up maintaining this one.  
I hope you'll enjoy it!


## Plugin comparison

A short comparison between the three main background-geolocation plugins commonly used in Capacitor apps.

| Plugin | Accuracy | Background | Geofencing | Native transition POST | Pricing |
|--------|----------|------------|------------|------------------------|---------|
| `@capacitor-community/background-geolocation` (Community) | Not accurate | Yes | No | No | Free |
| `@capgo/background-geolocation` (this plugin) | Accurate | Yes | iOS and Android | Yes, for geofence transitions | Free |
| Transistorsoft (original) | Accurate | Yes | Yes | Yes, built-in HTTP uploader | Paid |

Notes:
- The Community plugin is lightweight and continues to work in the background, but it is known to be less accurate than the options below.
- This Cap-go plugin aims to provide accurate location fixes, reliable background operation, and native geofence enter/exit handling without requiring a paid license.
- Native geofence POST delivery is useful when iOS or Android wakes native code for a region transition but the Capacitor WebView is not running.
- Transistorsoft's plugin is a mature, accurate solution that also includes a broader HTTP uploader. It is a commercial product and requires a paid license for full use.


## Usage

```javascript
import { BackgroundGeolocation } from "@capgo/background-geolocation";

BackgroundGeolocation.start(
    {
        backgroundMessage: "Cancel to prevent battery drain.",
        backgroundTitle: "Tracking You.",
        requestPermissions: true,
        stale: false,
        distanceFilter: 50
    },
    (location, error) => {
        if (error) {
            if (error.code === "NOT_AUTHORIZED") {
                if (window.confirm(
                    "This app needs your location, " +
                    "but does not have permission.\n\n" +
                    "Open settings now?"
                )) {
                    // It can be useful to direct the user to their device's
                    // settings when location permissions have been denied. The
                    // plugin provides the 'openSettings' method to do exactly
                    // this.
                    BackgroundGeolocation.openSettings();
                }
            }
            return console.error(error);
        }
        return console.log(location);
    }
).then(() => {
    // When location updates are no longer needed, the plugin should be stopped by calling
    BackgroundGeolocation.stop();
});

// Set a planned route to get a notification sound when a new location arrives and it's not on the route:
        
BackgroundGeolocation.setPlannedRoute({soundFile: "assets/myFile.mp3", route: [[1,2], [3,4]], distance: 30 });
```

## Native geofencing

Use native geofencing when you need lightweight location boundaries such as stores, job sites, delivery zones, campuses, or check-in areas. The plugin monitors geofences natively and emits JavaScript events while the app is active. Android background delivery is optional and only requested when you opt in.

```javascript
import { BackgroundGeolocation } from "@capgo/background-geolocation";

// Geofencing can notify JavaScript while the app is alive.
await BackgroundGeolocation.setupGeofencing({
    notifyOnEntry: true,
    notifyOnExit: true,
    payload: { userId: "123" }
});

await BackgroundGeolocation.addGeofence({
    identifier: "office",
    latitude: 37.33182,
    longitude: -122.03118,
    radius: 150
});

const handle = await BackgroundGeolocation.addListener(
    "geofenceTransition",
    (event) => console.log(event.identifier, event.transition)
);

await BackgroundGeolocation.removeGeofence({ identifier: "office" });
handle.remove();
```

### Android background geofence permission

The plugin does not add `ACCESS_BACKGROUND_LOCATION` by default and does not request it unless you explicitly opt in. Apps that only use foreground location can omit this permission.

Opt in only when you need Android geofence transitions while the app is in the background:

```xml
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
```

```javascript
await BackgroundGeolocation.setupGeofencing({
    url: "https://api.example.com/geofences",
    backgroundLocation: true
});
```

```javascript
// If you just want the current location, try something like this. The longer
// the timeout, the more accurate the guess will be. I wouldn't go below about 100ms.
function guessLocation(callback, timeout) {
    let last_location;
    BackgroundGeolocation.start(
        {
            requestPermissions: false,
            stale: true
        },
        (location) => {
            last_location = location || undefined;
        }
    ).then(() => {
        setTimeout(() => {
            callback(last_location);
            BackgroundGeolocation.stop();
        }, timeout);
    });
}
```

## Documentation

The most complete doc is available here: https://capgo.app/docs/plugins/background-geolocation/

## Compatibility

| Plugin version | Capacitor compatibility | Maintained |
| -------------- | ----------------------- | ---------- |
| v8.\*.\*       | v8.\*.\*                | ✅          |
| v7.\*.\*       | v7.\*.\*                | On demand   |
| v6.\*.\*       | v6.\*.\*                | ❌          |
| v5.\*.\*       | v5.\*.\*                | ❌          |

> **Note:** The major version of this plugin follows the major version of Capacitor. Use the version that matches your Capacitor installation (e.g., plugin v8 for Capacitor 8). Only the latest major version is actively maintained.

## Installation

You can use our AI-Assisted Setup to install the plugin. Add the Capgo skills to your AI tool using the following command:

```bash
npx skills add https://github.com/cap-go/capacitor-skills --skill capacitor-plugins
```

Then use the following prompt:

```text
Use the `capacitor-plugins` skill from `cap-go/capacitor-skills` to install the `@capgo/background-geolocation` plugin in my project.
```

If you prefer Manual Setup, install the plugin by running the following commands and follow the platform-specific instructions below:

This plugin supports Capacitor v8:

| Capacitor  | Plugin |
|------------|--------|
| v8         | v8     |

```sh
bun add @capgo/background-geolocation
bunx cap update
```

### iOS
Add the following keys to `Info.plist.`:

```xml
<dict>
  ...
  <key>NSLocationWhenInUseUsageDescription</key>
  <string>We need to track your location</string>
  <key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
  <string>We need to track your location while your device is locked.</string>
  <key>UIBackgroundModes</key>
  <array>
    <string>location</string>
  </array>
  ...
</dict>
```

### Android

Set the the `android.useLegacyBridge` option to `true` in your Capacitor configuration. This prevents location updates halting after 5 minutes in the background. See https://capacitorjs.com/docs/config and https://github.com/capacitor-community/background-geolocation/issues/89.

On Android 13+, the app needs the `POST_NOTIFICATIONS` runtime permission to show the persistent notification informing the user that their location is being used in the background. This runtime permission is requested after the location permission is granted.

For background geofencing on Android 10+, the app also needs `ACCESS_BACKGROUND_LOCATION` and `backgroundLocation: true` in `setupGeofencing()`. Android may require the user to grant this from system settings after foreground location is granted; use `openSettings()` if the permission remains denied. Leave `backgroundLocation` unset or `false` if your app does not have Google Play approval for Android background location.

If your app forwards location updates to a server in real time, be aware that after 5 minutes in the background Android will throttle HTTP requests initiated from the WebView. The solution is to use a native HTTP plugin such as [CapacitorHttp](https://capacitorjs.com/docs/apis/http). See https://github.com/capacitor-community/background-geolocation/issues/14.

Configuration specific to Android can be made in `strings.xml`:
```xml
<resources>
    <!--
        The channel name for the background notification. This will be visible
        when the user presses & holds the notification. It defaults to
        "Background Tracking".
    -->
    <string name="capacitor_background_geolocation_notification_channel_name">
        Background Tracking
    </string>

    <!--
        The icon to use for the background notification. Note the absence of a
        leading "@". It defaults to "mipmap/ic_launcher", the app's launch icon.

        If a raster image is used to generate the icon (as opposed to a vector
        image), it must have a transparent background. To make sure your image
        is compatible, select "Notification Icons" as the Icon Type when
        creating the image asset in Android Studio.

        An incompatible image asset will cause the notification to misbehave in
        a few telling ways, even if the icon appears correctly:

          - The notification may be dismissable by the user when it should not
            be.
          - Tapping the notification may open the settings, not the app.
          - The notification text may be incorrect.
    -->
    <string name="capacitor_background_geolocation_notification_icon">
        drawable/ic_tracking
    </string>

    <!--
        The color of the notification as a string parseable by
        android.graphics.Color.parseColor. Optional.
    -->
    <string name="capacitor_background_geolocation_notification_color">
        yellow
    </string>
</resources>

```

## API

<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`openSettings()`](#opensettings)
* [`setPlannedRoute(...)`](#setplannedroute)
* [`setupGeofencing(...)`](#setupgeofencing)
* [`addGeofence(...)`](#addgeofence)
* [`removeGeofence(...)`](#removegeofence)
* [`removeAllGeofences()`](#removeallgeofences)
* [`getMonitoredGeofences()`](#getmonitoredgeofences)
* [`addListener('geofenceTransition', ...)`](#addlistenergeofencetransition-)
* [`addListener('geofenceError', ...)`](#addlistenergeofenceerror-)
* [`getPluginVersion()`](#getpluginversion)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Main plugin interface for background geolocation functionality.
Provides methods to manage location updates and access device settings.

### start(...)

```typescript
start(options: StartOptions, callback: (position?: Location | undefined, error?: CallbackError | undefined) => void) => Promise<void>
```

To start listening for changes in the device's location, call this method.
A Promise is returned to indicate that it finished the call. The callback will be called every time a new location
is available, or if there was an error when calling this method. Don't rely on promise rejection for this.

| Param          | Type                                                                                                                      | Description                                                                       |
| -------------- | ------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| **`options`**  | <code><a href="#startoptions">StartOptions</a></code>                                                                     | The configuration options                                                         |
| **`callback`** | <code>(position?: <a href="#location">Location</a>, error?: <a href="#callbackerror">CallbackError</a>) =&gt; void</code> | The callback function invoked when a new location is available or an error occurs |

**Since:** 7.0.9

--------------------


### stop()

```typescript
stop() => Promise<void>
```

Stops location updates.

**Since:** 7.0.9

--------------------


### openSettings()

```typescript
openSettings() => Promise<void>
```

Opens the device's location settings page.
Useful for directing users to enable location services or adjust permissions.

**Since:** 7.0.0

--------------------


### setPlannedRoute(...)

```typescript
setPlannedRoute(options: SetPlannedRouteOptions) => Promise<void>
```

Plays a sound file when the user deviates from the planned route.
This should be used to play a sound (in the background too, only for native).

| Param         | Type                                                                      | Description                                              |
| ------------- | ------------------------------------------------------------------------- | -------------------------------------------------------- |
| **`options`** | <code><a href="#setplannedrouteoptions">SetPlannedRouteOptions</a></code> | The options for setting the planned route and sound file |

**Since:** 7.0.11

--------------------


### setupGeofencing(...)

```typescript
setupGeofencing(options: GeofenceSetupOptions) => Promise<void>
```

Configures native geofence transition handling.

Call this before adding geofences when you need default entry/exit settings
or native background POSTs. Android background POSTs require
`backgroundLocation: true`.

| Param         | Type                                                                  | Description                        |
| ------------- | --------------------------------------------------------------------- | ---------------------------------- |
| **`options`** | <code><a href="#geofencesetupoptions">GeofenceSetupOptions</a></code> | The geofence configuration options |

**Since:** 8.0.30

--------------------


### addGeofence(...)

```typescript
addGeofence(options: AddGeofenceOptions) => Promise<void>
```

Starts monitoring a circular native geofence.

| Param         | Type                                                              | Description                 |
| ------------- | ----------------------------------------------------------------- | --------------------------- |
| **`options`** | <code><a href="#addgeofenceoptions">AddGeofenceOptions</a></code> | The geofence region options |

**Since:** 8.0.30

--------------------


### removeGeofence(...)

```typescript
removeGeofence(options: RemoveGeofenceOptions) => Promise<void>
```

Stops monitoring one geofence.

| Param         | Type                                                                    | Description             |
| ------------- | ----------------------------------------------------------------------- | ----------------------- |
| **`options`** | <code><a href="#removegeofenceoptions">RemoveGeofenceOptions</a></code> | The geofence identifier |

**Since:** 8.0.30

--------------------


### removeAllGeofences()

```typescript
removeAllGeofences() => Promise<void>
```

Stops monitoring every geofence registered by this plugin.

**Since:** 8.0.30

--------------------


### getMonitoredGeofences()

```typescript
getMonitoredGeofences() => Promise<MonitoredGeofencesResult>
```

Lists the geofence identifiers currently monitored by this plugin.

**Returns:** <code>Promise&lt;<a href="#monitoredgeofencesresult">MonitoredGeofencesResult</a>&gt;</code>

**Since:** 8.0.30

--------------------


### addListener('geofenceTransition', ...)

```typescript
addListener(eventName: 'geofenceTransition', listenerFunc: (event: GeofenceTransitionEvent) => void) => Promise<PluginListenerHandle>
```

Listens for geofence enter/exit transitions while the WebView is alive.

Native `url` delivery configured through `setupGeofencing` is used for
background-safe delivery.

| Param              | Type                                                                                            |
| ------------------ | ----------------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'geofenceTransition'</code>                                                               |
| **`listenerFunc`** | <code>(event: <a href="#geofencetransitionevent">GeofenceTransitionEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.30

--------------------


### addListener('geofenceError', ...)

```typescript
addListener(eventName: 'geofenceError', listenerFunc: (event: GeofenceErrorEvent) => void) => Promise<PluginListenerHandle>
```

Listens for native geofence monitoring errors while the WebView is alive.

| Param              | Type                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------- |
| **`eventName`**    | <code>'geofenceError'</code>                                                          |
| **`listenerFunc`** | <code>(event: <a href="#geofenceerrorevent">GeofenceErrorEvent</a>) =&gt; void</code> |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.30

--------------------


### getPluginVersion()

```typescript
getPluginVersion() => Promise<{ version: string; }>
```

Get the native Capacitor plugin version

**Returns:** <code>Promise&lt;{ version: string; }&gt;</code>

--------------------


### Interfaces


#### StartOptions

The options for configuring for location updates.

| Prop                     | Type                 | Description                                                                                                                                                                                                                                                                                                                                                                                                          | Default                            | Since |
| ------------------------ | -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- | ----- |
| **`backgroundMessage`**  | <code>string</code>  | If the "backgroundMessage" option is defined, the plugin will provide location updates whether the app is in the background or the foreground. If it is not defined, location updates are only guaranteed in the foreground. This is true on both platforms. On Android, a notification must be shown to continue receiving location updates in the background. This option specifies the text of that notification. |                                    | 7.0.9 |
| **`backgroundTitle`**    | <code>string</code>  | The title of the notification mentioned above.                                                                                                                                                                                                                                                                                                                                                                       | <code>"Using your location"</code> | 7.0.9 |
| **`requestPermissions`** | <code>boolean</code> | Whether permissions should be requested from the user automatically, if they are not already granted.                                                                                                                                                                                                                                                                                                                | <code>true</code>                  | 7.0.9 |
| **`stale`**              | <code>boolean</code> | If "true", stale locations may be delivered while the device obtains a GPS fix. You are responsible for checking the "time" property. If "false", locations are guaranteed to be up to date.                                                                                                                                                                                                                         | <code>false</code>                 | 7.0.9 |
| **`distanceFilter`**     | <code>number</code>  | The distance in meters that the device must move before a new location update is triggered. This is used to filter out small movements and reduce the number of updates.                                                                                                                                                                                                                                             | <code>0</code>                     | 7.0.9 |


#### Location

Represents a geographical location with various attributes.
Contains all the standard location properties returned by GPS/network providers.

| Prop                   | Type                        | Description                                                                                                                            | Since |
| ---------------------- | --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | ----- |
| **`latitude`**         | <code>number</code>         | Latitude in degrees. Range: -90.0 to +90.0                                                                                             | 7.0.0 |
| **`longitude`**        | <code>number</code>         | Longitude in degrees. Range: -180.0 to +180.0                                                                                          | 7.0.0 |
| **`accuracy`**         | <code>number</code>         | Radius of horizontal uncertainty in metres, with 68% confidence. Lower values indicate more accurate location.                         | 7.0.0 |
| **`altitude`**         | <code>number \| null</code> | Metres above sea level (or null if not available).                                                                                     | 7.0.0 |
| **`altitudeAccuracy`** | <code>number \| null</code> | Vertical uncertainty in metres, with 68% confidence (or null if not available).                                                        | 7.0.0 |
| **`simulated`**        | <code>boolean</code>        | `true` if the location was simulated by software, rather than GPS. Useful for detecting mock locations in development or testing.      | 7.0.0 |
| **`bearing`**          | <code>number \| null</code> | Deviation from true north in degrees (or null if not available). Range: 0.0 to 360.0                                                   | 7.0.0 |
| **`speed`**            | <code>number \| null</code> | Speed in metres per second (or null if not available).                                                                                 | 7.0.0 |
| **`time`**             | <code>number \| null</code> | Time the location was produced, in milliseconds since the unix epoch. Use this to check if a location is stale when using stale: true. | 7.0.0 |


#### CallbackError

Error object that may be passed to the location start callback.
Extends the standard Error with optional error codes.

| Prop       | Type                | Description                                           | Since |
| ---------- | ------------------- | ----------------------------------------------------- | ----- |
| **`code`** | <code>string</code> | Optional error code for more specific error handling. | 7.0.0 |


#### SetPlannedRouteOptions

| Prop            | Type                            | Description                                                                                                                                                                                                                                        | Default         | Since  |
| --------------- | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- | ------ |
| **`soundFile`** | <code>string</code>             | The name of the sound file to play. Must be a valid sound relative path in the app's public folder to work for both web and native platforms. There's no need to include the public folder in the path.                                            |                 | 7.0.10 |
| **`route`**     | <code>[number, number][]</code> | The planned route as an array of longitude and latitude pairs. Each pair represents a point on the route. This is used to define a route that the user can follow. The route is used to play a sound when the user deviates from it.               |                 | 7.0.11 |
| **`distance`**  | <code>number</code>             | The distance in meters that the user must deviate from the planned route to trigger the sound. This is used to determine how far off the route the user can be before the sound is played. If not specified, a default value of 50 meters is used. | <code>50</code> | 7.0.11 |


#### GeofenceSetupOptions

Options for configuring native geofence transition handling.

When `url` is provided, native code can send a JSON `POST` whenever a
monitored region is entered or exited. Android background POST delivery
requires `backgroundLocation: true`.

| Prop                     | Type                                                             | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | Default            | Since  |
| ------------------------ | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------ | ------ |
| **`url`**                | <code>string</code>                                              | Endpoint that receives geofence transition payloads. On Android, native background POST delivery requires `backgroundLocation: true`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |                    | 8.0.30 |
| **`notifyOnEntry`**      | <code>boolean</code>                                             | Whether entry transitions should be monitored.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 | <code>true</code>  | 8.0.30 |
| **`notifyOnExit`**       | <code>boolean</code>                                             | Whether exit transitions should be monitored.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | <code>true</code>  | 8.0.30 |
| **`payload`**            | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | Base JSON payload merged into every native transition POST and listener event.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |                    | 8.0.30 |
| **`requestPermissions`** | <code>boolean</code>                                             | Whether the plugin should request the native location permission needed for geofencing. iOS geofencing needs Always location authorization. Android geofencing requests foreground location by default. Android background location is only requested when `backgroundLocation` is enabled.                                                                                                                                                                                                                                                                                                                                    | <code>true</code>  | 8.0.30 |
| **`backgroundLocation`** | <code>boolean</code>                                             | Whether Android geofencing should opt into background location permission. The plugin does not add `ACCESS_BACKGROUND_LOCATION` to your app manifest. Leave this disabled if your app does not have Google Play approval for Android background location. Enable it only after adding `ACCESS_BACKGROUND_LOCATION` to your app manifest and when you need Android geofence transitions while the app is in the background. This option only affects Android. Android versions below 10 do not request an extra background-location runtime permission, but the option still gates native Android background geofence delivery. | <code>false</code> | 8.0.34 |


#### AddGeofenceOptions

A circular geofence region.

| Prop                | Type                                                             | Description                                              | Default         | Since  |
| ------------------- | ---------------------------------------------------------------- | -------------------------------------------------------- | --------------- | ------ |
| **`latitude`**      | <code>number</code>                                              | Latitude in degrees for the region center.               |                 | 8.0.30 |
| **`longitude`**     | <code>number</code>                                              | Longitude in degrees for the region center.              |                 | 8.0.30 |
| **`radius`**        | <code>number</code>                                              | Region radius in meters.                                 | <code>50</code> | 8.0.30 |
| **`identifier`**    | <code>string</code>                                              | Stable identifier for the geofence.                      |                 | 8.0.30 |
| **`notifyOnEntry`** | <code>boolean</code>                                             | Overrides the setup-level entry setting for this region. |                 | 8.0.30 |
| **`notifyOnExit`**  | <code>boolean</code>                                             | Overrides the setup-level exit setting for this region.  |                 | 8.0.30 |
| **`payload`**       | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | Region-specific payload merged over the setup payload.   |                 | 8.0.30 |


#### RemoveGeofenceOptions

Options for removing a monitored geofence.

| Prop             | Type                | Description                         | Since  |
| ---------------- | ------------------- | ----------------------------------- | ------ |
| **`identifier`** | <code>string</code> | Identifier passed to `addGeofence`. | 8.0.30 |


#### MonitoredGeofencesResult

Result returned when listing monitored geofences.

| Prop          | Type                  | Description                                                       | Since  |
| ------------- | --------------------- | ----------------------------------------------------------------- | ------ |
| **`regions`** | <code>string[]</code> | Identifiers for all geofences currently monitored by this plugin. | 8.0.30 |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### GeofenceTransitionEvent

Event emitted when a monitored geofence is entered or exited.

The same data is also sent to the configured `url`, when one is set.

| Prop             | Type                                                             | Description                                                           | Since  |
| ---------------- | ---------------------------------------------------------------- | --------------------------------------------------------------------- | ------ |
| **`identifier`** | <code>string</code>                                              | Identifier of the geofence that changed state.                        | 8.0.30 |
| **`transition`** | <code>'enter' \| 'exit'</code>                                   | Transition name.                                                      | 8.0.30 |
| **`enter`**      | <code>boolean</code>                                             | `true` for entry transitions, `false` for exit transitions.           | 8.0.30 |
| **`latitude`**   | <code>number</code>                                              | Latitude in degrees for the monitored region center, when available.  | 8.0.30 |
| **`longitude`**  | <code>number</code>                                              | Longitude in degrees for the monitored region center, when available. | 8.0.30 |
| **`radius`**     | <code>number</code>                                              | Region radius in meters, when available.                              | 8.0.30 |
| **`payload`**    | <code><a href="#record">Record</a>&lt;string, unknown&gt;</code> | Merged setup and region payload.                                      | 8.0.30 |


#### GeofenceErrorEvent

Event emitted when native geofence monitoring fails.

| Prop             | Type                | Description                                                          | Since  |
| ---------------- | ------------------- | -------------------------------------------------------------------- | ------ |
| **`identifier`** | <code>string</code> | Identifier of the geofence that failed, when native APIs provide it. | 8.0.30 |
| **`code`**       | <code>number</code> | Native platform error code.                                          | 8.0.30 |
| **`message`**    | <code>string</code> | Native platform error message.                                       | 8.0.30 |
| **`domain`**     | <code>string</code> | Native error domain, when available.                                 | 8.0.30 |


### Type Aliases


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>
