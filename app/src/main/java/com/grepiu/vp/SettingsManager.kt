package com.grepiu.vp

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱 설정을 SharedPreferences에 영구 저장하고 관리하는 클래스.
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vp_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME_MODE = "theme_mode"
    }

    /**
     * 저장된 언어 설정을 가져옴. 기본값은 한국어.
     */
    fun getLanguage(): AppLanguage {
        val name = prefs.getString(KEY_LANGUAGE, AppLanguage.KOREAN.name)
        return try {
            AppLanguage.valueOf(name ?: AppLanguage.KOREAN.name)
        } catch (e: Exception) {
            AppLanguage.KOREAN
        }
    }

    /**
     * 언어 설정을 저장함.
     */
    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    /**
     * 저장된 테마 모드를 가져옴. 기본값은 자동.
     */
    fun getThemeMode(): AppThemeMode {
        val name = prefs.getString(KEY_THEME_MODE, AppThemeMode.AUTO.name)
        return try {
            AppThemeMode.valueOf(name ?: AppThemeMode.AUTO.name)
        } catch (e: Exception) {
            AppThemeMode.AUTO
        }
    }

    /**
     * 테마 모드를 저장함.
     */
    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}
