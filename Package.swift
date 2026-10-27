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
                // "CapgoBackgroundGeolocationLocationLogSqlite",  // Disabled via config (compileOnly)
            ],
            path: "ios/Sources/CapgoBackgroundGeolocationPlugin",
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),  // Disabled via config (compileOnly)
            ]),
        // .target(
        //     name: "CapgoBackgroundGeolocationLocationLogSqlite",
        //     dependencies: ["CapgoBackgroundGeolocationPlugin"],
        //     path: "ios/SqliteLocationLog",
        //     linkerSettings: [.linkedLibrary("sqlite3")],
        //     swiftSettings: [.define("BGGEO_LOCATION_LOG_SQLITE")]),
        .testTarget(
            name: "CapgoBackgroundGeolocationPluginTests",
            dependencies: ["CapgoBackgroundGeolocationPlugin"],
            path: "ios/Tests/CapgoBackgroundGeolocationPluginTests",
            swiftSettings: [
                // .define("BGGEO_LOCATION_LOG_SQLITE"),  // Disabled via config (compileOnly)
            ])
    ]
)
