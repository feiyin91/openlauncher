package com.openlauncher.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.openlauncher.app.data.FuelEntry

private const val KM_PER_MILE = 1.609344

@Composable
fun FuelLogWidget(
    entries: List<FuelEntry>,
    isMetric: Boolean,
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    onAddEntry: (odometerKm: Double, volume: Double, cost: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val labelColor    = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    val dimColor      = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    var showAddDialog by remember { mutableStateOf(false) }

    val sorted   = entries.sortedBy { it.timestampMs }
    val last     = sorted.lastOrNull()
    val previous = if (sorted.size >= 2) sorted[sorted.size - 2] else null

    // Distance/efficiency since the prior fill-up (only when there's a pair to diff)
    val distanceKm  = if (last != null && previous != null) last.odometerKm - previous.odometerKm else null
    val efficiency  = if (distanceKm != null && distanceKm > 0 && last!!.volume > 0) {
        if (isMetric) (last.volume / distanceKm) * 100.0            // L/100km
        else distanceKm / KM_PER_MILE / last.volume                  // MPG
    } else null
    val costPerDist = if (distanceKm != null && distanceKm > 0 && last != null) {
        if (isMetric) last.cost / distanceKm else last.cost / (distanceKm / KM_PER_MILE)
    } else null

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (last == null) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.LocalGasStation, null, tint = dimColor, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("NO FILL-UPS LOGGED", color = dimColor, fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.SpaceBetween) {
                        Text("EFFICIENCY", color = labelColor, fontSize = 6.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = efficiency?.let { "%.1f".format(it) } ?: "--.-",
                                color = displayColor, fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isMetric) "L/100" else "MPG",
                                color = displayColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                        Text("LAST FILL-UP", color = labelColor, fontSize = 6.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%.2f".format(last.cost), color = displayColor, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                text = costPerDist?.let { "%.3f/%s".format(it, if (isMetric) "km" else "mi") } ?: "-- / fill-up",
                                color = dimColor, fontSize = 7.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent.copy(alpha = 0.12f))
                        .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .clickable(enabled = !isEditing) { showAddDialog = true }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(12.dp))
                        Text("LOG FILL-UP", color = accent, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddFuelEntryDialog(
            isMetric  = isMetric,
            accent    = accent,
            isDayMode = isDayMode,
            onDismiss = { showAddDialog = false },
            onConfirm = { odometer, volume, cost ->
                val odometerKm = if (isMetric) odometer else odometer * KM_PER_MILE
                onAddEntry(odometerKm, volume, cost)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddFuelEntryDialog(
    isMetric: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (odometer: Double, volume: Double, cost: Double) -> Unit
) {
    var odometerText by remember { mutableStateOf("") }
    var volumeText   by remember { mutableStateOf("") }
    var costText     by remember { mutableStateOf("") }

    val dialogBg   = if (isDayMode) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.background
    val dialogText = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val cancelColor = if (isDayMode) Color(0xFF6C757D) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    val odometer = odometerText.toDoubleOrNull()
    val volume   = volumeText.toDoubleOrNull()
    val cost     = costText.toDoubleOrNull()
    val valid    = odometer != null && odometer > 0 && volume != null && volume > 0 && cost != null && cost >= 0

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .padding(20.dp)
                .widthIn(min = 280.dp, max = 380.dp)
        ) {
            Text("LOG FILL-UP", color = dialogText, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = odometerText,
                onValueChange = { odometerText = it },
                label = { Text(if (isMetric) "ODOMETER (KM)" else "ODOMETER (MI)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = volumeText,
                onValueChange = { volumeText = it },
                label = { Text(if (isMetric) "VOLUME (LITERS)" else "VOLUME (GALLONS)", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = costText,
                onValueChange = { costText = it },
                label = { Text("TOTAL COST", fontSize = 10.sp) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("CANCEL", color = cancelColor, fontSize = 11.sp, letterSpacing = 1.sp)
                }
                TextButton(
                    enabled = valid,
                    onClick = { if (valid) onConfirm(odometer!!, volume!!, cost!!) }
                ) {
                    Text("SAVE", color = if (valid) accent else cancelColor, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}
