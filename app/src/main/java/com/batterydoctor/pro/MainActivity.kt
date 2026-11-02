
package com.batterydoctor.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs

data class BatterySnapshot(
    val level: Int = 0,
    val tempC: Float = 0f,
    val voltageV: Float? = null,
    val currentMa: Float? = null,
    val powerW: Float? = null,
    val status: String = "Unknown",
    val source: String = "Unknown",
    val health: String = "Unknown",
    val technology: String = "Unknown",
    val plugged: Int = 0
)

private val bgColor = Color(0xFF0B1220)
private val cardColor = Color(0xFF151F30)
private val accentColor = Color(0xFF63D9B5)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initial = readSnapshot(null)

        setContent {
            var data by remember {
                mutableStateOf(initial)
            }

            // BATTERY BROADCAST RECEIVER
            DisposableEffect(Unit) {

                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?
                    ) {
                        data = readSnapshot(intent)
                    }
                }

                val filter = IntentFilter(
                    Intent.ACTION_BATTERY_CHANGED
                )

                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("DEPRECATION")
                    registerReceiver(receiver, filter)
                }

                onDispose {
                    try {
                        unregisterReceiver(receiver)
                    } catch (_: Exception) {
                    }
                }
            }

            // V1.3 AUTO REFRESH ADD-ON
            DisposableEffect(lifecycle) {

                val scope = rememberCoroutineScope()
                var refreshJob: Job? = null

                val observer = LifecycleEventObserver {
                        _, event ->

                    when (event) {

                        Lifecycle.Event.ON_RESUME -> {

                            if (refreshJob?.isActive != true) {

                                refreshJob = scope.launch {

                                    while (isActive) {

                                        delay(2000)

                                        data = readSnapshot(null)
                                    }
                                }
                            }
                        }

                        Lifecycle.Event.ON_PAUSE -> {

                            refreshJob?.cancel()
                            refreshJob = null
                        }

                        else -> Unit
                    }
                }

                lifecycle.addObserver(observer)

                onDispose {

                    lifecycle.removeObserver(observer)

                    refreshJob?.cancel()
                    refreshJob = null
                }
            }
            // END AUTO REFRESH ADD-ON

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = accentColor,
                    background = bgColor,
                    surface = cardColor
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = bgColor
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing
                            )
                            .verticalScroll(
                                rememberScrollState()
                            )
                            .padding(18.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            "BATTERY DOCTOR",
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Professional",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Live Battery & Charging Diagnostics",
                            color = Color.LightGray
                        )

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = cardColor
                            ),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(22.dp),
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {

                                Text(
                                    "BATTERY LEVEL",
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )

                                Text(
                                    "${data.level}%",
                                    color = accentColor,
                                    fontSize = 54.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                LinearProgressIndicator(
                                    progress = {
                                        data.level.coerceIn(0, 100) / 100f
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                )

                                Spacer(Modifier.height(12.dp))

                                Text(
                                    data.status,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Section("BATTERY DIAGNOSTICS")

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            Metric(
                                "Temperature",
                                "${fmt(data.tempC)} °C",
                                Modifier.weight(1f)
                            )

                            Metric(
                                "Battery Voltage",
                                data.voltageV?.let {
                                    "${fmt(it)} V"
                                } ?: "Unavailable",
                                Modifier.weight(1f)
                            )
                        }

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            Metric(
                                "Health Status",
                                data.health,
                                Modifier.weight(1f)
                            )

                            Metric(
                                "Technology",
                                data.technology,
                                Modifier.weight(1f)
                            )
                        }

                        Section("LIVE CHARGING")

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            Metric(
                                "Current",
                                data.currentMa?.let {
                                    "${fmt(it)} mA"
                                } ?: "Unavailable",
                                Modifier.weight(1f)
                            )

                            Metric(
                                "Estimated Power",
                                data.powerW?.let {
                                    "${fmt(it)} W"
                                } ?: "Unavailable",
                                Modifier.weight(1f)
                            )
                        }

                        Section("CHARGER INFORMATION")

                        Info(
                            "Connection",
                            if (data.plugged != 0)
                                "Connected"
                            else
                                "Disconnected"
                        )

                        Info("Power Source", data.source)

                        Info("Charging Status", data.status)

                        Info(
                            "Phone",
                            "${Build.MANUFACTURER} ${Build.MODEL}"
                        )

                        Info(
                            "Android Version",
                            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                        )

                        Section("CHARGER ANALYSIS")

                        Info(
                            "Adapter Model",
                            "Not exposed by Android"
                        )

                        Info(
                            "SUPERVOOC",
                            "Protocol not verified"
                        )

                        Info(
                            "Cable Status",
                            "Not independently detectable"
                        )

                        Section("BATTERY SAFETY")

                        Info(
                            "Temperature Alert",
                            when {
                                data.tempC >= 50f ->
                                    "High temperature"

                                data.tempC >= 45f ->
                                    "Temperature elevated"

                                else ->
                                    "No high-temperature alert"
                            }
                        )

                        Info(
                            "Battery Health",
                            data.health
                        )

                        Section("IMPORTANT INFORMATION")

                        Text(
                            "Charging power is estimated from battery voltage and reported battery current. It is not direct USB charger input power.",
                            color = Color(0xFFB8C3D4),
                            fontSize = 12.sp
                        )

                        Text(
                            "Android does not reliably expose the exact charger model, cable quality or proprietary SUPERVOOC negotiation.",
                            color = Color(0xFFB8C3D4),
                            fontSize = 12.sp
                        )

                        Text(
                            "Battery health status is supplied by the phone. It is not a measured battery capacity percentage.",
                            color = Color(0xFFB8C3D4),
                            fontSize = 12.sp
                        )

                        Text(
                            "Auto Refresh: Every 2 Seconds",
                            color = accentColor,
                            fontSize = 11.sp,
                            modifier = Modifier.align(
                                Alignment.CenterHorizontally
                            )
                        )

                        Text(
                            "Professional Battery Doctor V1.3",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.align(
                                Alignment.CenterHorizontally
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readSnapshot(
        intent: Intent?
    ): BatterySnapshot {

        val i = intent ?: registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        val level = i?.getIntExtra(
            BatteryManager.EXTRA_LEVEL, 0
        ) ?: 0

        val scale = i?.getIntExtra(
            BatteryManager.EXTRA_SCALE, 100
        ) ?: 100

        val percentage = if (scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }

        val temperature = (
            i?.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, 0
            ) ?: 0
        ) / 10f

        val voltageRaw = i?.getIntExtra(
            BatteryManager.EXTRA_VOLTAGE, 0
        ) ?: 0

        val voltage = if (voltageRaw > 0) {
            voltageRaw / 1000f
        } else {
            null
        }

        val statusCode = i?.getIntExtra(
            BatteryManager.EXTRA_STATUS, -1
        ) ?: -1

        val status = when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING ->
                "Charging"

            BatteryManager.BATTERY_STATUS_FULL ->
                "Full"

            BatteryManager.BATTERY_STATUS_DISCHARGING ->
                "Discharging"

            BatteryManager.BATTERY_STATUS_NOT_CHARGING ->
                "Not Charging"

            else -> "Unknown"
        }

        val plugged = i?.getIntExtra(
            BatteryManager.EXTRA_PLUGGED, 0
        ) ?: 0

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC ->
                "AC Adapter"

            BatteryManager.BATTERY_PLUGGED_USB ->
                "USB"

            BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                "Wireless"

            else ->
                if (plugged == 0) "Battery" else "Other"
        }

        val healthCode = i?.getIntExtra(
            BatteryManager.EXTRA_HEALTH, -1
        ) ?: -1

        val health = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD ->
                "Good"

            BatteryManager.BATTERY_HEALTH_OVERHEAT ->
                "Overheating"

            BatteryManager.BATTERY_HEALTH_DEAD ->
                "Dead"

            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                "Over Voltage"

            BatteryManager.BATTERY_HEALTH_COLD ->
                "Cold"

            else -> "Unknown"
        }

        val technology = i?.getStringExtra(
            BatteryManager.EXTRA_TECHNOLOGY
        ) ?: "Unknown"

        val bm = getSystemService(
            BATTERY_SERVICE
        ) as BatteryManager

        val currentMicroAmps = try {

            val now = bm.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
            )

            if (now != Int.MIN_VALUE && now != 0) {
                now
            } else {
                bm.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE
                )
            }

        } catch (_: Exception) {
            Int.MIN_VALUE
        }

        val current = if (
            currentMicroAmps != Int.MIN_VALUE &&
            currentMicroAmps != 0
        ) {
            abs(currentMicroAmps.toFloat() / 1000f)
        } else {
            null
        }

        val power = if (
            current != null && voltage != null
        ) {
            current * voltage / 1000f
        } else {
            null
        }

        return BatterySnapshot(
            level = percentage,
            tempC = temperature,
            voltageV = voltage,
            currentMa = current,
            powerW = power,
            status = status,
            source = source,
            health = health,
            technology = technology,
            plugged = plugged
        )
    }
}

@Composable
fun Section(title: String) {
    Text(
        title,
        color = accentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun Metric(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                title,
                color = Color.LightGray,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(7.dp))

            Text(
                value,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun Info(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                cardColor,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
        horizontalArrangement =
            Arrangement.SpaceBetween
    ) {
        Text(
            label,
            color = Color.LightGray,
            fontSize = 13.sp
        )

        Text(
            value,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

fun fmt(value: Float): String {
    return String.format(
        Locale.US,
        "%.2f",
        value
    )
}
