package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class NominatimAddress(
    // Finest → coarsest. Dense cities (e.g. Singapore, where "city" and "country"
    // both just resolve to "Singapore") only surface real granularity through
    // neighbourhood/quarter — suburb alone isn't enough there.
    @SerializedName("neighbourhood")  val neighbourhood: String? = null,
    @SerializedName("quarter")        val quarter: String? = null,
    @SerializedName("suburb")         val suburb: String? = null,
    @SerializedName("city_district")  val cityDistrict: String? = null,
    @SerializedName("city")           val city: String? = null,
    @SerializedName("town")           val town: String? = null,
    @SerializedName("village")        val village: String? = null,
    @SerializedName("county")         val county: String? = null,
    @SerializedName("state")          val state: String? = null
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
        // Always request the finest zoom (18 = building-level) — Nominatim still
        // returns the full coarse-to-fine address hierarchy either way, so this
        // just ensures the fine fields (neighbourhood/quarter) are populated when
        // they exist. Which field to actually display is a client-side choice —
        // see LocationDetailLevel.
        @Query("zoom") zoom: Int = 18,
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
