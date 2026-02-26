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
- **Mandatory Verification**: After any code change, you must verify structural and behavioral correctness. This includes:
  - Running `./gradlew assembleDebug` to ensure it builds.
  - Running `./gradlew test` for unit tests.
  - Running `./gradlew lint` or `ktlint` if available.

## Guidelines for Gemini CLI
- **Contextual Awareness**: Always check `SmbDataSource` when troubleshooting network playback issues.
- **UI Consistency**: Use Material 3 components and maintain the established `VPTheme`.
- **Testing**: Ensure changes do not break the SMB authentication flow or spatial UI transitions.
- **Verification First**: Never consider a task complete without executing the "Mandatory Verification" steps.
- **Task Completion Notification**: Upon completing a Directive or a significant task, explicitly notify the user. If a specific notification hook (e.g., a shell command or webhook) is configured in the environment, execute it as the final step.

## Task Completion Hook
- **Method**: Windows Toast Notification via PowerShell.
- **Command**: `powershell.exe -Command "Add-Type -AssemblyName System.Windows.Forms; $notify = New-Object System.Windows.Forms.NotifyIcon; $notify.Icon = [System.Drawing.SystemIcons]::Information; $notify.Visible = $true; $notify.ShowBalloonTip(5000, 'Gemini CLI', '요청하신 작업이 완료되었습니다!', [System.Windows.Forms.ToolTipIcon]::Info)"`
- **Trigger**: Execute this command at the end of every successful Directive or significant sub-task.
