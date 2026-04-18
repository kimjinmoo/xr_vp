package com.grepiu.vp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * DLNA 브라우징 기능을 위한 ViewModel.
 */
class DlnaViewModel(application: Application) : AndroidViewModel(application) {
    private val dlnaService = DlnaService(application)
    
    // 발견된 DLNA 장치 목록
    var devices by mutableStateOf<List<DlnaDevice>>(emptyList())
        private set

    // 선택된 장치 및 현재 컨텐츠 목록
    var currentDevice by mutableStateOf<DlnaDevice?>(null)
        private set
    
    var items by mutableStateOf<List<DlnaItem>>(emptyList())
        private set

    var currentContainerId by mutableStateOf("0") // 루트 컨테이너 ID는 보통 "0"
        private set
    
    var isLoading by mutableStateOf(false)
        private set

    private val containerStack = mutableListOf<String>()

    init {
        startDiscovery()
    }

    /**
     * DLNA 장치 검색을 시작함.
     */
    fun startDiscovery() {
        dlnaService.start()
        viewModelScope.launch {
            dlnaService.observeDevices().collectLatest {
                devices = it
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
     * 특정 DLNA 장치를 선택하여 브라우징을 시작함.
     */
    fun selectDevice(device: DlnaDevice) {
        currentDevice = device
        containerStack.clear()
        navigateTo("0")
    }

    /**
     * 특정 컨테이너(폴더)로 이동함.
     */
    fun navigateTo(containerId: String) {
        val device = currentDevice ?: return
        isLoading = true
        viewModelScope.launch {
            val result = dlnaService.browse(device.rawDevice, containerId)
            items = result
            currentContainerId = containerId
            isLoading = false
        }
    }

    /**
     * 하위 아이템을 클릭했을 때의 처리.
     */
    fun onItemClick(item: DlnaItem) {
        if (item.isContainer) {
            containerStack.add(currentContainerId)
            navigateTo(item.id)
        }
    }

    /**
     * 상위 디렉토리로 이동함.
     */
    fun goBack() {
        if (containerStack.isNotEmpty()) {
            val lastId = containerStack.removeAt(containerStack.size - 1)
            navigateTo(lastId)
        } else {
            // 더 이상 뒤로 갈 곳이 없으면 장치 목록으로 돌아감
            currentDevice = null
            items = emptyList()
        }
    }

    override fun onCleared() {
        super.onCleared()
        dlnaService.stop()
    }
}
