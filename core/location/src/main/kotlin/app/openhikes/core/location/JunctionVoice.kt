package app.openhikes.core.location

import android.content.Context
import android.speech.tts.TextToSpeech
import app.openhikes.core.common.DefaultDispatcher
import app.openhikes.core.common.LaunchConfigHolder
import app.openhikes.core.model.DistanceUnit
import app.openhikes.core.model.JunctionCue
import app.openhikes.core.model.JunctionManeuver
import app.openhikes.core.model.RouteProfile
import app.openhikes.core.model.RouteTurnJunctionDetector
import app.openhikes.core.model.TrailJunction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Spoken audio seam for trail junction warnings so unit and UI tests never touch platform TTS.
 */
interface JunctionVoice {
    fun speak(message: String)

    fun stopSpeaking()
}

/**
 * Source of junctions along a [RouteProfile], backed by route turn geometry until offline routing
 * graph tiles are wired.
 */
interface TrailJunctionProvider {
    suspend fun junctionsAlong(profile: RouteProfile): List<TrailJunction>
}

@Singleton
internal class DefaultTrailJunctionProvider @Inject constructor(
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher
) : TrailJunctionProvider {
    override suspend fun junctionsAlong(profile: RouteProfile): List<TrailJunction> = withContext(defaultDispatcher) {
        RouteTurnJunctionDetector.detect(profile)
    }
}

@Singleton
internal class AndroidJunctionVoice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val launchConfigHolder: LaunchConfigHolder
) : JunctionVoice {
    private val lock = Any()
    private var tts: TextToSpeech? = null
    private var ready: Boolean = false
    private var pendingUtterance: String? = null

    override fun speak(message: String) {
        if (launchConfigHolder.current.value.isUiTesting) return
        synchronized(lock) {
            if (tts == null) {
                pendingUtterance = message
                tts = TextToSpeech(context) { status ->
                    synchronized(lock) {
                        ready = status == TextToSpeech.SUCCESS
                        if (ready) {
                            tts?.language = Locale.getDefault()
                            pendingUtterance?.let { queued ->
                                pendingUtterance = null
                                tts?.speak(queued, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
                            }
                        }
                    }
                }
            } else if (ready) {
                tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } else {
                pendingUtterance = message
            }
        }
    }

    override fun stopSpeaking() {
        synchronized(lock) {
            pendingUtterance = null
            if (ready) {
                tts?.stop()
            }
        }
    }

    private companion object {
        const val UTTERANCE_ID = "openhikes-junction-cue"
    }
}

/**
 * Formats junction maneuvers and rounded distances into concise American English spoken warnings.
 */
object JunctionCueFormatter {
    const val WARNINGS_ENABLED_ANNOUNCEMENT = "Junction warnings on"

    fun formatSpokenCue(cue: JunctionCue, unit: DistanceUnit): String {
        val action = maneuverPhrase(cue.maneuver)
        val rounded = cue.roundedAheadValue(unit)?.roundToInt()
        return if (rounded == null) {
            action
        } else {
            val unitWord = if (unit == DistanceUnit.IMPERIAL) "feet" else "meters"
            "$action in $rounded $unitWord"
        }
    }

    private fun maneuverPhrase(maneuver: JunctionManeuver): String = when (maneuver) {
        JunctionManeuver.KEEP_LEFT -> "Keep left at the fork"
        JunctionManeuver.KEEP_RIGHT -> "Keep right at the fork"
        JunctionManeuver.TURN_LEFT -> "Turn left"
        JunctionManeuver.TURN_RIGHT -> "Turn right"
        JunctionManeuver.SHARP_LEFT -> "Turn sharp left"
        JunctionManeuver.SHARP_RIGHT -> "Turn sharp right"
        JunctionManeuver.STRAIGHT -> "Go straight at the fork"
    }
}
