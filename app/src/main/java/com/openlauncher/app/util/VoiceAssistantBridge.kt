package com.openlauncher.app.util

import com.openlauncher.app.viewmodel.LauncherViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App-wide bridge between WakeWordService (a plain background Service, not
 * ViewModel-scoped) and LauncherViewModel's voice assistant state.
 *
 * WakeWordService needs to know when a voice command is already in progress
 * (so it can release the mic instead of fighting SpeechRecognizer for it),
 * and needs to hand off a detection to the ViewModel without holding a
 * direct reference to it — a plain object is the simplest link between the
 * two without threading a ViewModel reference through the Service lifecycle.
 */
object VoiceAssistantBridge {
    val voiceState = MutableStateFlow(LauncherViewModel.VoiceAssistantState.IDLE)
    val wakeWordDetected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Shared for the same reason as voiceState: coming back from another app
    // can leave two ViewModel instances alive, and the one that handles a
    // wake word isn't necessarily the one the UI is reading from. With these
    // per-instance, the banner sat on "Thinking…" with the transcript missing
    // while the reply was spoken aloud — state and audio came from one
    // instance, the text from another that never ran anything.
    val voiceTranscript = MutableStateFlow<String?>(null)
    val voiceReply = MutableStateFlow<String?>(null)

    // Debug-only readout (no logcat access on this ROM — no USB debugging
    // support) so detection tuning can happen by watching this on-screen
    // instead of pulling device logs. Remove once "Hi Sebastian" sensitivity
    // is dialed in and confirmed reliable.
    val wakeWordDebug = MutableStateFlow("not started")
}
