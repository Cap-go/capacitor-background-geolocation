// swift-tools-version: 5.9
import PackageDescription

// DO NOT MODIFY THIS FILE - managed by Capacitor CLI commands
let package = Package(
    name: "CapApp-SPM",
    platforms: [.iOS(.v15)],
    products: [
        .library(
            name: "CapApp-SPM",
            targets: ["CapApp-SPM"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.1"),
        .package(name: "CapgoBackgroundGeolocation", path: "../../../node_modules/.bun/@capgo+background-geolocation@file+../node_modules/@capgo/background-geolocation"),
        .package(name: "CapacitorLocalNotifications", path: "../../../node_modules/.bun/@capacitor+local-notifications@7.0.4+15e98482558ccfe6/node_modules/@capacitor/local-notifications")
    ],
    targets: [
        .target(
            name: "CapApp-SPM",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                .product(name: "CapgoBackgroundGeolocation", package: "CapgoBackgroundGeolocation"),
                .product(name: "CapacitorLocalNotifications", package: "CapacitorLocalNotifications")
            ]
        )
    ]
)
