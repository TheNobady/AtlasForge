# AtlasForge

AtlasForge is an Android application designed for mapping, navigation, and robust offline routing capabilities. Combining an intuitive interface with a powerful routing engine, AtlasForge provides seamless turn-by-turn directions without needing an active internet connection.

<div align="center">
  <img src="images/28a1d638-bec2-4e82-9ad0-ef8e3686ca15" width="30%" />
  <img src="images/8357b051-87e6-4443-9da8-4a3e014c95fb" width="30%" />
  <img src="images/bc303ec8-022b-4339-855b-24636fc4642e" width="30%" />
</div>

## How the Offline Routing Works
 
This was the most technically involved part of the project. At startup, GraphHopper reads the raw `.pbf` file from device storage and constructs an in-memory road network graph — nodes are intersections, edges are road segments weighted by distance and road type. When a destination is set, the engine runs a shortest-path algorithm over this graph and returns a full route with bearing changes at each waypoint, which the app converts into human-readable instructions.
 
The result: sub-second routing for city-scale maps entirely on the device.
 
```
[User sets destination]
        ↓
[GraphHopper reads local .osm.pbf]
        ↓
[Builds weighted road graph]
        ↓
[Runs shortest-path (car profile)]
        ↓
[Returns waypoints + turn instructions]
        ↓
[OSMDroid draws polyline on map]
```
 
---


## Tech Stack

- **Language**: Kotlin
- **Mapping Engine**: [osmdroid](https://github.com/osmdroid/osmdroid)
- **Offline Routing**: [GraphHopper Core](https://github.com/graphhopper/graphhopper)
- **Location Services**: Google Play Services Location
- **Network**: [OkHttp3](https://square.github.io/okhttp/)

## Getting Started

### Prerequisites
- Android Studio
- An Android device or emulator running API 24 or higher
- **Important**: To use the offline routing feature, you must have an OSM PBF file stored on your device at `[External Storage]/atlasforge/map.osm.pbf`. 

### Installation
1. Clone the repository to your local machine.
2. Open the project in Android Studio.
3. Sync the Gradle files.
4. Download a `.osm.pbf` map file for your region (e.g., from [Geofabrik](http://download.geofabrik.de/)) and place it in the required directory on your device.
5. Build and run the app. Note that the initial map parsing by GraphHopper may take a few moments depending on the map file size.

## License
This project is for demonstration and learning purposes.
