package com.grepiu.vp

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.jupnp.DefaultUpnpServiceConfiguration
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
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * DLNA 미디어 서버 정보를 담는 데이터 클래스.
 * 
 * @property name 사용자에게 표시될 서버의 이름.
 * @property udn 서버의 고유 식별자 (Unique Device Name).
 * @property rawDevice jUPnP에서 제공하는 원시 장치 객체.
 */
data class DlnaDevice(val name: String, val udn: String, val rawDevice: Device<*, *, *>)

/**
 * DLNA 미디어 아이템(폴더 또는 비디오 파일) 정보를 담는 데이터 클래스.
 * 
 * @property name 아이템 제목.
 * @property id DLNA 서버 내의 아이템 식별자.
 * @property isContainer 폴더(컨테이너)인지 여부.
 * @property uri 실제 스트리밍을 위한 URI (파일일 경우에만 존재).
 */
data class DlnaItem(
    val name: String,
    val id: String,
    val isContainer: Boolean,
    val uri: Uri? = null
)

/**
 * DLNA(UPnP) 연동을 위한 서비스 클래스.
 * jUPnP 라이브러리를 사용하여 네트워크 내의 미디어 서버를 검색하고 컨텐츠를 탐색함.
 * 
 * @property context 안드로이드 앱 컨텍스트.
 */
class DlnaService(private val context: Context) {
    /** jUPnP 서비스 인스턴스. */
    private var upnpService: UpnpService? = null
    
    /** 멀티캐스트 패킷 수신을 유지하기 위한 Wi-Fi Lock. */
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * UPnP 서비스를 시작함.
     * 안드로이드 기기에서 멀티캐스트 패킷을 원활히 수신하기 위해 MulticastLock을 획득하고,
     * 적절한 로컬 네트워크 인터페이스에 바인딩함.
     */
    fun start() {
        if (upnpService == null) {
            try {
                Log.d("DLNA_SERVICE", "Starting jUPnP service...")
                
                // 안드로이드에서 절전 모드 등에서도 멀티캐스트(SSDP) 패킷을 받기 위해 필수
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("jupnp_lock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                Log.d("DLNA_SERVICE", "Multicast lock acquired")

                // 192.168.x.x 등 실질적인 Wi-Fi 사설 IP를 찾아 바인딩 주소로 강제 설정
                // VPN이나 가상 인터페이스 혼재 시 검색 실패 문제를 방어함.
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    var bound = false
                    while (interfaces.hasMoreElements()) {
                        val iface = interfaces.nextElement()
                        if (iface.isLoopback || !iface.isUp) continue
                        
                        val addresses = iface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                                System.setProperty("org.jupnp.network.address", addr.hostAddress)
                                Log.d("DLNA_SERVICE", "Forcing jUPnP binding to local address: ${addr.hostAddress} (${iface.name})")
                                bound = true
                                break
                            }
                        }
                        if (bound) break
                    }
                } catch (e: Exception) {
                    Log.e("DLNA_SERVICE", "Error during interface scanning", e)
                }
                
                upnpService = UpnpServiceImpl()
                Log.d("DLNA_SERVICE", "jUPnP service instance created")
            } catch (e: Exception) {
                Log.e("DLNA_SERVICE", "Failed to start jUPnP service", e)
            }
        }
    }

    /**
     * 현재 네트워크에서 장치 검색(SSDP M-SEARCH)을 명시적으로 다시 시도함.
     */
    fun search() {
        Log.d("DLNA_SERVICE", "Triggering manual search...")
        upnpService?.controlPoint?.search()
    }

    /**
     * UPnP 서비스를 종료하고 리소스를 해제함.
     * 서비스 중지 및 Wi-Fi MulticastLock을 반납함.
     */
    fun stop() {
        Log.d("DLNA_SERVICE", "Stopping jUPnP service...")
        upnpService?.shutdown()
        upnpService = null
        
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e("DLNA_SERVICE", "Error releasing multicast lock", e)
        }
        multicastLock = null
    }

    /**
     * 네트워크상의 DLNA 미디어 서버 목록을 실시간으로 감시하여 Flow로 반환함.
     * 
     * @return 발견된 DlnaDevice 리스트의 스트림.
     */
    fun observeDevices(): Flow<List<DlnaDevice>> = callbackFlow {
        val devices = mutableListOf<DlnaDevice>()
        val listener = object : DefaultRegistryListener() {
            override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
                Log.d("DLNA_SERVICE", "Remote device added: ${device.details.friendlyName}")
                // ContentDirectory 서비스를 제공하는 미디어 서버만 필터링
                if (device.findService(UDAServiceType("ContentDirectory")) != null) {
                    val dlnaDevice = DlnaDevice(device.details.friendlyName, device.identity.udn.toString(), device)
                    if (devices.none { it.udn == dlnaDevice.udn }) {
                        devices.add(dlnaDevice)
                        trySend(devices.toList())
                    }
                }
            }

            override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
                Log.d("DLNA_SERVICE", "Remote device removed: ${device.identity.udn.toString()}")
                devices.removeAll { it.udn == device.identity.udn.toString() }
                trySend(devices.toList())
            }
        }

        upnpService?.registry?.addListener(listener)
        
        // 이미 레지스트리에 등록된 장치들도 초기 전송
        upnpService?.registry?.remoteDevices?.forEach { device ->
            if (device.findService(UDAServiceType("ContentDirectory")) != null) {
                val dlnaDevice = DlnaDevice(device.details.friendlyName, device.identity.udn.toString(), device)
                if (devices.none { it.udn == dlnaDevice.udn }) {
                    devices.add(dlnaDevice)
                }
            }
        }
        trySend(devices.toList())
        
        // 검색 시작
        search()

        awaitClose {
            Log.d("DLNA_SERVICE", "Stopping devices observation")
            upnpService?.registry?.removeListener(listener)
        }
    }

    /**
     * 특정 DLNA 서버의 폴더 내용을 조회함.
     * 
     * @param device 조회할 대상 장치.
     * @param containerId 조회할 폴더의 ID.
     * @return 발견된 아이템(폴더 및 파일) 목록.
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
                    
                    // 하위 폴더 처리
                    didl.containers.forEach { container ->
                        items.add(DlnaItem(container.title, container.id, true))
                    }
                    
                    // 미디어 파일 처리
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
