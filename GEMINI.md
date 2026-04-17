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

## Library & Version Management Policy
- **Version Catalog Usage**: All dependencies must be managed via `gradle/libs.versions.toml`. Hardcoding versions in `build.gradle.kts` is strictly prohibited.
- **Stable Versions Preference**: Prefer stable versions for core libraries. Alpha/Beta versions are allowed only for cutting-edge features like Android XR.
- **Core Library Baseline (as of 2026-03-03)**:
  - **Kotlin**: 2.0.21+
  - **Android Gradle Plugin (AGP)**: 8.13.2+
  - **Media3**: 1.9.2+ (Core playback engine)
  - **Compose BOM**: 2026.02.00+
  - **Android XR (Spatial)**: 1.0.0-alpha12 (Maintain until stable release)
  - **jcifs-ng**: 2.1.10+ (SMB streaming)
- **Dependency Updates**: When updating libraries, verify API compatibility (e.g., Media3 `C` class constants) and perform full regression testing on both 2D and Spatial modes.

## Coding Style & Import Policy
- **Explicit Imports Over Full Paths**: 코드 가독성을 높이기 위해 클래스나 함수 사용 시 풀 패키지 경로(Fully Qualified Name) 대신 상단 `import`를 사용하는 것을 원칙으로 한다. 특히 Compose Modifier, Layout, UI 요소 등 자주 사용되는 객체는 반드시 임포트하여 코드를 간결하게 유지한다.
- **Import Verification & Cleanup**: 코드 수정 시, 특히 새로운 Compose Modifier나 라이브러리 클래스를 추가할 때 필요한 임포트가 누락되지 않았는지 반드시 확인해야 한다. 사용되지 않는 임포트는 즉시 제거하여 최소한의 깨끗한 임포트 섹션을 유지한다.
- **Grouped Imports**: 관련 있는 패키지끼리 그룹화하여 임포트 섹션을 관리하며, IDE의 자동 정렬(Optimize Imports) 기능을 적극 활용한다.

## Documentation & Commenting Policy
- **KDoc for All Functions**: Every function (including private ones and Composable functions) must have a KDoc comment explaining its purpose, parameters, and return value.
- **Import Verification & Cleanup**: 코드 수정 시, 특히 새로운 Compose Modifier나 라이브러리 클래스를 추가할 때 필요한 임포트가 누락되지 않았는지 반드시 확인해야 한다. 사용되지 않는 임포트는 즉시 제거하여 최소한의 깨끗한 임포트 섹션을 유지한다.
- **Implementation Details**: Complex logic within methods should be documented with inline comments explaining the "why" rather than just the "what."
- **Codebase Navigation**: Use `// MARK:` or equivalent section headers in large files to improve navigability.
- **Language**: All technical documentation and comments must be in Korean for clarity within the team, while maintaining English for standard programming terms.
- **No Redundancy**: Avoid obvious comments that repeat what the code clearly expresses.

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
