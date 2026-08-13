package com.openlauncher.app.ui.widget

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.data.ClockStyle
import com.openlauncher.app.util.LocationData
import com.openlauncher.app.util.SunriseSunset
import kotlinx.coroutines.delay
import java.util.*
import kotlin.math.cos
import kotlin.math.sin

import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Bluetooth

@Composable
fun ClockWidget(
    style: ClockStyle,
    accent: Color,
    isDayMode: Boolean = false,
    location: LocationData? = null,
    showSunriseSunset: Boolean = true,
    showQuickToggles: Boolean = true,
    isEditing: Boolean = false,
    use24HourFormat: Boolean = true,
    modifier: Modifier = Modifier
) {
    var calendar by remember { mutableStateOf(Calendar.getInstance()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            calendar = Calendar.getInstance()
        }
    }

    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    Box(modifier = modifier) {
        when (style) {
            ClockStyle.DIGITAL -> DigitalClock(
                cal = calendar, contentColor = contentColor, subColor = subColor, accent = accent,
                location = location, showSunriseSunset = showSunriseSunset,
                showQuickToggles = showQuickToggles, isDayMode = isDayMode, isEditing = isEditing,
                use24HourFormat = use24HourFormat
            )
            ClockStyle.ANALOG  -> AnalogClock(calendar, accent, isDayMode)
        }
    }
}

@Composable
private fun DigitalClock(
    cal: Calendar,
    contentColor: Color,
    subColor: Color,
    accent: Color,
    location: LocationData?,
    showSunriseSunset: Boolean,
    showQuickToggles: Boolean,
    isDayMode: Boolean,
    isEditing: Boolean,
    use24HourFormat: Boolean
) {
    val hour   = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val isDaylightHour = hour in 6..17
    val timeText = if (use24HourFormat) {
        "%02d:%02d".format(hour, minute)
    } else {
        val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
    }

    Row(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp)) {
        Column(
            modifier            = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon sits directly beside the label (not SpaceBetween'd across the
            // whole row) so it stays anchored to its text instead of drifting to
            // the far edge on wider panels.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    text          = clockTimeLabel(cal),
                    color         = subColor,
                    fontSize      = 9.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Icon(
                    imageVector        = if (isDaylightHour) Icons.Default.WbSunny else Icons.Default.NightsStay,
                    contentDescription = null,
                    tint               = accent.copy(alpha = 0.45f),
                    modifier           = Modifier.size(14.dp)
                )
            }

            // Bottom-anchored — sunrise/sunset (moved here from the Weather panel
            // to keep that panel from getting crowded) sits directly above the
            // time, which stays pinned to its original bottom-left spot.
            Column(horizontalAlignment = Alignment.Start) {
                if (showSunriseSunset && location != null) {
                    val (riseMin, setMin) = remember(location.latitude, location.longitude) {
                        SunriseSunset.localMinutes(location.latitude, location.longitude)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "🌅", fontSize = 11.sp) // 🌅 sunrise
                        Text(text = "%02d:%02d".format(riseMin / 60, riseMin % 60), color = subColor, fontSize = 10.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(text = "🌇", fontSize = 11.sp) // 🌇 sunset
                        Text(text = "%02d:%02d".format(setMin / 60, setMin % 60), color = subColor, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text          = timeText,
                    color         = contentColor,
                    fontSize      = if (use24HourFormat) 44.sp else 36.sp,
                    fontWeight    = FontWeight.Light,
                    letterSpacing = 1.sp
                )
                Text(
                    text     = buildDateString(cal),
                    color    = subColor,
                    fontSize = 12.sp
                )
            }
        }

        // Right rail — WiFi/Bluetooth stacked portrait-style in the panel's
        // otherwise-empty right side. DND dropped: not something anyone
        // actually reaches for on a car head unit.
        if (showQuickToggles) {
            Spacer(Modifier.width(10.dp))
            CompactQuickToggles(
                accent    = accent,
                isDayMode = isDayMode,
                isEditing = isEditing,
                modifier  = Modifier.fillMaxHeight()
            )
        }
    }
}

/**
 * Icon-only WiFi/Bluetooth rail for the Clock panel's empty right side —
 * a slimmer sibling of [QuickTogglesWidget] (which is padded for filling
 * a whole standalone widget cell, not standing in a narrow column).
 */
@Composable
private fun CompactQuickToggles(accent: Color, isDayMode: Boolean, isEditing: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wifiManager = remember { context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager }

    var wifiOn by remember { mutableStateOf(wifiManager?.isWifiEnabled == true) }

    LaunchedEffect(Unit) {
        while (true) {
            wifiOn = wifiManager?.isWifiEnabled == true
            delay(2000)
        }
    }

    val inactiveTint = if (isDayMode) Color(0xFF999999) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = "WiFi",
            tint = if (wifiOn) accent else inactiveTint,
            modifier = Modifier
                .size(18.dp)
                .clickable(enabled = !isEditing) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.Panel.ACTION_WIFI).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
        )
        Spacer(Modifier.height(16.dp))
        Icon(
            imageVector = Icons.Default.Bluetooth,
            contentDescription = "Bluetooth",
            tint = inactiveTint,
            modifier = Modifier
                .size(18.dp)
                .clickable(enabled = !isEditing) {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
        )
    }
}

