package com.grepiu.vp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * DLNA 미디어 서버 브라우징을 관리하는 ViewModel.
 * 장치 발견 목록, 현재 선택된 장치, 폴더 내 아이템 목록 및 탐색 스택을 관리함.
 * 
 * @param application 안드로이드 애플리케이션 컨텍스트.
 */
class DlnaViewModel(application: Application) : AndroidViewModel(application) {
    /** DLNA 기능을 수행하는 서비스 객체. */
    private val dlnaService = DlnaService(application)
    
    /** 탐색 경로를 추적하기 위한 스택 (컨테이너 ID 목록). */
    private val navigationStack = mutableListOf<String>()

    /** 발견된 DLNA 미디어 서버 목록. */
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set

    /** 현재 선택하여 탐색 중인 DLNA 장치. */
    var currentDevice by mutableStateOf<DlnaDevice?>(null)
        private set

    /** 현재 폴더 내의 아이템(폴더/파일) 목록. */
    var items by mutableStateOf<List<DlnaItem>>(emptyList())
        private set

    /** 데이터 로딩 중인지 여부. */
    var isLoading by mutableStateOf(false)
        private set

    init {
        // 서비스 시작 및 장치 목록 감시 시작
        dlnaService.start()
        viewModelScope.launch {
            dlnaService.observeDevices().collect { discoveredDevices ->
                devices = discoveredDevices
            }
        }
    }

    /**
     * DLNA 장치 검색을 다시 시도함.
     */
    fun refreshDiscovery() {
        dlnaService.search()
    }

    /**
     * 특정 DLNA 장치를 선택하여 최상위(Root) 브라우징을 시작함.
     * 
     * @param device 선택된 장치.
     */
    fun selectDevice(device: DlnaDevice) {
        currentDevice = device
        navigationStack.clear()
        navigationStack.add("0") // DLNA 표준 Root ID는 보통 "0"
        browseCurrent()
    }

    /**
     * 폴더 아이템을 클릭했을 때 하위 경로로 진입함.
     * 
     * @param item 클릭된 아이템 (컨테이너여야 함).
     */
    fun onItemClick(item: DlnaItem) {
        if (item.isContainer) {
            navigationStack.add(item.id)
            browseCurrent()
        }
    }

    /**
     * 현재 스택의 최상단 ID를 기준으로 폴더 내용을 조회함.
     */
    private fun browseCurrent() {
        val device = currentDevice ?: return
        val containerId = navigationStack.lastOrNull() ?: "0"
        
        isLoading = true
        viewModelScope.launch {
            items = dlnaService.browse(device.rawDevice, containerId)
            isLoading = false
        }
    }

    /**
     * 이전 경로로 돌아감. 최상위일 경우 장치 선택 목록으로 복귀함.
     */
    fun goBack() {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.size - 1)
            browseCurrent()
        } else {
            // 더 이상 뒤로 갈 곳이 없으면 장치 목록으로 돌아감
            currentDevice = null
            items = emptyList()
            navigationStack.clear()
        }
    }

    /**
     * ViewModel 소멸 시 DLNA 서비스를 중지하고 리소스를 해제함.
     */
    override fun onCleared() {
        super.onCleared()
        dlnaService.stop()
    }
}
