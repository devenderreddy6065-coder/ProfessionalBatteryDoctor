
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import java.io.File

data class BatterySnapshot(
    val level: Int = 0,
    val tempC: Float = 0f,
    val voltageV: Float? = null,
    val currentMa: Float? = null,
    val powerW: Float? = null,
    val currentCapacityMah: Float? = null,
    val status: String = "Unknown",
    val source: String = "Unknown",
    val health: String = "Unknown",
    val technology: String = "Unknown",
    val plugged: Int = 0,
    val debugBroadcastVoltage: String = "Unknown",
    val debugSysfsVoltage: String = "Unknown",
    val debugCurrentNow: String = "Unknown",
    val debugCurrentAverage: String = "Unknown",
    val debugSensorAccess: String = "Unknown"
)

private val bgColor = Color(0xFF0B1220)
private val cardColor = Color(0xFF151F30)
private val accentColor = Color(0xFF63D9B5)

class MainActivity : ComponentActivity() {

    // BATTERY SENSOR ADD-ON
    private fun readBatterySysfsValue(
        vararg paths: String
    ): Long? {
        for (path in paths) {
            try {
                val value = File(path).readText().trim().toLongOrNull()
                if (value != null && value != 0L) {
                    return value
                }
            } catch (_: Exception) {
                // Try next available path
            }
        }
        return null
    }
    // END BATTERY SENSOR ADD-ON
    // V1.6.1 ADVANCED BATTERY VOLTAGE SCAN
    // Only read battery-side nodes. USB/AC voltage is charger input voltage,
    // not battery voltage, so those directories are deliberately excluded.
    private fun readAnyPowerSupplyVoltageV(): Float? {
        val root = File("/sys/class/power_supply")
        val directories = try {
            root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val hints = listOf("battery", "bms", "fuel", "fg", "main", "cell")
        for (directory in directories) {
            val name = directory.name.lowercase(Locale.US)
            if (hints.none { it in name }) continue

            for (node in listOf("voltage_now", "voltage_avg", "voltage_ocv")) {
                try {
                    val raw = File(directory, node).readText().trim().toLongOrNull()
                        ?: continue
                    val volts = when {
                        abs(raw) >= 100_000L -> raw / 1_000_000f
                        abs(raw) >= 1_000L -> raw / 1_000f
                        else -> continue
                    }
                    if (volts in 2.5f..5.5f) return volts
                } catch (_: Exception) {
                    // Permission denied or node unavailable: try next node.
                }
            }
        }
        return null
    }

    private fun describePowerSupplyVoltageNodes(): String {
        val root = File("/sys/class/power_supply")
        val directories = try {
            root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (directories.isEmpty()) return "power_supply: unavailable or blocked"

        val hints = listOf("battery", "bms", "fuel", "fg", "main", "cell")
        val output = mutableListOf<String>()
        for (directory in directories) {
            val name = directory.name.lowercase(Locale.US)
            if (hints.none { it in name }) continue
            for (node in listOf("voltage_now", "voltage_avg", "voltage_ocv")) {
                val file = File(directory, node)
                val result = when {
                    !file.exists() -> "missing"
                    !file.canRead() -> "blocked"
                    else -> try {
                        file.readText().trim().take(18)
                    } catch (_: Exception) {
                        "read-error"
                    }
                }
                output.add("${directory.name}/$node=$result")
            }
        }
        return output.take(18).joinToString(" | ").ifEmpty {
            "No battery-related power_supply folders found"
        }
    }
    // END V1.6.1 ADVANCED BATTERY VOLTAGE SCAN

    // V1.5.7 SUPERVOOC DETECTION ADD-ON
    private fun readSuperVoocStatus(): String {
        val propertyNames = listOf(
            "sys.oplus.chg_type",
            "sys.oplus.vooc_charger",
            "persist.sys.oplus.vooc",
            "ro.oplusvooc.support"
        )

        for (name in propertyNames) {
            try {
                val systemProperties = Class.forName("android.os.SystemProperties")
                val getMethod = systemProperties.getMethod(
                    "get",
                    String::class.java,
                    String::class.java
                )
                val value = getMethod.invoke(null, name, "") as? String ?: ""
                val normalized = value.lowercase(Locale.US)
                if ("supervooc" in normalized || "vooc" in normalized) {
                    return "Detected by ColorOS property"
                }
            } catch (_: Exception) {
                // Hidden system properties may be blocked for normal apps.
            }
        }

        val candidatePaths = listOf(
            "/sys/class/oplus_chg/voocphy/voocphy_status",
            "/sys/class/oplus_chg/voocphy/vooc_chg_type",
            "/sys/class/oplus_chg/voocphy/fastchg_status",
            "/sys/class/oplus_chg/charger/vooc_chg_type",
            "/sys/class/oplus_chg/charger/fastchg_status",
            "/sys/class/power_supply/usb/real_type",
            "/sys/class/power_supply/battery/charge_type"
        )

        for (path in candidatePaths) {
            try {
                val value = File(path).readText().trim()
                val normalized = value.lowercase(Locale.US)
                if ("supervooc" in normalized || "vooc" in normalized) {
                    return "Detected by ColorOS vendor interface"
                }
            } catch (_: Exception) {
                // Vendor nodes may be private or unreadable to ordinary apps.
            }
        }

        return "Not exposed to this app by ColorOS"
    }
    // END SUPERVOOC DETECTION ADD-ON

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
            LaunchedEffect(Unit) {

                lifecycle.repeatOnLifecycle(
                    Lifecycle.State.RESUMED
                ) {

                    while (isActive) {

                        delay(2000)

                        data = readSnapshot(null)
                    }
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

                        // BATTERY DOCTOR PRO LOGO ADD-ON
                        Image(
                            painter = painterResource(
                                id = R.drawable.battery_doctor_pro
                            ),
                            contentDescription =
                                "Battery Doctor Pro Logo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp),
                            contentScale = ContentScale.Fit
                        )
                        // END LOGO ADD-ON

                        Text(
                            "LIVE BATTERY & CHARGING DIAGNOSTICS",
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(
                                Alignment.CenterHorizontally
                            )
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
                                    "${fmtVoltage(it)} V"
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

                        // CURRENT CAPACITY ADD-ON
                        Section("BATTERY CAPACITY")

                        Metric(
                            "Current Capacity",
                            data.currentCapacityMah?.let {
                                "${fmt(it)} mAh (${data.level}%)"
                            } ?: "Unavailable",
                            Modifier.fillMaxWidth()
                        )
                        // END CURRENT CAPACITY ADD-ON

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

                        // V1.5.7 SUPERVOOC / 67W ADD-ON
                        Info(
                            "SUPERVOOC Status",
                            readSuperVoocStatus()
                        )

                        Info(
                            "Device Charging Capability",
                            if (Build.MODEL.contains("CPH2603", ignoreCase = true)) {
                                "Up to 67W SUPERVOOC"
                            } else {
                                "Not confirmed for this model"
                            }
                        )
                        // END SUPERVOOC / 67W ADD-ON

                        Info(
                            "Cable Status",
                            "Not independently detectable"
                        )

                        // V1.6.0 BATTERY SENSOR DEBUG
                        Section("BATTERY SENSOR DEBUG (V1.6.1)")
                        Info("Android raw voltage", data.debugBroadcastVoltage)
                        Info("Sysfs voltage scan", data.debugSysfsVoltage)
                        Info("CURRENT_NOW raw", data.debugCurrentNow)
                        Info("CURRENT_AVERAGE raw", data.debugCurrentAverage)
                        Info("Sensor file access", data.debugSensorAccess)
                        // END BATTERY SENSOR DEBUG

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
                            "Estimated Power is battery-side only and is shown only when valid voltage and current readings are available. It is not direct USB charger input power.",
                            color = Color(0xFFB8C3D4),
                            fontSize = 12.sp
                        )

                        Text(
                            "SUPERVOOC detection is shown only when an accessible ColorOS property or vendor interface explicitly reports VOOC/SUPERVOOC. Some OPPO system interfaces may be private to normal apps.",
                            color = Color(0xFFB8C3D4),
                            fontSize = 12.sp
                        )

                        Text(
                            "Current Capacity is the remaining charge reported by the phone. It is not the battery design capacity.",
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
                            "Battery Doctor Pro V1.6.1",
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
        val debugBroadcastVoltage = "$voltageRaw mV"

        // VOLTAGE READING ADD-ON
        // Android EXTRA_VOLTAGE is in millivolts. Reject implausible
        // tiny values so they are not displayed as 0.00 V.
        val voltagePaths = listOf(
            "/sys/class/power_supply/battery/voltage_now",
            "/sys/class/power_supply/battery/voltage_avg",
            "/sys/class/power_supply/battery/voltage_ocv",
            "/sys/class/power_supply/Battery/voltage_now",
            "/sys/class/power_supply/Battery/voltage_avg",
            "/sys/class/power_supply/bms/voltage_now",
            "/sys/class/power_supply/bms/voltage_avg",
            "/sys/class/power_supply/bms/voltage_ocv",
            "/sys/class/power_supply/usb/voltage_now",
            "/sys/class/power_supply/main/voltage_now"
        )
        val sysfsVoltage = readBatterySysfsValue(*voltagePaths.toTypedArray())
        val debugSysfsVoltage = describePowerSupplyVoltageNodes()

        val broadcastVoltageV = voltageRaw / 1000f
        val sysfsVoltageV = when {
            sysfsVoltage != null && sysfsVoltage > 100000 ->
                sysfsVoltage / 1000000f
            sysfsVoltage != null && sysfsVoltage > 1000 ->
                sysfsVoltage / 1000f
            else -> null
        }

        // Battery voltage should normally be in a plausible single-cell
        // Li-ion range. Reject tiny/incorrectly-scaled values such as 0.004 V.
        // Prefer a valid Android broadcast value, then try readable sysfs values.
        val scannedVoltageV = readAnyPowerSupplyVoltageV()
        val voltage = listOfNotNull(
            broadcastVoltageV.takeIf { voltageRaw > 0 },
            sysfsVoltageV,
            scannedVoltageV
        ).firstOrNull { it in 2.5f..5.5f }
        // END VOLTAGE READING ADD-ON

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

        // CURRENT CAPACITY ADD-ON
        val chargeCounter = try {
            bm.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
            )
        } catch (_: Exception) {
            Int.MIN_VALUE
        }

        val currentCapacity = if (
            chargeCounter != Int.MIN_VALUE &&
            chargeCounter > 0
        ) {
            chargeCounter / 1000f
        } else {
            null
        }
        // END CURRENT CAPACITY ADD-ON

        // V1.5.8 CURRENT / POWER FIX
        // Android reports current in microamps. Some vendor kernels expose
        // a tiny/incorrect CURRENT_NOW value, so try CURRENT_AVERAGE and
        // readable battery sysfs current nodes as fallbacks.
        val androidCurrentNow = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (_: Exception) {
            Int.MIN_VALUE
        }

        val androidCurrentAverage = try {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        } catch (_: Exception) {
            Int.MIN_VALUE
        }
        val debugCurrentNow = if (androidCurrentNow == Int.MIN_VALUE) "INT_MIN / unavailable" else "$androidCurrentNow µA"
        val debugCurrentAverage = if (androidCurrentAverage == Int.MIN_VALUE) "INT_MIN / unavailable" else "$androidCurrentAverage µA"
        val debugSensorAccess = if (voltagePaths.any { File(it).canRead() }) {
            "At least one candidate readable"
        } else {
            "No candidate voltage file readable by app"
        }

        val sysfsCurrentMicroAmps = readBatterySysfsValue(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/current_avg",
            "/sys/class/power_supply/Battery/current_now",
            "/sys/class/power_supply/Battery/current_avg",
            "/sys/class/power_supply/bms/current_now",
            "/sys/class/power_supply/bms/current_avg"
        )?.takeIf { abs(it) in 10_000L..10_000_000L }

        // Prefer a meaningful Android reading. If it is suspiciously small
        // (<10 mA), prefer a valid sysfs reading when the device exposes one.
        val androidCurrent = listOf(
            androidCurrentNow,
            androidCurrentAverage
        ).firstOrNull {
            it != Int.MIN_VALUE && it != 0 && abs(it.toLong()) <= 10_000_000L
        }

        val selectedCurrentMicroAmps = when {
            androidCurrent != null &&
                abs(androidCurrent.toLong()) >= 10_000L -> androidCurrent.toLong()
            sysfsCurrentMicroAmps != null -> sysfsCurrentMicroAmps
            androidCurrent != null -> androidCurrent.toLong()
            else -> null
        }

        // Normalize the sign for a clear user-facing display:
        // charging = positive, discharging = negative.
        val current = selectedCurrentMicroAmps?.let { raw ->
            val magnitudeMa = abs(raw).toFloat() / 1000f
            when (statusCode) {
                BatteryManager.BATTERY_STATUS_CHARGING -> magnitudeMa
                BatteryManager.BATTERY_STATUS_DISCHARGING -> -magnitudeMa
                BatteryManager.BATTERY_STATUS_FULL -> 0f
                else -> if (plugged != 0) magnitudeMa else -magnitudeMa
            }
        }

        // Battery-side estimated power. Use current magnitude so a vendor's
        // negative charging-current sign cannot suppress the watt reading.
        // Show battery-side power while charging OR discharging.
        // The absolute current magnitude is used; the sign remains visible
        // separately in the Current field to indicate charge/discharge direction.
        // V1.6.1: show watts ONLY when both real sensor readings are valid.
        // Never substitute nominal/design voltage or an assumed value.
        val power = if (
            voltage != null && voltage in 2.5f..5.5f &&
            current != null && current.isFinite() && abs(current) > 0f
        ) {
            (voltage * abs(current) / 1000f).takeIf { it.isFinite() && it > 0f }
        } else {
            null
        }
        // END CURRENT / POWER FIX

        return BatterySnapshot(
            level = percentage,
            tempC = temperature,
            voltageV = voltage,
            currentMa = current,
            powerW = power,
            currentCapacityMah = currentCapacity,
            status = status,
            source = source,
            health = health,
            technology = technology,
            plugged = plugged,
            debugBroadcastVoltage = debugBroadcastVoltage,
            debugSysfsVoltage = debugSysfsVoltage,
            debugCurrentNow = debugCurrentNow,
            debugCurrentAverage = debugCurrentAverage,
            debugSensorAccess = debugSensorAccess
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

// Voltage uses 3 decimal places so small non-zero readings
// do not appear as 0.00 V after rounding.
fun fmtVoltage(value: Float): String {
    return String.format(
        Locale.US,
        "%.3f",
        value
    )
}
