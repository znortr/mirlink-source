package com.nos.ridedaemon.core.hud

import android.content.Context
import android.graphics.Color
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import api.Api
import api.MobileCallback
import api.MobileConfig
import api.MobileSession
import api.StreamHost
import com.nos.ridedaemon.R
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class HudStreamController(
    private val context: Context
) {
    private var mobileSession: MobileSession? = null
    private var encoder: HudEncoder? = null

    private var renderer: Hud2DRenderer? = null

    private var targetWidth = 800
    private var targetHeight = 400
    private var targetFps = 30
    private var targetBitrate = 2_500_000 // 2.5 Mbps

    private val staticSignalBytes: ByteArray = context.resources
        .openRawResource(R.raw.static_signal)
        .readBytes()

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val serviceType = "_EasyConn._tcp."
    private val expectedPackageName = "com.cfmoto.cfmotointernational"

    @Volatile
    private var isRunning = false
    @Volatile
    private var isDiscovering = false

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("mdns_lock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMultiCastLock() {
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    fun discoverHost(timeoutMs: Long = 20_000, onResult: (Result<StreamHost>) -> Unit) {
        if (isDiscovering) throw IllegalStateException("controller is already searching")
        if (isRunning) throw IllegalStateException("controller is already streaming")

        acquireMulticastLock()
        isDiscovering = true

        val completed = AtomicBoolean(false)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i("HudStream", "started mDNS discovery: $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i("HudStream", "stopped mDNS discovery: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return

                if (!serviceInfo.serviceType.endsWith(serviceType)) return

                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w("HudStream", "resolved failed: $errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (!isDiscovering || completed.get()) return

                        try {
                            // TXT attributes ≈ entry.Text in Go
                            // (API is getAttributes() in Java, .attributes in Kotlin)
                            val attrs: Map<String, ByteArray> =
                                resolved.attributes  // or resolved.getAttributes()

                            val pkgAttr = attrs["packagename"]?.toString(Charsets.UTF_8)
                            val ipAttr = attrs["ip"]?.toString(Charsets.UTF_8)

                            val port = resolved.port
                            val ip = ipAttr ?: resolved.host.hostAddress
                            val packageName = pkgAttr ?: expectedPackageName

                            val matchesPackage =
                                expectedPackageName.isEmpty() || packageName == expectedPackageName

                            if (matchesPackage && ip != null) {
                                // Stop looking
                                if (!completed.compareAndSet(false, true)) return
                                stopDiscoveryInternal()

                                // Tell Go layer about host + port
                                val ecHost: StreamHost = Api.newStreamHost(ip, port.toString(), packageName)
                                onResult(Result.success(ecHost))
                            }
                        } catch (t: Throwable) {
                            Log.e("HudStream", "error resolving service")
                            // Don’t stop discovery unless we actually matched a host
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.i("HudStream", "service lost: $serviceInfo")
            }

            override fun onStartDiscoveryFailed(
                serviceType: String?,
                errorCode: Int
            ) {
                Log.e("HudStream", "NSD start failed: $errorCode")
                stopDiscoveryInternal()
                if (completed.compareAndSet(false, true)) {
                    onResult(Result.failure(RuntimeException("NSD start failed: $errorCode")))
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                stopDiscoveryInternal()
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e("HudStream", "discover services threw", e)
            stopDiscoveryInternal()
            if (completed.compareAndSet(false, true)) {
                onResult(Result.failure(e))
            }
            return
        }

        // timeout
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isDiscovering || !completed.compareAndSet(false, true)) {
                return@postDelayed
            }
            Log.w("HudStream", "mDNS discovery timed out after $timeoutMs ms")
            stopDiscoveryInternal()
            onResult(Result.failure(Exception("mDNS discovery timeout")))
        }, timeoutMs)
    }

    fun stopDiscovery() {
        stopDiscoveryInternal()
    }

    fun setRendererScene(scene: HudScene, elementsArePreSorted: Boolean = false) {
        if (!isRunning || isDiscovering) throw IllegalStateException("controller is not in a render-able state")
        this.renderer?.setScene(scene)
    }

    private fun stopDiscoveryInternal() {
        if (!isDiscovering) return
        isDiscovering = false

        discoveryListener?.let{ listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                // already stopped
                Log.w("HudStream", "stopServiceDiscovery: already stopped")
            } catch (e: Exception) {
                Log.e("HudStream", "stopServiceDiscovery threw", e)
            }
        }
        discoveryListener = null
        releaseMultiCastLock()
    }

    private fun startEncoder(viewArea: JSONObject) {
        val height = viewArea.optInt("height")
        val width = viewArea.optInt("width")
        if (height > 0 && width > 0) {
            targetHeight = height
            targetWidth = width
        }

        // Make sure we are streaming
        if (!isRunning || this.mobileSession == null) return
        // Make sure the past encoder has been torn down
        if (this.encoder != null || this.renderer != null) return

        val encoder = HudEncoder(
            width = targetWidth,
            height = targetHeight,
            fps = targetFps,
            bitrate = targetBitrate
        ){ avccSample ->
            try {
                this.mobileSession?.pushFrame(avccSample)
            } catch (e: Exception) {
                Log.e("HudStream", "Error pushing frame", e)
            }
        }
        encoder.start()

        val assets = HudAssets(context)
        val sceneRenderer = Hud2DSceneRenderer(context, assets)

        val renderer = Hud2DRenderer(
            surface=encoder.inputSurface,
            fps=targetFps,
            defaultWidth=800f,
            defaultHeight=400f,
            sceneRenderer=sceneRenderer
        )
        renderer.start()

        renderer.setScene(
            HudScene(
                clearColor = Color.LTGRAY,
                designWidth = 800f,
                designHeight = 400f,
                scaleMode = HudScaleMode.FILL,
                elements = listOf(
                    HudElement.AnimatedImage(
                        z = 0,
                        frameResIds = listOf(
                            R.drawable.home_f0,
                            R.drawable.home_f1,
                        ),
                        frameDurationMs = 200, // each frame = 0.2 sec (5 FPS loop)
                        left = 0f,
                        top = 0f,
                        width = 800f,
                        height = 400f
                    )
                )
            )
        )

        this.encoder = encoder
        this.renderer = renderer
    }

    fun start(){
        if (isDiscovering) throw IllegalStateException("controller is discovering services")
        if (isRunning) throw IllegalStateException("controller is already streaming")

        discoverHost {
            result ->
            result.onFailure {
                Log.e("HudStream", "failed to discover HUD", it)
            }
            result.onSuccess { host ->
                val cfg: MobileConfig = Api.newMobileConfig(
                    getStaticSignalBytes(),
                    targetFps.toLong(),
                    10L,5L,10L,3L
                )

                val callback = object : MobileCallback {
                    override fun onError(msg: String?, fatal: Boolean) {
                        val message = msg ?: "unknown HUD error"
                        if (fatal && isRunning) {
                            Log.e("HudStream", "fatal HUD stream error: $message")
                            stopInternal()
                        } else {
                            if (isRunning) {
                                Log.e("HudStream", "non-fatal HUD stream error: $message")
                            }
                        }
                    }

                    override fun onEvent(time: Long, source: Long, command: Long, payload: ByteArray?) {
                        handleHudEvent(time, source, command, payload)
                    }

                    override fun onStopped() {
                        Log.i("HudStream", "HUD session stopped (callback)")
                        stopInternal()
                    }
                }

                val session: MobileSession = try {
                    Api.newMobileSession(cfg, callback)
                } catch (e: Exception) {
                    Log.e("HudStream", "Failed to create MobileSession", e)
                    return@discoverHost
                } as MobileSession

                session.setECHost(host) // Set host

                try {
                    session.startSession()
                } catch (e: Exception) {
                    Log.e("HudStream", "failed to start HUD stream session", e)
                    throw e
                }

                this.mobileSession = session
                isRunning = true
            }
        }
    }

    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!isRunning) return
        isRunning = false

        renderer?.let{
            try{
                it.stop()
            } catch (e: Exception) {
                Log.e("HudStream", "failed to stop renderer", e)
            }
        }
        renderer = null

        encoder?.let {
            try{
                it.stop()
            } catch (e: Exception) {
                Log.e("HudStream", "Error stopping encoder", e)
            }
        }
        encoder = null
        renderer = null

        mobileSession?.let { session ->
            try {
                session.stopSession()
            } catch (e: Exception) {
                Log.e("HudStream", "failed to stop HUD session", e)
            }
        }
        mobileSession = null
    }

    private fun handleHudEvent(time: Long, source: Long, command: Long, payload: ByteArray?) {
        when (source) {
            3L -> payload?.let {
                try {
                    val text = it.toString(Charsets.UTF_8)
                    val json: JSONObject? = JSONObject(text)
                    json?.optJSONObject("viewAreaConfig")
                        ?.optJSONArray("viewAreas")
                        ?.optJSONObject(0)
                        ?.optJSONObject("safeArea")
                        ?.let { area ->
                            this.startEncoder(area)
                        }
                } catch (e: Exception) {
                    Log.e("HudSession", "error parsing HUD Setup response: $e")
                }
            }
            else -> {
                Log.i("HudSession", "Event received: time=$time source=$source command=$command payload=$payload")
            }
        }
    }

    private fun getStaticSignalBytes(): ByteArray {
        return staticSignalBytes
    }
}