package app.openhikes.core.testing.android

import app.openhikes.core.location.JunctionVoice
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recording [JunctionVoice] fake capturing spoken junction warnings without touching Android TTS.
 */
@Singleton
class FakeJunctionVoice @Inject constructor() : JunctionVoice {
    private val utterances = ArrayList<String>()

    val spokenMessages: List<String>
        get() = utterances.toList()

    var stopCount: Int = 0
        private set

    override fun speak(message: String) {
        utterances.add(message)
    }

    override fun stopSpeaking() {
        stopCount += 1
    }

    fun clear() {
        utterances.clear()
        stopCount = 0
    }
}
