package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class OpenMeteoResponse(
    @SerializedName("current_weather") val currentWeather: CurrentWeather?,
    @SerializedName("hourly")          val hourly: HourlyBlock?
)

data class CurrentWeather(
    @SerializedName("temperature")  val temperature: Double,
    @SerializedName("windspeed")    val windspeed: Double,
    @SerializedName("weathercode")  val weathercode: Int,
    @SerializedName("is_day")       val isDay: Int
)

// Open-Meteo returns hourly data as parallel arrays (same index = same hour),
// not a list of objects — zipped into HourlyPoint list after the response lands.
data class HourlyBlock(
    @SerializedName("time")                  val time: List<String> = emptyList(),
    @SerializedName("temperature_2m")        val temperature2m: List<Double> = emptyList(),
    @SerializedName("weathercode")           val weathercode: List<Int> = emptyList(),
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
    @SerializedName("apparent_temperature")  val apparentTemperature: List<Double> = emptyList()
)

interface WeatherApiService {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude")         latitude: Double,
        @Query("longitude")        longitude: Double,
        @Query("current_weather")  currentWeather: Boolean = true,
        @Query("hourly")           hourly: String = "temperature_2m,weathercode,precipitation_probability,apparent_temperature",
        @Query("forecast_days")    forecastDays: Int = 2,
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("windspeed_unit")   windspeedUnit: String = "kmh"
    ): OpenMeteoResponse
}

object WeatherApi {
    private val client = OkHttpClient.Builder().build()

    val service: WeatherApiService = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WeatherApiService::class.java)
}
