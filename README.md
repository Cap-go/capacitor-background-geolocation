# Background Geolocation
 <a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/Cap-go/capgo/main/assets/capgo_banner.png' alt='Capgo - Instant updates for capacitor'/></a>

<div align="center">
  <h2><a href="https://capgo.app/?ref=plugin"> ➡️ Get Instant updates for your App with Capgo</a></h2>
  <h2><a href="https://capgo.app/consulting/?ref=plugin"> Missing a feature? We’ll build the plugin for you 💪</a></h2>
</div>

A Capacitor plugin that lets you receive geolocation updates even while the app is backgrounded.
It has a web API to facilitate for a similar usage, but background geolocation is not supported in a regular browser, only in an app environment.

## This plugin's history

Interestingly enough, this plugin has a lot of history. The initial solution from [Transistorsoft](https://github.com/transistorsoft) was a great piece of software, and I encourage using it if it fits your needs.  
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
    function callback(location, error) {
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

// If you just want the current location, try something like this. The longer
// the timeout, the more accurate the guess will be. I wouldn't go below about 100ms.
function guess_location(callback, timeout) {
    let last_location;
    BackgroundGeolocation.start(
        {
            requestPermissions: false,
            stale: true
        },
        (location) => {
            last_location = location || undefined;
        }
    ).then(() => 
        setTimeout(() => {
            callback(last_location);
            BackgroundGeolocation.stop();
        }, timeout);
    });
}
```

## Installation

This plugin supports Capacitor v7:

| Capacitor  | Plugin |
|------------|--------|
| v7         | v7     |

```sh
npm install @capgo/background-geolocation
npx cap update
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

If your app forwards location updates to a server in real time, be aware that after 5 minutes in the background Android will throttle HTTP requests initiated from the WebView. The solution is to use a native HTTP plugin such as [CapacitorHttp](https://capacitorjs.com/docs/apis/http). See https://github.com/capacitor-community/background-geolocation/issues/14.

Configration specific to Android can be made in `strings.xml`:
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


<docgen-index>

* [`start(...)`](#start)
* [`stop()`](#stop)
* [`openSettings()`](#opensettings)
* [`playSound(...)`](#playsound)
* [Interfaces](#interfaces)

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


### playSound(...)

```typescript
playSound(options: PlaySoundOptions) => Promise<void>
```

Plays a sound file.
This should be used to play a sound in the background too when listening for location updates.
The idea behind this is to allow the user to hear a sound when a new location is available or when going off track.
If you simply need to play a sound, you can use `@capgo/native-audio` plugin instead.

| Param         | Type                                                          | Description                       |
| ------------- | ------------------------------------------------------------- | --------------------------------- |
| **`options`** | <code><a href="#playsoundoptions">PlaySoundOptions</a></code> | The options for playing the sound |

**Since:** 7.0.10

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


#### PlaySoundOptions

| Prop            | Type                | Description                                                                                                                                                                                             | Since  |
| --------------- | ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| **`soundFile`** | <code>string</code> | The name of the sound file to play. Must be a valid sound relative path in the app's public folder to work for both web and native platforms. There's no need to include the public folder in the path. | 7.0.10 |

</docgen-api>
