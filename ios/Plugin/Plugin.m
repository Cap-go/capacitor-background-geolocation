#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

CAP_PLUGIN(BackgroundGeolocation, "BackgroundGeolocation",
    CAP_PLUGIN_METHOD(start, CAPPluginReturnCallback);
    CAP_PLUGIN_METHOD(stop, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(openSettings, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(playSound, CAPPluginReturnPromise);
)
