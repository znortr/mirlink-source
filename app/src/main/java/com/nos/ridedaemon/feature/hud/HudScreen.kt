package com.nos.ridedaemon.feature.hud

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nos.ridedaemon.R
import com.nos.ridedaemon.core.hud.DoubleBufferedValue
import com.nos.ridedaemon.core.hud.HudElement
import com.nos.ridedaemon.core.hud.HudScaleMode
import com.nos.ridedaemon.core.hud.HudScene
import com.nos.ridedaemon.core.hud.HudStreamController
import com.nos.ridedaemon.core.qr.QrContent
import com.nos.ridedaemon.feature.ap.WifiApController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.core.graphics.toColorInt

// helper to remember and clean up the controller
@Composable
fun rememberHudStreamController(): HudStreamController {
    val appContext = LocalContext.current.applicationContext
    val controller = remember { HudStreamController(appContext) }

    DisposableEffect(Unit) {
        onDispose {
            setOf(controller.stop())
        }
    }

    return controller
}

@Composable
fun rememberWifiApController(
    onHudConnected: () -> Unit,
    onHudDisconnected: (WifiApController) -> Unit ={}
): WifiApController {
    val context = LocalContext.current.applicationContext
    val controller = remember(context, onHudConnected, onHudDisconnected) {
        WifiApController(context, onHudConnected, onHudDisconnected)
    }

    DisposableEffect(controller) {
        controller.startWatching()
        onDispose {
            controller.stopWatching()
        }
    }

    return controller
}

// Element samples
/*HudElement.Rect(
    z = 0,
    left = 0f,
    top = 320f,
    right = 800f,
    bottom = 400f,
    color = 0x66000000,
    cornerRadius = 16f
),
HudElement.Text(
    z = 10,
    text = "Speed: 72 km/h",
    x = 80f,
    y = 370f,
    sizePx = 32f, // virtual "pixels", will be scaled
    color = Color.WHITE
)*/
/*HudElement.AnimatedImage(
    z = 0,
    frameResIds = listOf(
        R.drawable.hud_bg_frame0,
        R.drawable.hud_bg_frame1,
        R.drawable.hud_bg_frame2
    ),
    frameDurationMs = 200, // each frame = 0.2 sec (5 FPS loop)
    left = 0f,
    top = 0f,
    width = 800f,
    height = 400f
)*/
/*HudElement.Image(
    z = 0,
    resId = R.drawable.hud_background_800x400,
    left = 0,
    top = 0,
    width = 800,
    height = 400
)*/

fun requiredWifiPermissions(): Array<String> {
    val list = mutableListOf<String>()

    // For SSID / Wi-Fi info
    list += Manifest.permission.ACCESS_FINE_LOCATION

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.NEARBY_WIFI_DEVICES
    }

    return list.toTypedArray()
}

