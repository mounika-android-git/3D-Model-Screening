# 3D Model Viewer — Android Assessment

## Overview
An Android application developed in Kotlin for the Senior Android Developer assessment. The app demonstrates displaying and interacting with multiple 3D models.

## Features

* Display multiple 3D models simultaneously.
* Move individual models independently.
* Resize models independently.
* Interact with the available 3D models (rotate / zoom in interaction mode).

## Tech Stack

* Language: Kotlin
* Minimum SDK: Android 7.0 (API 24)
* UI: Jetpack Compose
* 3D Library: Filament + SceneView (Filament's Gltfio for GLB loading, SceneView for Compose integration)
* Build System: Gradle

## Project Structure
The Android application source code is located in the `app/` directory.

## How to Build and Run

1. Clone this repository.
2. Open the project root folder in Android Studio.
3. Allow Gradle sync to finish.
4. Connect an Android device or start an emulator (minimum Android 7.0 / API 24).
5. Run the app.

## Demo
A demo video is provided separately through the assessment submission form.

## Known Limitations

* **The very first model you load after opening the app takes a few seconds longer than usual.** This is a one-time startup cost: the first time a model is loaded, the app needs to set up the 3D rendering engine itself in addition to loading the model. Once that setup is done, every model loaded after that — in the same app session — loads quickly.

## Author
Submitted as part of the Senior Android Developer screening assessment.
