package com.grepiu.vp

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.support.contentdirectory.callback.Browse
import org.jupnp.support.model.BrowseFlag
import org.jupnp.support.model.DIDLContent
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * DLNA 미디어 서버 정보 데이터 클래스.
 */
data class DlnaDevice(val name: String, val udn: String, val rawDevice: Device<*, *, *>)

/**
 * DLNA 아이템 정보 데이터 클래스.
 */
data class DlnaItem(
    val name: String,
    val id: String,
    val isContainer: Boolean,
    val uri: Uri? = null
)

/**
 * DLNA(UPnP) 연동을 위한 서비스 클래스 (jUPnP 사용).
 */
class DlnaService(private val context: Context) {
    private var upnpService: UpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * UPnP 서비스를 시작함.
     */
    fun start() {
        if (upnpService == null) {
            try {
                // 안드로이드에서 멀티캐스트 패킷 수신을 위해 Lock 획득 필수
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("jupnp_lock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                
                upnpService = UpnpServiceImpl()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * UPnP 서비스를 종료함.
     */
    fun stop() {
        upnpService?.shutdown()
        upnpService = null
        
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        multicastLock = null
    }

    /**
     * 네트워크상의 DLNA 미디어 서버를 검색하고 Flow로 반환함.
     */
    fun observeDevices(): Flow<List<DlnaDevice>> = callbackFlow {
        val devices = mutableListOf<DlnaDevice>()
        val listener = object : DefaultRegistryListener() {
            override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
                if (device.findService(UDAServiceType("ContentDirectory")) != null) {
                    val dlnaDevice = DlnaDevice(device.details.friendlyName, device.identity.udn.toString(), device)
                    if (devices.none { it.udn == dlnaDevice.udn }) {
                        devices.add(dlnaDevice)
                        trySend(devices.toList())
                    }
                }
            }

            override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
                devices.removeAll { it.udn == device.identity.udn.toString() }
                trySend(devices.toList())
            }
        }

        upnpService?.registry?.addListener(listener)
        
        // 기존 발견된 장치 전송
        upnpService?.registry?.remoteDevices?.forEach { device ->
            if (device.findService(UDAServiceType("ContentDirectory")) != null) {
                val dlnaDevice = DlnaDevice(device.details.friendlyName, device.identity.udn.toString(), device)
                if (devices.none { it.udn == dlnaDevice.udn }) {
                    devices.add(dlnaDevice)
                }
            }
        }
        trySend(devices.toList())
        
        // 주기적으로 검색 시도
        upnpService?.controlPoint?.search()

        awaitClose {
            upnpService?.registry?.removeListener(listener)
        }
    }

    /**
     * 특정 장치의 컨텐츠 디렉토리를 브라우징함.
     */
    suspend fun browse(device: Device<*, *, *>, containerId: String): List<DlnaItem> = withContext(Dispatchers.IO) {
        val service: Service<*, *> = device.findService(UDAServiceType("ContentDirectory")) ?: return@withContext emptyList()
        
        suspendCoroutine { continuation ->
            upnpService?.controlPoint?.execute(object : Browse(service, containerId, BrowseFlag.DIRECT_CHILDREN) {
                override fun received(
                    invocation: ActionInvocation<out Service<*, *>>?,
                    didl: DIDLContent
                ) {
                    val items = mutableListOf<DlnaItem>()
                    
                    didl.containers.forEach { container ->
                        items.add(DlnaItem(container.title, container.id, true))
                    }
                    
                    didl.items.forEach { item ->
                        val uri = item.firstResource?.value?.let { Uri.parse(it) }
                        items.add(DlnaItem(item.title, item.id, false, uri))
                    }
                    
                    continuation.resume(items)
                }

                override fun updateStatus(status: Status?) {}

                override fun failure(
                    invocation: ActionInvocation<out Service<*, *>>?,
                    operation: UpnpResponse?,
                    defaultMsg: String?
                ) {
                    continuation.resume(emptyList())
                }
            })
        }
    }
}
