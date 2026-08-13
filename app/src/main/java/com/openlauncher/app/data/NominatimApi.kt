package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class NominatimAddress(
    @SerializedName("suburb")  val suburb: String? = null,
    @SerializedName("city")    val city: String? = null,
    @SerializedName("town")    val town: String? = null,
    @SerializedName("village") val village: String? = null,
    @SerializedName("county")  val county: String? = null,
    @SerializedName("state")   val state: String? = null
)

data class NominatimResponse(
    @SerializedName("address")     val address: NominatimAddress? = null,
    @SerializedName("display_name") val displayName: String? = null
)

interface NominatimApiService {
    @GET("reverse")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "json",
        @Query("zoom") zoom: Int = 12,
        @Query("addressdetails") addressDetails: Int = 1
    ): NominatimResponse
}

object NominatimApi {
    // Nominatim's usage policy requires a self-identifying User-Agent on every
    // request — the default OkHttp UA gets silently rate-limited/blocked.
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "OpenLauncher-CarDashboard/1.0 (single-user, personal)")
                .build()
            chain.proceed(request)
        }
        .build()

    val service: NominatimApiService = Retrofit.Builder()
        .baseUrl("https://nominatim.openstreetmap.org/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NominatimApiService::class.java)
}