@Composable
private fun AnalogClock(cal: Calendar, accent: Color, isDayMode: Boolean = false) {
    val hour   = cal.get(Calendar.HOUR).toFloat()
    val minute = cal.get(Calendar.MINUTE).toFloat()
    val second = cal.get(Calendar.SECOND).toFloat()

    val ringColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    val minuteHandColor = if (isDayMode) Color(0xFF222222) else MaterialTheme.colorScheme.onBackground
    val pivotBg = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF1E1E1E)

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx     = size.width / 2f
            val cy     = size.height / 2f
            val radius = size.minDimension / 2f * 0.82f

            // Outer hairline ring
            drawCircle(
                color  = ringColor,
                radius = radius,
                center = Offset(cx, cy),
                style  = Stroke(1.dp.toPx())
            )

            // Tick marks — minimal, hairline
            for (i in 0 until 60) {
                val angle = (Math.PI * 2 / 60 * i - Math.PI / 2).toFloat()
                val isHour = i % 5 == 0
                val isQuarter = i % 15 == 0
                val inner = when {
                    isQuarter -> 0.80f
                    isHour    -> 0.85f
                    else      -> 0.90f
                }
                drawLine(
                    color       = when {
                        isQuarter -> accent.copy(alpha = 0.9f)
                        isHour    -> if (isDayMode) Color(0xFF888888) else Color(0xFF555555)
                        else      -> if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2E2E2E)
                    },
                    start       = Offset(cx + cos(angle) * radius * inner, cy + sin(angle) * radius * inner),
                    end         = Offset(cx + cos(angle) * radius * 0.96f, cy + sin(angle) * radius * 0.96f),
                    strokeWidth = if (isQuarter) 1.5.dp.toPx() else 0.8.dp.toPx(),
                    cap         = StrokeCap.Round
                )
            }

            // Hour hand — thick, accent tinted
            val hAngle = ((hour / 12f + minute / 720f) * 2 * Math.PI - Math.PI / 2).toFloat()
            drawLine(
                color       = accent,
                start       = Offset(cx - cos(hAngle) * radius * 0.14f, cy - sin(hAngle) * radius * 0.14f),
                end         = Offset(cx + cos(hAngle) * radius * 0.50f, cy + sin(hAngle) * radius * 0.50f),
                strokeWidth = 3.dp.toPx(),
                cap         = StrokeCap.Round
            )

            // Minute hand
            val mAngle = ((minute / 60f) * 2 * Math.PI - Math.PI / 2).toFloat()
            drawLine(
                color       = minuteHandColor,
                start       = Offset(cx - cos(mAngle) * radius * 0.14f, cy - sin(mAngle) * radius * 0.14f),
                end         = Offset(cx + cos(mAngle) * radius * 0.74f, cy + sin(mAngle) * radius * 0.74f),
                strokeWidth = 1.5.dp.toPx(),
                cap         = StrokeCap.Round
            )

            // Second hand — accent, hairline
            val sAngle = ((second / 60f) * 2 * Math.PI - Math.PI / 2).toFloat()
            drawLine(
                color       = accent.copy(alpha = 0.75f),
                start       = Offset(cx - cos(sAngle) * radius * 0.22f, cy - sin(sAngle) * radius * 0.22f),
                end         = Offset(cx + cos(sAngle) * radius * 0.88f, cy + sin(sAngle) * radius * 0.88f),
                strokeWidth = 0.8.dp.toPx(),
                cap         = StrokeCap.Round
            )

            // Center pivot
            drawCircle(color = pivotBg, radius = 4.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color  = accent,
                radius = 2.5.dp.toPx(),
                center = Offset(cx, cy),
                style  = Stroke(1.dp.toPx())
            )
        }

        // Date inset — centered, above 6 o'clock position like a real watch
        Text(
            text      = shortDateString(cal),
            color     = if (isDayMode) Color(0xFF999999) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            fontSize  = 9.sp,
            letterSpacing = 1.5.sp,
            modifier  = Modifier
                .align(Alignment.Center)
                .padding(bottom = 44.dp)
        )
    }
}

private fun shortDateString(cal: Calendar): String {
    val days   = arrayOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
    return "${days[cal.get(Calendar.DAY_OF_WEEK) - 1]} ${cal.get(Calendar.DAY_OF_MONTH)}"
}

private fun buildDateString(cal: Calendar): String {
    val days   = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val months = arrayOf("January", "February", "March", "April", "May", "June",
                         "July", "August", "September", "October", "November", "December")
    return "${days[cal.get(Calendar.DAY_OF_WEEK) - 1]}, ${months[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.DAY_OF_MONTH)}"
}

fun clockTimeLabel(cal: Calendar): String = when (cal.get(Calendar.HOUR_OF_DAY)) {
    in 5..11  -> "MORNING"
    in 12..16 -> "AFTERNOON"
    in 17..20 -> "EVENING"
    else      -> "NIGHT"
}
