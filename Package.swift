// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapgoBackgroundGeolocation",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapgoBackgroundGeolocation",
            targets: ["CapgoBackgroundGeolocationPlugin"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.1.0")
    ],
    targets: [
        .target(
            name: "CapgoBackgroundGeolocationPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/CapgoBackgroundGeolocationPlugin"),
        .testTarget(
            name: "CapgoBackgroundGeolocationPluginTests",
            dependencies: ["CapgoBackgroundGeolocationPlugin"],
            path: "ios/Tests/CapgoBackgroundGeolocationPluginTests")
    ]
)
