import XCTest
import CoreLocation
@testable import CapgoBackgroundGeolocationPlugin

class CapgoBackgroundGeolocationTests: XCTestCase {

    var plugin: BackgroundGeolocation!

    override func setUp() {
        super.setUp()
        plugin = BackgroundGeolocation()
    }

    override func tearDown() {
        plugin = nil
        super.tearDown()
    }

    // MARK: - Plugin Initialization Tests

    func testPluginInitialization() {
        XCTAssertNotNil(plugin)
        XCTAssertEqual(plugin.identifier, "BackgroundGeolocationPlugin")
        XCTAssertEqual(plugin.jsName, "BackgroundGeolocation")
    }

    func testPluginMethods() {
        let methodNames = plugin.pluginMethods.map { $0.name }
        XCTAssertTrue(methodNames.contains("start"))
        XCTAssertTrue(methodNames.contains("stop"))
        XCTAssertTrue(methodNames.contains("openSettings"))
        XCTAssertTrue(methodNames.contains("setPlannedRoute"))
        XCTAssertTrue(methodNames.contains("getPluginVersion"))
    }

    // MARK: - Format Location Tests

    func testFormatLocationBasic() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 100.0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            course: 45.0,
            speed: 10.0,
            timestamp: Date()
        )

        let formatted = formatLocation(location)

        XCTAssertEqual(formatted["latitude"] as? Double, 37.7749)
        XCTAssertEqual(formatted["longitude"] as? Double, -122.4194)
        XCTAssertEqual(formatted["accuracy"] as? Double, 5.0)
        XCTAssertEqual(formatted["altitude"] as? Double, 100.0)
        XCTAssertEqual(formatted["altitudeAccuracy"] as? Double, 3.0)
        XCTAssertEqual(formatted["speed"] as? Double, 10.0)
        XCTAssertEqual(formatted["bearing"] as? Double, 45.0)
        XCTAssertNotNil(formatted["time"])
    }

    func testFormatLocationNegativeSpeed() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 100.0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            course: -1.0,
            speed: -1.0,
            timestamp: Date()
        )

        let formatted = formatLocation(location)

        // Speed and bearing should be null when negative
        XCTAssertNil(formatted["speed"] as? Double)
        XCTAssertNil(formatted["bearing"] as? Double)
    }

    func testFormatLocationTimestamp() {
        let timestamp = Date(timeIntervalSince1970: 1609459200) // 2021-01-01 00:00:00 UTC
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: timestamp
        )

        let formatted = formatLocation(location)
        let timeMs = (formatted["time"] as? NSNumber)?.int64Value

        XCTAssertEqual(timeMs, 1609459200000)
    }

    // MARK: - Coordinate Tests

    func testCoordinateEdgeCases() {
        // North Pole
        let northPole = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 90, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedNorth = formatLocation(northPole)
        XCTAssertEqual(formattedNorth["latitude"] as? Double, 90)

        // South Pole
        let southPole = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: -90, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedSouth = formatLocation(southPole)
        XCTAssertEqual(formattedSouth["latitude"] as? Double, -90)

        // Date line
        let dateLine = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 180),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedDate = formatLocation(dateLine)
        XCTAssertEqual(formattedDate["longitude"] as? Double, 180)
    }

    // MARK: - Altitude Tests

    func testAltitudeVariations() {
        // Sea level
        let seaLevel = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedSea = formatLocation(seaLevel)
        XCTAssertEqual(formattedSea["altitude"] as? Double, 0)

        // Below sea level (Death Valley)
        let belowSea = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: -85.0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedBelow = formatLocation(belowSea)
        XCTAssertEqual(formattedBelow["altitude"] as? Double, -85.0)

        // Mount Everest height
        let mountain = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 8849.0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )
        let formattedMountain = formatLocation(mountain)
        XCTAssertEqual(formattedMountain["altitude"] as? Double, 8849.0)
    }

    // MARK: - Accuracy Tests

    func testAccuracyValues() {
        // High accuracy
        let highAccuracy = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            timestamp: Date()
        )
        let formattedHigh = formatLocation(highAccuracy)
        XCTAssertEqual(formattedHigh["accuracy"] as? Double, 5.0)
        XCTAssertEqual(formattedHigh["altitudeAccuracy"] as? Double, 3.0)

        // Low accuracy
        let lowAccuracy = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 500.0,
            verticalAccuracy: 300.0,
            timestamp: Date()
        )
        let formattedLow = formatLocation(lowAccuracy)
        XCTAssertEqual(formattedLow["accuracy"] as? Double, 500.0)
        XCTAssertEqual(formattedLow["altitudeAccuracy"] as? Double, 300.0)
    }

    // MARK: - Speed and Bearing Tests

    func testSpeedAndBearing() {
        // Stationary
        let stationary = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            course: 0,
            speed: 0,
            timestamp: Date()
        )
        let formattedStationary = formatLocation(stationary)
        XCTAssertEqual(formattedStationary["speed"] as? Double, 0)
        XCTAssertEqual(formattedStationary["bearing"] as? Double, 0)

        // Moving north
        let movingNorth = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            course: 0,
            speed: 20.0,
            timestamp: Date()
        )
        let formattedNorth = formatLocation(movingNorth)
        XCTAssertEqual(formattedNorth["speed"] as? Double, 20.0)
        XCTAssertEqual(formattedNorth["bearing"] as? Double, 0)

        // Moving east
        let movingEast = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            course: 90,
            speed: 15.0,
            timestamp: Date()
        )
        let formattedEast = formatLocation(movingEast)
        XCTAssertEqual(formattedEast["speed"] as? Double, 15.0)
        XCTAssertEqual(formattedEast["bearing"] as? Double, 90)
    }

    // MARK: - Performance Tests

    func testPerformanceFormatLocation() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 100.0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            course: 45.0,
            speed: 10.0,
            timestamp: Date()
        )

        self.measure {
            for _ in 0..<1000 {
                _ = formatLocation(location)
            }
        }
    }

    func testPerformanceMultipleLocationUpdates() {
        var locations: [CLLocation] = []
        for index in 0..<100 {
            let location = CLLocation(
                coordinate: CLLocationCoordinate2D(
                    latitude: 37.7749 + Double(index) * 0.001,
                    longitude: -122.4194 + Double(index) * 0.001
                ),
                altitude: 100.0,
                horizontalAccuracy: 5.0,
                verticalAccuracy: 3.0,
                course: 45.0,
                speed: 10.0,
                timestamp: Date()
            )
            locations.append(location)
        }

        self.measure {
            for location in locations {
                _ = formatLocation(location)
            }
        }
    }

    // MARK: - Data Type Tests

    func testFormattedLocationDataTypes() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 100.0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            course: 45.0,
            speed: 10.0,
            timestamp: Date()
        )

        let formatted = formatLocation(location)

        // Verify all values are of expected types
        XCTAssertTrue(formatted["latitude"] is Double)
        XCTAssertTrue(formatted["longitude"] is Double)
        XCTAssertTrue(formatted["accuracy"] is Double)
        XCTAssertTrue(formatted["altitude"] is Double)
        XCTAssertTrue(formatted["altitudeAccuracy"] is Double)
        XCTAssertTrue(formatted["speed"] is Double)
        XCTAssertTrue(formatted["bearing"] is Double)
        XCTAssertTrue(formatted["time"] is NSNumber)
        XCTAssertTrue(formatted["simulated"] is Bool)
    }

    // MARK: - Simulated Location Tests (iOS 15+)

    func testSimulatedLocationFlag() {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194),
            altitude: 100.0,
            horizontalAccuracy: 5.0,
            verticalAccuracy: 3.0,
            timestamp: Date()
        )

        let formatted = formatLocation(location)

        // The simulated flag should be present
        XCTAssertNotNil(formatted["simulated"])
        XCTAssertTrue(formatted["simulated"] is Bool)
    }

    // MARK: - Precision Tests

    func testHighPrecisionCoordinates() {
        // Test with high precision coordinates
        let preciseLat = 37.77492830283847
        let preciseLon = -122.41943529482831

        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: preciseLat, longitude: preciseLon),
            altitude: 0,
            horizontalAccuracy: 0,
            verticalAccuracy: 0,
            timestamp: Date()
        )

        let formatted = formatLocation(location)

        XCTAssertEqual(formatted["latitude"] as? Double, preciseLat)
        XCTAssertEqual(formatted["longitude"] as? Double, preciseLon)
    }
}
