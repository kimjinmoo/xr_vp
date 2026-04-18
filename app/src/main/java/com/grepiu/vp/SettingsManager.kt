package com.grepiu.vp

import android.content.Context
import android.content.SharedPreferences

/**
 * 앱의 사용자 설정(언어, 테마 등)을 SharedPreferences에 영구 저장하고 관리하는 클래스.
 * 
 * @property context 안드로이드 컨텍스트.
 */
class SettingsManager(context: Context) {
    /** 설정 데이터를 저장할 SharedPreferences 인스턴스. */
    private val prefs: SharedPreferences = context.getSharedPreferences("vp_settings", Context.MODE_PRIVATE)

    companion object {
        /** 언어 설정을 위한 저장 키. */
        private const val KEY_LANGUAGE = "language"
        /** 테마 모드 설정을 위한 저장 키. */
        private const val KEY_THEME_MODE = "theme_mode"
    }

    /**
     * 저장된 언어 설정을 가져옴. 
     * 저장된 값이 없거나 유효하지 않으면 기본값인 한국어를 반환함.
     * 
     * @return 현재 설정된 [AppLanguage].
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
     * 사용자의 언어 설정을 영구 저장함.
     * 
     * @param language 저장할 [AppLanguage] 객체.
     */
    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    /**
     * 저장된 테마 모드(자동, 라이트, 다크)를 가져옴.
     * 기본값은 시스템 설정을 따르는 'AUTO' 모드임.
     * 
     * @return 현재 설정된 [AppThemeMode].
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
     * 사용자의 테마 모드 설정을 영구 저장함.
     * 
     * @param mode 저장할 [AppThemeMode] 객체.
     */
    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}
