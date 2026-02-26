# GEMINI.md - VP (Video Player) Project

## Project Overview
**VP** is an advanced Android video player application built with Jetpack Compose and AndroidX Media3. It specializes in high-fidelity playback using custom decoder extensions and supports direct streaming from SMB network shares.

## Tech Stack
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose (Material 3)
- **Navigation:** Adaptive Navigation Suite
- **Media Engine:** AndroidX Media3 (ExoPlayer)
- **Network:** jcifs-ng (SMB)
- **XR/Spatial:** Android XR SceneCore (`androidx.xr.scenecore`)

## Architecture & Core Components
The project follows an MVVM-like pattern:
- **`MainActivity`**: Manages top-level navigation and switches between 2D and Spatial UI modes.
- **`SmbService`**: Provides network scanning and file system operations for SMB.
- **`SmbDataSource`**: A custom `androidx.media3.datasource.DataSource` that enables ExoPlayer to stream content directly from SMB URLs.
- **`VideoPlayer`**: A Compose component that encapsulates ExoPlayer, including configuration for custom decoders (AV1, FFMPEG, IAMF, MPEG-H).
- **`SmbViewModel`**: Holds the state for the SMB browser, including connection details and file listings.

## Key Directories
- `app/src/main/java/com/grepiu/vp/`: Core application logic.
- `app/libs/`: Native decoder AARs (`lib-decoder-av1-release.aar`, etc.).
- `app/src/main/res/`: UI resources, including spatial mode icons.

## Mandates & Constraints
- **ExoPlayer Extensions**: Custom decoders are provided as AARs. When updating media playback logic, ensure these extensions are correctly prioritized or utilized.
- **SMB Streaming**: `SmbDataSource` is optimized for network latency. Any changes to the I/O logic should be verified against high-bitrate streaming performance.
- **Spatial UI**: The app uses `LocalSpatialCapabilities` to detect XR environments. Maintain parity between `My2DContent` and `MySpatialContent`.
- **Min SDK 34**: The app targets modern Android features (Android 14+), including advanced spatial APIs.

## Guidelines for Gemini CLI
- **Contextual Awareness**: Always check `SmbDataSource` when troubleshooting network playback issues.
- **UI Consistency**: Use Material 3 components and maintain the established `VPTheme`.
- **Testing**: Ensure changes do not break the SMB authentication flow or spatial UI transitions.
