package com.grepiu.vp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// MARK: - Color Schemes (색상 스킴 설정)

/**
 * 다크 테마에서 사용될 컬러 스킴 정의.
 * 메인 색상으로 Purple80 계열을 사용함.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

/**
 * 라이트 테마에서 사용될 컬러 스킴 정의.
 * 메인 색상으로 Purple40 계열을 사용함.
 */
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* 기타 커스텀 색상 예시:
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * VP 앱의 전체 디자인 테마를 설정하는 메인 컴포저블.
 * Material 3 기반의 다이나믹 컬러 및 시스템 다크/라이트 모드 대응을 지원함.
 *
 * @param darkTheme 시스템 다크 모드 사용 여부 (기본값은 시스템 설정 추종).
 * @param dynamicColor Android 12(S) 이상에서 배경색 추출 테마 사용 여부.
 * @param content 테마가 적용될 하위 UI 컴포저블.
 */
@Composable
fun VPTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Android 12 이상에서 제공하는 다이나믹 컬러 기능 (기본 활성화)
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // Android 12(API 31) 이상이면 시스템 다이나믹 컬러를 우선 적용하고, 그 외엔 정의된 스킴 사용
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
