package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.util.LocationData
import kotlin.math.absoluteValue

@Composable
fun WeatherWidget(
    state: WeatherState?,
    // Null = this fetch is as current as the app ever knows anything to be.
    // Non-null = state is a cached/offline fallback obtained at this time —
    // see LauncherViewModel.weatherAsOfMillis.
    asOfMillis: Long? = null,
    accent: Color,
    metric: Boolean,
    isDayMode: Boolean = false,
    location: LocationData? = null,
    placeName: String? = null,
    showWindSpeed: Boolean = true,
    showFeelsLike: Boolean = true,
    showRainChance: Boolean = true,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    Box(modifier = modifier) {
        if (state != null) {
            Column(
                modifier            = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Current conditions
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(text = state.conditionIcon, fontSize = 30.sp)
                    Column {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text          = state.temperatureDisplay(metric),
                                color         = contentColor,
                                fontSize      = 28.sp,
                                fontWeight    = FontWeight.Light,
                                letterSpacing = 1.sp
                            )
                            if (showFeelsLike && Math.round(state.feelsLikeCelsius) != Math.round(state.temperatureCelsius)) {
                                Text(
                                    text     = "feels ${state.feelsLikeDisplay(metric)}",
                                    color    = subColor,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        }
                        Text(
                            text          = state.conditionLabel.uppercase() +
                                (if (showWindSpeed) "  ·  ${state.windspeedDisplay(metric)}" else ""),
                            color         = subColor,
                            fontSize      = 9.sp,
                            letterSpacing = 1.sp
                        )
                        if (asOfMillis != null) {
                            Text(
                                // Offline/cache fallback — the whole point is
                                // making clear this isn't a live reading, so
                                // this stays visible regardless of how old it
                                // actually is rather than hiding under some
                                // "still fresh enough" cutoff.
                                text     = "as of " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                                    .format(java.util.Date(asOfMillis)),
                                color    = subColor.copy(alpha = 0.7f),
                                fontSize = 8.sp,
                                letterSpacing = 0.5.sp
                            )
                        }
                        if (placeName != null) {
                            Text(
                                text          = placeName,
                                color         = subColor.copy(alpha = 0.8f),
                                fontSize      = 12.sp,
                                letterSpacing = 0.3.sp,
                                lineHeight    = 14.sp,
                                // Wraps to a 2nd line only when the full name doesn't
                                // fit on one — short names ("Singapore, Kallang") stay
                                // single-line, long ones ("Singapore, Woodlands South")
                                // get the space instead of being cut off mid-word.
                                maxLines      = 2,
                                overflow      = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        } else if (location != null) {
                            Text(
                                text = "%.2f°%s %.2f°%s".format(
                                    location.latitude.absoluteValue, if (location.latitude >= 0) "N" else "S",
                                    location.longitude.absoluteValue, if (location.longitude >= 0) "E" else "W"
                                ),
                                color         = subColor.copy(alpha = 0.7f),
                                fontSize      = 10.sp,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }

                // Next few hours — scrolls if the widget's narrower than the whole strip,
                // so resizing it wider (e.g. spanning 2 columns) just reveals more of it.
                if (state.hourlyForecast.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier              = Modifier.fillMaxWidth()
                    ) {
                        items(state.hourlyForecast) { pt ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text          = hourLabel(pt.hour),
                                    color         = subColor,
                                    fontSize      = 8.sp,
                                    fontFamily    = FontFamily.Monospace,
                                    letterSpacing = 0.5.sp
                                )
                                Text(text = pt.conditionIcon, fontSize = 15.sp)
                                Text(
                                    text     = pt.temperatureDisplay(metric),
                                    color    = contentColor,
                                    fontSize = 10.sp
                                )
                                if (showRainChance && pt.precipitationChance > 0) {
                                    Text(
                                        text     = "${pt.precipitationChance}%",
                                        color    = if (isDayMode) Color(0xFF3B7FD1) else Color(0xFF6EA8E0),
                                        fontSize = 7.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hourLabel(hour24: Int): String {
    val suffix = if (hour24 < 12) "A" else "P"
    val h12 = when (hour24 % 12) { 0 -> 12; else -> hour24 % 12 }
    return "$h12$suffix"
}
