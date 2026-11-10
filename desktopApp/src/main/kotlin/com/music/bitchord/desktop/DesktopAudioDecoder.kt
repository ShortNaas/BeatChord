package com.music.bitchord.desktop

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.AVDISCARD_ALL
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_get_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S32
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_get_bytes_per_sample
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_out_samples
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer

/** What a buffer of decoded audio looks like on its way to the speakers. */
internal data class DesktopPcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val bytesPerSample: Int,
    val isFloat: Boolean = false,
) {
    val frameBytes: Int get() = channels * bytesPerSample

    /** Bytes per second, which is what turns a buffer length into a duration. */
    val byteRate: Int get() = sampleRate * frameBytes

    fun durationUsOf(bytes: Int): Long =
        if (byteRate == 0) 0L else bytes.toLong() * 1_000_000L / byteRate
}

/** Audio decoding, by way of FFmpeg. */
internal class DesktopAudioDecoder : DesktopSampleSource {

    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var resampler: SwrContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null

    private var streamIndex = -1
    private var streamTimeBase = 1.0
    private var outputBuffer: BytePointer? = null
    private var outputBufferSamples = 0
    private var outputPlanes: PointerPointer<BytePointer>? = null
    private var scratch = ByteArray(0)
    private var samples = FloatArray(0)

    /** Set once the container is exhausted, so the drain runs exactly once. */
    private var drained = false

    /** The format samples arrive in, once [open] has succeeded. */
    var outputFormat: DesktopPcmFormat = DesktopPcmFormat(44_100, 2, 2)
        private set

    /** Total length, or null when the source will not say. */
    var durationUs: Long? = null
        private set

    /** Where the last decoded frame sat, for position reporting after a seek. */
    var positionUs: Long = 0L
        private set

    /** The stream's first timestamp: song position 0, where a container does not start at it. */
    private var streamStartUs = 0L

    /** After a seek, decoded audio before this song position is dropped; see [seek]. */
    private var trimBeforeUs: Long? = null

    /** What is actually being decoded, once [open] has succeeded. */
    var measuredFormat: DesktopAudioFormat? = null
        private set

    /** Opens the file at [path] and prepares to decode it as [requested]. */
    fun open(
        path: String,
        requested: DesktopPcmFormat,
    ): Result<Unit> = runCatching {
        av_log_set_level(AV_LOG_ERROR)

        val opened = AVFormatContext(null)
        // A file on this machine: no headers, no reconnect options, and no demuxer to name —
        // FFmpeg reads the container's own signature.
        val status = avformat_open_input(opened, path, null, null as AVDictionary?)
        check(status >= 0) { "could not open stream (${describe(status)})" }
        format = opened
        check(avformat_find_stream_info(opened, null as AVDictionary?) >= 0) { "no stream info" }

        streamIndex = bestRendition(opened)
            ?: av_find_best_stream(opened, AVMEDIA_TYPE_AUDIO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0)
        check(streamIndex >= 0) { "no audio stream" }
        // The container's other streams are not read at all.
        for (i in 0 until opened.nb_streams()) {
            if (i != streamIndex) opened.streams(i).discard(AVDISCARD_ALL)
        }

        val stream = opened.streams(streamIndex)
        val timeBase = stream.time_base()
        streamTimeBase = timeBase.num().toDouble() / timeBase.den().toDouble()
        streamStartUs = stream.start_time()
            .takeIf { it != AV_NOPTS_VALUE }
            ?.let { (it * streamTimeBase * 1_000_000L).toLong() }
            ?: 0L

        val parameters = stream.codecpar()
        val decoder = avcodec_find_decoder(parameters.codec_id()) ?: error("no decoder for stream")
        val context = avcodec_alloc_context3(decoder)
        check(avcodec_parameters_to_context(context, parameters) >= 0) { "bad codec parameters" }
        // Lets the decoder keep frame timestamps right when it trims priming samples (Opus's
        // pre-skip, an MP3's encoder delay), which the seek trim in [convert] reads.
        context.pkt_timebase(timeBase)
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context

        durationUs = when {
            opened.duration() != AV_NOPTS_VALUE && opened.duration() > 0 -> opened.duration()
            stream.duration() > 0 -> (stream.duration() * streamTimeBase * 1_000_000L).toLong()
            else -> null
        }

        outputFormat = requested.copy(sampleRate = context.sample_rate())
        measuredFormat = measure(decoder.name()?.string, context, opened)
        resampler = buildResampler(context, outputFormat)
        packet = av_packet_alloc()
        frame = av_frame_alloc()
        drained = false
    }.onFailure { close() }

