package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.util.LocationData
import kotlin.math.absoluteValue

@Composable
fun LocationWidget(
    location: LocationData?,
    placeName: String? = null,
    accent: Color,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val displayColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val labelColor   = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    val dimColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    Box(modifier = modifier.fillMaxSize()) {
        if (location == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.GpsOff, null, tint = dimColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    "NO GPS FIX", color = dimColor, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "LIVE LOCATION", color = labelColor, fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                    )
                    Icon(Icons.Default.GpsFixed, null, tint = accent.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (placeName != null) {
                        Text(
                            text = placeName,
                            color = displayColor, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${formatCoordinate(location.latitude, isLat = true)}  ${formatCoordinate(location.longitude, isLat = false)}",
                            color = dimColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = formatCoordinate(location.latitude, isLat = true),
                            color = displayColor, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatCoordinate(location.longitude, isLat = false),
                            color = displayColor, fontSize = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "±${location.accuracy.toInt()}m ACCURACY",
                    color = dimColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp
                )
            }
        }
    }
}

private fun formatCoordinate(value: Double, isLat: Boolean): String {
    val hemisphere = if (isLat) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")
    return "%.4f° %s".format(value.absoluteValue, hemisphere)
}
