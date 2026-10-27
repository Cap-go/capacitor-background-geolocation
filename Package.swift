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
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "CapgoBackgroundGeolocationPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
                // "CapgoBackgroundGeolocationLocationLogSqlite",
            ],
            path: "ios/Sources/CapgoBackgroundGeolocationPlugin",
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),
            ]),
        // .target(
        //     name: "CapgoBackgroundGeolocationLocationLogSqlite",
        //     dependencies: ["CapgoBackgroundGeolocationPlugin"],
        //     path: "ios/SqliteLocationLog",
        //     swiftSettings: [.define("BGGEO_LOCATION_LOG_SQLITE")],
        //     linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(
            name: "CapgoBackgroundGeolocationPluginTests",
            dependencies: ["CapgoBackgroundGeolocationPlugin"],
            path: "ios/Tests/CapgoBackgroundGeolocationPluginTests",
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),
            ])
    ]
)
