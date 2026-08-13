package com.openlauncher.app.model

data class HourlyPoint(
    val hour: Int,           // 0-23, local time
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val precipitationChance: Int = 0   // 0-100, percent
) {
    fun temperatureDisplay(metric: Boolean): String =
        if (metric) "${Math.round(temperatureCelsius)}°" else "${Math.round(celsiusToFahrenheit(temperatureCelsius))}°"

    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

data class WeatherState(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val windspeedKmh: Double,
    val isDay: Boolean,
    val hourlyForecast: List<HourlyPoint> = emptyList(),
    val feelsLikeCelsius: Double = temperatureCelsius
) {
    // roundToInt, not toInt — truncation displayed 20.9° as 20°
    fun temperatureDisplay(metric: Boolean): String =
        if (metric) "${Math.round(temperatureCelsius)}°C"
        else "${Math.round(celsiusToFahrenheit(temperatureCelsius))}°F"

    fun feelsLikeDisplay(metric: Boolean): String =
        if (metric) "${Math.round(feelsLikeCelsius)}°"
        else "${Math.round(celsiusToFahrenheit(feelsLikeCelsius))}°"

    fun windspeedDisplay(metric: Boolean): String =
        if (metric) "${Math.round(windspeedKmh)} km/h"
        else "${Math.round(windspeedKmh / 1.609)} mph"

    val conditionLabel: String get() = wmoCodeToLabel(weatherCode)
    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

private fun celsiusToFahrenheit(c: Double) = c * 9.0 / 5.0 + 32.0

private fun wmoCodeToLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2, 3 -> "Cloudy"
    45, 48 -> "Foggy"
    51, 53, 55 -> "Drizzle"
    61, 63, 65 -> "Rain"
    71, 73, 75 -> "Snow"
    80, 81, 82 -> "Showers"
    95 -> "Thunderstorm"
    96, 99 -> "Hail"
    else -> "Unknown"
}

private fun wmoCodeToEmoji(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "🌤"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 61, 63, 65, 80, 81, 82 -> "🌧️"
    71, 73, 75 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
