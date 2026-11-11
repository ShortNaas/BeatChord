package com.music.beatchord.data

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Architectural provenance of audio pipeline telemetry statistics.
 */
enum class TelemetryProvenance {
    AUTHORITATIVE,
    MEASURED,
    DERIVED,
    UNKNOWN,
}

/**
 * What the audio decoder is actually being fed, for "stats for nerds".
 *
 * Every figure here is measured from the file being decoded rather than
 * inferred, and every label derived from them — "High Quality", "Lossless",
 * "Hi-Res Lossless" — is decided on the measurements alone. Codec, sample rate,
 * channel count and depth come from the `Format` the audio renderer was
 * configured with, topped up from the file's own header where its container
 * left them blank; see `PlaybackService.measure`. Bitrate is the one a
 * container usually withholds, so a lossless file reports what its samples
 * decode to and a lossy one reports what its own header states. Anything the
 * player hasn't reported stays null and is left out of the display instead of
 * being guessed at.
 */
object NerdStats {

    class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
        /** From the decoder's PCM encoding, where it states one. */
        val bitDepth: Int? = null,
        /**
         * The file's container where it says something the codec doesn't —
         * "DSF" or "DFF" for DSD, which reaches the decoder as plain PCM.
         */
        val container: String? = null,
        /** Provenance of the displayed encoded bitrate. */
        val bitrateProvenance: TelemetryProvenance = TelemetryProvenance.UNKNOWN,
        /** True PCM throughput (sampleRate * bitDepth * channels / 1000). Never conflated with encoded bitrate. */
        val pcmDataRateKbps: Int? = null,
    ) {
        /**
         * Whether the decoder is genuinely being fed a lossless codec — the
         * figure the Now Playing screen's "Lossless" badge is gated on.
         *
         * Only ever decided on the mime type the renderer was configured with,
         * never on what the file's own metadata says about itself: a `.flac`
         * name over an Opus payload would otherwise light a badge over lossy
         * audio. [current] is nulled across a track change so that gap shows
         * nothing rather than the previous track's figures, which keeps
         * "measured or not stated" a rule that can be read straight off the
         * screen.
         */
        val isLossless: Boolean
            get() = isLosslessMime(mimeType)

        /** Dolby Atmos decoded as E-AC-3 JOC; deliberately distinct from lossless. */
        val isDolbyAtmos: Boolean
            get() = isDolbyAtmosMime(mimeType)

        /**
         * Whether this is better than CD quality — past 16-bit or past 48kHz,
         * not merely lossless. A 16-bit/44.1kHz FLAC is a bit-exact CD rip and
         * gets called "Lossless"; a 24-bit/96kHz one is "Hi-Res Lossless",
         * because calling both the same thing would flatten a distinction the
         * listener can plausibly hear.
         */
        val isHiRes: Boolean
            get() = isLossless && ((bitDepth ?: 0) > 16 || (sampleRateHz ?: 0) > 48_000)

        /**
         * Whether this is lossy, but at the top of what lossy gets — a 320kbps
         * AAC or MP3 rather than a 128kbps one.
         *
         * Worth naming on screen because it is the honest answer often enough
         * to matter: a good many libraries hold nothing lossless, and a badge
         * with only two states — "Lossless" or nothing — makes a good encode
         * and a mediocre one look identical. Not called lossless anywhere,
         * because it isn't.
         *
         * Decided on the bitrate rather than on the codec: 256kbps is 256kbps
         * whichever encoder wrote it, and [bitrateKbps] is the figure the
         * decoder was actually handed. Kept off [isLossless]'s answer rather
         * than folded into it, so a lossless file never doubles as this one and
         * the two badges stay distinct.
         */
        val isHiQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) >= HI_QUALITY_KBPS

        /**
         * Whether this is a medium quality stream (e.g. AAC 128-192kbps).
         * Sits strictly between low-quality (<= 80kbps) and high-quality (>= 256kbps).
         */
        val isMediumQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in (LOW_QUALITY_MAX_KBPS + 1) until HI_QUALITY_KBPS

        /**
         * Whether this is a low quality stream (e.g. Opus 50-80kbps).
         */
        val isLowQuality: Boolean
            get() = !isLossless && (bitrateKbps ?: 0) in 1..LOW_QUALITY_MAX_KBPS
    }

    /**
     * What the renderer's input mime type looks like when the bytes behind it
     * are bit-exact.
     *
     * `audio/raw` is here because that is how Media3 names PCM — a WAV stream
     * reaches the renderer as raw samples, not as `audio/wav`.
     */
    private val LOSSLESS_CODEC_SUFFIXES = listOf("flac", "alac", "raw")

    /**
     * Whether [mimeType] names a bit-exact codec.
     *
     * Exposed so a caller holding only a mime type — a queue item, an audio
     * output status — can ask the same question the decoder answered, instead
     * of re-deriving it from the string itself and disagreeing about the edges.
     */
    fun isLosslessMime(mimeType: String?): Boolean =
        mimeType != null && (LOSSLESS_CODEC_SUFFIXES.any { mimeType.endsWith(it) } || mimeType.startsWith("audio/dsd"))

    /**
     * A rate of a megahertz or more, as DSD's are named: 2822400 -> "2.8224 MHz".
     * Null below that, where each caller keeps its own kHz wording. Exact
     * digits rather than one decimal place, since the DSD rates are all
     * multiples of 44.1 kHz and rounding them makes them unrecognisable.
     */
    fun megahertzLabel(hz: Int): String? =
        if (hz < 1_000_000) null
        else java.math.BigDecimal(hz).movePointLeft(6).stripTrailingZeros().toPlainString() + " MHz"

    fun isDolbyAtmosMime(mimeType: String?): Boolean =
        mimeType != null && (mimeType.endsWith("eac3-joc") || mimeType.endsWith("eac3"))

    fun isRawPcm(mimeType: String?): Boolean =
        mimeType != null && (mimeType == "audio/raw" || mimeType.endsWith("wav", ignoreCase = true) || mimeType == "audio/x-wav")

    /**
     * The upper bound in kbps for a low-bitrate lossy stream.
     */
    const val LOW_QUALITY_MAX_KBPS = 80

    /**
     * The bitrate a lossy stream has to reach to be worth calling out.
     *
     * At 256 a 320kbps AAC or MP3 counts and a 128kbps one does not — which is
     * the distinction the label exists to draw.
     */
    const val HI_QUALITY_KBPS = 256

    fun codecLabel(mimeType: String?): String? {
        if (mimeType == null) return null
        return when {
            mimeType.endsWith("flac", ignoreCase = true) -> "FLAC"
            mimeType.endsWith("opus", ignoreCase = true) -> "Opus"
            mimeType.endsWith("mp4a-latm", ignoreCase = true) || mimeType.endsWith("aac", ignoreCase = true) -> "AAC"
            mimeType.endsWith("mpeg", ignoreCase = true) || mimeType.endsWith("mp3", ignoreCase = true) -> "MP3"
            mimeType.endsWith("alac", ignoreCase = true) -> "ALAC"
            mimeType.endsWith("raw", ignoreCase = true) -> "PCM"
            mimeType.startsWith("audio/dsd", ignoreCase = true) -> mimeType.substringAfter('/').uppercase()
            mimeType.endsWith("vorbis", ignoreCase = true) -> "Vorbis"
            mimeType.endsWith("eac3-joc", ignoreCase = true) -> "E-AC-3 JOC"
            mimeType.endsWith("eac3", ignoreCase = true) -> "E-AC-3"
            mimeType.contains("/") -> mimeType.substringAfterLast("/").uppercase()
            else -> mimeType.uppercase()
        }
    }

    val current = MutableStateFlow<Snapshot?>(null)

    fun onTrackTransition() {
        current.value = null
    }

    /**
     * Drops everything measured about the last player, because there isn't one
     * any more.
     *
     * What [current] describes is a stream a particular player was reading, and
     * it is scoped to the *process* while the player it describes is scoped to
     * [PlaybackService][com.music.beatchord.playback.PlaybackService] — which the
     * app being closed destroys while leaving the process alive to be reused.
     * Nothing else clears it: [current] is nulled when the queue moves on, and
     * a service standing back up is not the queue moving on.
     *
     * Left uncleared, a player that has not been handed a single byte yet
     * reports the previous session's codec and bitrate on the nerd stats sheet,
     * and the Now Playing screen keeps a "Lossless" badge over it.
     */
    fun forgetLastSession() {
        current.value = null
    }
}
