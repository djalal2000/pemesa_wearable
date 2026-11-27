# PEMESA

**PEMESA** (*Pénibilité Métier Santé*) is a research application for collecting and annotating wearable sensor data to study work-related strain among healthcare professionals.

## Project Structure

```text
PEMESA/
├── app/          # Android mobile application
├── wear/         # Wear OS application
└── README.md
```

## Table of Contents

- [Overview](#overview)
- [Installation](#installation)
  - [Preparing the Device](#preparing-the-device)
  - [Wireless Debugging](#wireless-debugging)
- [Features](#features)
  - [Mobile Application](#mobile-application)
  - [Wear OS Application](#wear-os-application)
    - [Motion Sensors](#motion-sensors)
    - [Environmental Sensors](#environmental-sensors)
    - [Physiological Sensors](#physiological-sensors)
- [Sampling Frequency](#sampling-frequency)
- [Data Transmission](#data-transmission)
- [Best Practices](#best-practices)
- [Support](#support)
- [License](#license)

## Overview

PEMESA supports the collection of inertial, physiological, and environmental data, real-time activity annotation, and subjective fatigue assessment.

- **Android Mobile App:** participant identification, smartwatch association, acquisition control, activity annotation, and configuration.
- **Wear OS App:** sensor acquisition, timestamping, buffering, and direct transmission to InfluxDB over HTTPS.

## Installation

### Preparing the Device

Requirements: an Android smartphone, a Wear OS smartwatch, and an InfluxDB server accessible over HTTPS.

To build from source, open the project in Android Studio with JDK 17 and Android SDK 36, or run:

```sh
./gradlew :app:assembleDebug :wear:assembleDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Install the mobile APK on the phone. To install the watch APK through ADB, enable developer options on the watch by tapping its build number seven times.

### Wireless Debugging

Connect the computer and smartwatch to the same trusted Wi-Fi network.

1. Enable **Wireless debugging** in the watch developer options.
2. Select **Pair device with pairing code** and note the IP address and pairing port.
3. Open a terminal with Android SDK platform-tools available and run:

```sh
adb pair <IP>:<PAIRING_PORT>
adb connect <IP>:<DEBUG_PORT>
adb devices
adb install -r <WATCH_APK_PATH>
```

The pairing port and debugging port may differ. Enter the temporary pairing code when prompted. Disable wireless debugging after installation.

Open PEMESA on both devices, grant the required sensor permissions on the watch, and associate it through **Settings > Smartwatch management**.

## Features

### Mobile Application

- Remote control of acquisition: Start, Pause, Resume, and Stop.
- Real-time activity annotation and fatigue assessment (0–5).
- Smartwatch association, sensor selection, and acquisition settings.
- InfluxDB configuration, connection monitoring, and technical diagnostics.
- English and French interfaces.

#### Main Interfaces

- **Home Screen:** enter the participant identifier and select the profession.
- **Control Screen:** control acquisition, annotate activities, and update fatigue ratings.
- **Settings:** manage watches, server credentials, sensors, acquisition, activity labels, and diagnostics.

### Wear OS Application

- Executes confirmed acquisition commands from the associated phone.
- Collects selected sensor signals and annotates inertial samples at acquisition time.
- Buffers pending measurements in RAM and retries transient transmission failures.
- Monitors device thermal status.

#### Motion Sensors

| Android sensor | InfluxDB fields |
| --- | --- |
| `TYPE_ACCELEROMETER` | `acceX`, `acceY`, `acceZ` |
| `TYPE_GYROSCOPE` | `gyroX`, `gyroY`, `gyroZ` |
| `TYPE_GRAVITY` | `gravityX`, `gravityY`, `gravityZ` |
| `TYPE_LINEAR_ACCELERATION` | `linAccX`, `linAccY`, `linAccZ` |
| `TYPE_ROTATION_VECTOR` | `rotX`, `rotY`, `rotZ`, `rotW` |
| `TYPE_MAGNETIC_FIELD` (optional) | `magX`, `magY`, `magZ` |
| `TYPE_STEP_COUNTER` | `steps` |

#### Environmental Sensors

| Android sensor | InfluxDB fields |
| --- | --- |
| `TYPE_LIGHT` | `lux` |
| `TYPE_PRESSURE` | `hPa` |

#### Physiological Sensors

| Android sensor | InfluxDB fields |
| --- | --- |
| `TYPE_HEART_RATE` | `bpm` |

Subjective fatigue is entered manually on the phone and recorded separately as `fatigue_level`.

## Sampling Frequency

- Inertial sampling targets: 25, 50, or 100 Hz (default: 50 Hz).
- Activity transmission intervals: 5, 10, or 20 seconds (default: 10 seconds).
- Heart rate, steps, light, and pressure are event-driven; heart rate and light are recorded on changes.
- Fatigue is recorded at session start and on changes when enabled.

Effective sampling rates depend on sensor availability, device capabilities, and operating conditions.

## Data Transmission

The watch sends measurements directly to InfluxDB over HTTPS.

### InfluxDB Configuration

In **Settings > InfluxDB**, enter the HTTPS server URL, organization, activity bucket, wearable bucket, and API write token. Save the configuration, confirm synchronization with the watch, and select **Test saved connection** before starting a recording.

| Default bucket | Measurements |
| --- | --- |
| `activity` | `inertial_data`, `activity` |
| `wearable` | `heart_rate`, `steps`, `light`, `pressure`, `fatigue_level` |

Inertial samples retain their acquisition timestamp, activity label, participant identifier, session identifier, watch identifier, and sequence number.

The active API token is encrypted using Android Keystore-backed encryption. No default server credentials are embedded in the current application source. Use a dedicated write token and a separate read token for analysis. Revoke any previously committed credentials before distributing the repository or APKs.

### Connection Interruptions

The watch can continue an active recording if the phone disconnects while the watch application remains running. The last confirmed activity label is retained until a new annotation is received.

During Internet outages, pending measurements are buffered in RAM. The activity queue holds up to 32 waiting batches, and the wearable queue is limited to 2,048 pending records. Each in-flight batch has a five-minute retry budget. Data may be lost if buffers fill, retries fail, or the watch process stops.

Stop confirms acquisition has ended and partial batches have been queued, not that all measurements have reached InfluxDB. No measurement data are permanently stored by the application on the watch.

## Best Practices

- Keep PEMESA open on the watch during acquisition.
- Check sensor permissions, battery level, connectivity, and watch configuration before each session.
- Position the watch correctly on the participant's wrist.
- Validate sampling rates, annotation timing, transmission integrity, battery endurance, and thermal behavior on the target devices.
- Record the software revision and acquisition configuration for reproducibility.
- Keep API tokens and participant identifiers out of public materials.

Run the available JVM unit tests for both modules:

```sh
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest
```

## Support

Development team: djalal.bouchekif@irit.fr

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