// extension to get activity from context
fun Context.getActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudScreen(
    hudQrValue: QrContent?,
    hudStreamController: HudStreamController = rememberHudStreamController(),
    onShowQrScanner: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var isStreaming by remember { mutableStateOf(false) }
    val permissions = remember { requiredWifiPermissions() }

    // Stream start/stop handy methods
    fun streamSessionStart() {
        if (isStreaming) return

        try {
            hudStreamController.start()
            isStreaming = true
        } catch (e: IllegalStateException) {
            Log.e("HudStream", "start() called in invalid state", e)
            Toast.makeText(
                ctx,
                e.message ?: "Unable to start HUD stream",
                Toast.LENGTH_SHORT
            ).show()
            isStreaming = false
        } catch(e: Exception) {
            Log.e("HudStream", "start() failed: $e")
            isStreaming = false
        }
    }
    fun streamSessionStop() {
        try {
            hudStreamController.stop()
            isStreaming = false
        } catch (e: Exception) {
            Log.e("HudStream", "stop() error", e)
        }
    }

    val scope = rememberCoroutineScope()
    val wifiController = rememberWifiApController(
        onHudConnected = {
            scope.launch {
                val start = System.currentTimeMillis()
                Log.d("HUD", "delay start: $start")

                delay(8000)

                Log.d("HUD", "delay end:  ${System.currentTimeMillis() - start} ms elapsed")
                streamSessionStart()
            }
        },
        onHudDisconnected = { controller ->
            // controller.clearAllHudSuggestions() -- uncomment if you want a suggestion to be removed after disconnect
            streamSessionStop()
        }
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                ctx,
                "Location/Wi-Fi permissions are required to connect to the HUD Wi-Fi.",
                Toast.LENGTH_LONG
            ).show()
        }
        wifiController.startWatching()
    }

    // HUD UI
    var switched = remember { false }
    val timeBuffer = remember { DoubleBufferedValue("--:--:--") }
    val timeFormatter = remember {
        if (DateFormat.is24HourFormat(ctx)) {
            DateTimeFormatter.ofPattern("HH:mm:ss")
        } else {
            DateTimeFormatter.ofPattern("hh:mm:ss a")
        }
    }

    // Have we received a HUD QR value, if so connect to that wifi setup
    LaunchedEffect(hudQrValue) {
        if (hudQrValue is QrContent.HudWifi) {
            val allGranted = permissions.all { perm ->
                ContextCompat.checkSelfPermission(ctx, perm) ==
                        PackageManager.PERMISSION_GRANTED
            }

            if (!allGranted) {
                permissionLauncher.launch(permissions)
            } else {

                val status = wifiController.suggestHudNetwork(hudQrValue)
                if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(ctx, "Failed to suggest HUD's AP (status=$status", Toast.LENGTH_LONG).show()
                }
            }
        } else if (hudQrValue is QrContent.BadQr) {
            Toast.makeText(ctx, "invalid HUD QR", Toast.LENGTH_LONG).show()
        }
    }

    val isHudConnected = wifiController.isHudConnected

    // Timestamp ticker
    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while(isActive) {
            val now = LocalTime.now()
            timeBuffer.publish(timeFormatter.format(now))
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moto HUD Dev") },
                actions = {
                    TextButton(onClick = onShowQrScanner) {
                        Text("Scan QR")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text="HUD AP status: ${if (isHudConnected) "ONLINE" else "OFFLINE"}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Streaming status: ${if (isStreaming) "STREAMING" else "STOPPED"}",
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        ctx.getActivity()?.let { act ->
                            when {
                                ContextCompat.checkSelfPermission(
                                    ctx,
                                    Manifest.permission.NEARBY_WIFI_DEVICES
                                ) == PackageManager.PERMISSION_GRANTED -> {
                                    streamSessionStart()
                                }

                                ActivityCompat.shouldShowRequestPermissionRationale(
                                    act,
                                    Manifest.permission.NEARBY_WIFI_DEVICES
                                ) -> {
                                    Toast.makeText(
                                        ctx,
                                        "Nearby Wi-Fi devices needed to find your bike",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }

                                else -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        ActivityCompat.requestPermissions(
                                            act,
                                            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                                            0
                                        )
                                    }
                                }
                            }
                        }
                    },
                    enabled = !isStreaming
                ) {
                    Text("Start")
                }

                OutlinedButton(
                    onClick = {
                        streamSessionStop()
                    },
                    enabled = isStreaming
                ) {
                    Text("Stop")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = {
                        hudStreamController.setRendererScene(
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
                                    ),
                                    HudElement.TextDynamic(
                                        z=1,
                                        prefix = "",
                                        value = timeBuffer,
                                        x = 50f,
                                        y = 80f,
                                        color = "#FF7CFFB2".toColorInt() ,
                                        sizePx = 32f,
                                    ),
                                    HudElement.SineWave(
                                        z = 2,
                                        x = 235f,
                                        y = 370f,
                                        width = 360f,
                                        amplitude = 15f,
                                        wavelength = 140f,
                                        phaseSpeedRadPerSec = 3.0f, // animate
                                        samples = 160,
                                        color = 0xFF7CFFB2.toInt(),
                                        strokeWidth = 1f
                                    )
                                )
                            )
                        )
                        switched = true
                    },
                    enabled = isStreaming && !switched
                ) {
                    Text("Set time scene")
                }
            }
        }
    }
}