    /**
     * The rendition to play when a file offers more than one audio stream — lossless first, then
     * the highest bitrate — or null when there is nothing to choose between.
     *
     * `av_find_best_stream` has no notion of lossless: given a container that holds both an AAC
     * commentary track and the album's FLAC, it picks whichever it likes the look of. A local file
     * can hold more than one stream as easily as anything served does, so the choice is made here.
     */
    private fun bestRendition(container: AVFormatContext): Int? {
        val audio = (0 until container.nb_streams()).filter { i ->
            val parameters = container.streams(i).codecpar()
            parameters.codec_type() == AVMEDIA_TYPE_AUDIO && avcodec_find_decoder(parameters.codec_id()) != null
        }
        if (audio.size < 2) return null
        return audio.maxWithOrNull(
            compareBy<Int> { i ->
                val name = avcodec_get_name(container.streams(i).codecpar().codec_id())?.string
                DesktopAudioFormat(codec = name).isLossless
            }.thenBy { i -> renditionBitrate(container, i) },
        )
    }

    /** A stream's bitrate, from its parameters or, failing those, the bandwidth it advertises. */
    private fun renditionBitrate(container: AVFormatContext, index: Int): Long {
        val stream = container.streams(index)
        stream.codecpar().bit_rate().takeIf { it > 0 }?.let { return it }
        return av_dict_get(stream.metadata(), "variant_bitrate", null, 0)
            ?.value()?.string?.toLongOrNull() ?: 0L
    }

    /** The decoded stream's own figures. */
    private fun measure(
        codecName: String?,
        context: AVCodecContext,
        container: AVFormatContext,
    ): DesktopAudioFormat {
        val channels = context.ch_layout().nb_channels().takeIf { it > 0 }
        val lossless = DesktopAudioFormat(codec = codecName).isLossless
        val depth = context.bits_per_raw_sample()
            .takeIf { it > 0 }
            ?: av_get_bytes_per_sample(context.sample_fmt()).takeIf { it > 0 }?.times(8)
        val bits = context.bit_rate().takeIf { it > 0 } ?: container.bit_rate().takeIf { it > 0 }
        return DesktopAudioFormat(
            codec = codecName,
            kbps = bits?.let { (it / 1_000).toInt() }?.takeIf { it > 0 },
            sampleRateHz = context.sample_rate().takeIf { it > 0 },
            bitDepth = depth?.takeIf { lossless },
            channels = channels,
        )
    }

    /** FFmpeg's own words for an AVERROR, so a failure says what it was. */
    private fun describe(status: Int): String {
        val text = ByteArray(256)
        av_strerror(status, text, text.size.toLong())
        val message = String(text, Charsets.UTF_8).substringBefore('\u0000').trim()
        return message.ifBlank { "error $status" }
    }

    private fun buildResampler(context: AVCodecContext, target: DesktopPcmFormat): SwrContext {
        val outLayout = AVChannelLayout()
        av_channel_layout_default(outLayout, target.channels)
        val swr = SwrContext(null)
        val status = swr_alloc_set_opts2(
            swr,
            outLayout,
            target.sampleFormat(),
            target.sampleRate,
            context.ch_layout(),
            context.sample_fmt(),
            context.sample_rate(),
            0,
            null,
        )
        check(status >= 0) { "could not configure resampler" }
        check(swr_init(swr) >= 0) { "resampler refused these formats" }
        return swr
    }

    /** The next block of PCM, or null at the end of the stream. */
    fun read(): ByteArray? {
        val context = codec ?: return null
        val container = format ?: return null
        val pkt = packet ?: return null
        val decoded = frame ?: return null

        while (true) {
            // Whatever the decoder is already holding, before asking for more.
            val received = avcodec_receive_frame(context, decoded)
            if (received == 0) {
                val block = convert(decoded) ?: return null
                // Empty when the whole frame fell before a seek target; on to the next one.
                if (block.isNotEmpty()) return block
                continue
            }
            if (received == AVERROR_EOF) return null
            // Every other negative means "not enough input yet".
            if (!pump(container, pkt, context)) return null
        }
    }

    /** The next block as interleaved floats, or null at the end of the stream. */
    override fun readSamples(): FloatArray? {
        val block = read() ?: return null
        val count = block.size / 4
        if (samples.size < count) samples = FloatArray(count)
        for (index in 0 until count) {
            val at = index * 4
            samples[index] = Float.fromBits(
                (block[at].toInt() and 0xFF) or
                    ((block[at + 1].toInt() and 0xFF) shl 8) or
                    ((block[at + 2].toInt() and 0xFF) shl 16) or
                    (block[at + 3].toInt() shl 24),
            )
        }
        sampleCount = count
        return samples
    }

    /** How much of the array [readSamples] returned is actually this block. */
    override var sampleCount: Int = 0
        private set

