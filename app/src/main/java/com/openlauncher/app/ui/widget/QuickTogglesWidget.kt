package com.openlauncher.app.ui.widget

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WiFi/Bluetooth/DND access from the home grid instead of Android's settings menus —
 * mainly so a passenger (or driver at a stoplight) can pair a phone or silence
 * notifications without hunting through several settings screens.
 *
 * WiFi and Bluetooth open the OS's own panel/settings screen rather than toggling
 * directly: apps can no longer flip radios programmatically as of API 29+ (WiFi) and
 * 31+ (Bluetooth) — this launches the real, permission-safe UI instead of silently
 * no-op'ing. DND is toggled directly since NotificationManager still allows that,
 * gated on the one-time Notification Policy Access grant.
 */
@Composable
fun QuickTogglesWidget(
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val wifiManager = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
    val notificationManager = remember { context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager }

    var wifiOn by remember { mutableStateOf(wifiManager?.isWifiEnabled == true) }
    var dndOn  by remember {
        mutableStateOf(
            notificationManager?.let {
                it.isNotificationPolicyAccessGranted &&
                    it.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
            } ?: false
        )
    }

    // Cheap poll so the tiles reflect state changed elsewhere (e.g. the WiFi panel)
    LaunchedEffect(Unit) {
        while (true) {
            wifiOn = wifiManager?.isWifiEnabled == true
            dndOn = notificationManager?.let {
                it.isNotificationPolicyAccessGranted &&
                    it.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE
            } ?: false
            kotlinx.coroutines.delay(2000)
        }
    }

    fun openWifiPanel() {
        runCatching {
            context.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun openBluetoothSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun toggleDnd() {
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val turningOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE
        runCatching {
            nm.setInterruptionFilter(
                if (turningOn) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
        }
        dndOn = turningOn
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleTile(
            icon = Icons.Default.Wifi, label = "WIFI", active = wifiOn,
            accent = accent, isDayMode = isDayMode,
            onClick = { if (!isEditing) openWifiPanel() }
        )
        ToggleTile(
            icon = Icons.Default.Bluetooth, label = "PAIR", active = false,
            accent = accent, isDayMode = isDayMode,
            onClick = { if (!isEditing) openBluetoothSettings() }
        )
        ToggleTile(
            icon = Icons.Default.DoNotDisturbOn, label = "DND", active = dndOn,
            accent = accent, isDayMode = isDayMode,
            onClick = { if (!isEditing) toggleDnd() }
        )
    }
}

@Composable
private fun ToggleTile(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onClick: () -> Unit
) {
    val inactiveTint = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
    val labelColor    = if (active) accent else inactiveTint

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(34.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (active) accent.copy(alpha = 0.15f) else Color.Transparent)
                .border(1.dp, if (active) accent.copy(alpha = 0.6f) else inactiveTint.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (active) accent else inactiveTint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
