package com.openlauncher.app.model

/**
 * The single JSON shape Gemini is instructed to always reply with (see
 * VoicePrompt.kt for the system instruction). One flat data class rather than
 * a sealed hierarchy — Gson needs a concrete shape to parse into, and every
 * action carries spokenReply so TTS confirmation/answering is one code path
 * regardless of which action fired.
 */
data class VoiceActionResult(
    val action: String = "UNKNOWN",
    val theme: String? = null,
    val dayNightMode: String? = null,
    val screen: String? = null,
    val query: String? = null,
    val destination: String? = null,
    val direction: String? = null,
    val clockFormat: String? = null,
    val odometerKm: Double? = null,
    val volumeLiters: Double? = null,
    val cost: Double? = null,
    val spokenReply: String = "Sorry, I didn't catch that."
)