    /** Feeds the decoder one more packet. */
    private fun pump(container: AVFormatContext, pkt: AVPacket, context: AVCodecContext): Boolean {
        if (drained) return false
        val read = av_read_frame(container, pkt)
        if (read < 0) {
            avcodec_send_packet(context, null as AVPacket?)
            drained = true
            return true
        }
        if (pkt.stream_index() == streamIndex) avcodec_send_packet(context, pkt)
        av_packet_unref(pkt)
        return true
    }

    private fun convert(decoded: AVFrame): ByteArray? {
        val swr = resampler ?: return null
        val target = outputFormat
        val capacity = swr_get_out_samples(swr, decoded.nb_samples()).coerceAtLeast(decoded.nb_samples())
        ensureOutputCapacity(capacity, target)
        val planes = outputPlanes ?: return null

        val produced = swr_convert(swr, planes, capacity, decoded.data(), decoded.nb_samples())
        if (produced <= 0) return ByteArray(0)

        val stamp = decoded.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: decoded.pts()
        val frameUs = if (stamp != AV_NOPTS_VALUE) {
            (stamp * streamTimeBase * 1_000_000L).toLong() - streamStartUs
        } else {
            null
        }
        if (frameUs != null) positionUs = frameUs

        // Frames before a seek target: the demuxer landed on the index point before it.
        var skip = 0
        val trimTo = trimBeforeUs
        if (trimTo != null) {
            if (frameUs == null || trimTo - frameUs > MAX_SEEK_TRIM_US) {
                // No timestamp to trim against — or one so far short of the target that it is on
                // another clock than the seek was — so play from wherever the seek landed.
                trimBeforeUs = null
            } else {
                skip = ((trimTo - frameUs) * target.sampleRate / 1_000_000L)
                    .coerceIn(0L, produced.toLong())
                    .toInt()
                if (skip < produced) trimBeforeUs = null
            }
        }
        if (skip >= produced) return ByteArray(0)

        val bytes = (produced - skip) * target.frameBytes
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        val buffer = outputBuffer ?: return null
        buffer.position(skip.toLong() * target.frameBytes)
        buffer.limit(produced.toLong() * target.frameBytes)
        buffer.get(scratch, 0, bytes)
        return scratch.copyOf(bytes)
    }

    private fun ensureOutputCapacity(samples: Int, target: DesktopPcmFormat) {
        if (outputBuffer != null && samples <= outputBufferSamples) return
        outputBuffer?.deallocate()
        val bytes = samples.toLong() * target.frameBytes
        val buffer = BytePointer(bytes)
        outputBuffer = buffer
        outputBufferSamples = samples
        outputPlanes = PointerPointer<BytePointer>(1L).apply { put(0L, buffer) }
    }

    /**
     * Jumps to exactly [micros], leaving the decoder with nothing stale in it.
     *
     * A backward seek lands on the index point at or before the target: a WebM cue, an fMP4
     * fragment, a FLAC seek point — seconds early, routinely. The engine counts
     * the position from the target it asked for, so audio from the landing point played under a
     * position (and lyrics) that far ahead of it, for the rest of the track. Decoding from the
     * landing point and dropping everything before the target is what Media3 does on the phone.
     */
    override fun seek(micros: Long): Boolean {
        val container = format ?: return false
        val context = codec ?: return false
        val target = ((micros + streamStartUs) / 1_000_000.0 / streamTimeBase).toLong()
        if (av_seek_frame(container, streamIndex, target, AVSEEK_FLAG_BACKWARD) < 0) return false
        avcodec_flush_buffers(context)
        drained = false
        positionUs = micros
        trimBeforeUs = micros.takeIf { it > 0 }
        return true
    }

    override fun close() {
        frame?.let { av_frame_free(it) }
        packet?.let { av_packet_free(it) }
        resampler?.let { swr_free(it) }
        codec?.let { avcodec_free_context(it) }
        format?.let { avformat_close_input(it) }
        outputBuffer?.deallocate()
        frame = null
        packet = null
        resampler = null
        codec = null
        format = null
        outputBuffer = null
        outputPlanes = null
        outputBufferSamples = 0
        measuredFormat = null
    }

    internal companion object {
        /** Further apart than any index point or cue lands before its target. */
        const val MAX_SEEK_TRIM_US = 30_000_000L
    }
}

/** How this format is spelled in FFmpeg's own vocabulary. */
internal fun DesktopPcmFormat.sampleFormat(): Int = when {
    isFloat -> AV_SAMPLE_FMT_FLT
    bytesPerSample >= 4 -> AV_SAMPLE_FMT_S32
    else -> AV_SAMPLE_FMT_S16
}
