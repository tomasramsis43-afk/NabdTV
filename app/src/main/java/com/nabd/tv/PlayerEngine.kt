@file:kotlin.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nabd.tv

import android.content.Context
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.nio.ByteBuffer
import kotlin.math.abs

/** Global audio sync offset, read by every player's audio processor. */
object AudioDelayBus {
    @Volatile var ms: Int = 0
}

/**
 * Shifts PCM audio by a configurable number of milliseconds.
 * Positive values insert silence (audio becomes later); negative values drop frames (audio becomes earlier).
 * A new value is applied once, at the next audio buffer, and again after every seek/flush.
 */
@UnstableApi
class AudioDelayProcessor : BaseAudioProcessor() {
    private var sampleRate = 48_000
    private var bytesPerFrame = 4
    private var appliedMs = 0
    private var logged = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        appliedMs = AudioDelayBus.ms
        logged = false
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val target = AudioDelayBus.ms
        val deltaMs = target - appliedMs
        if (deltaMs != 0) {
            appliedMs = target
            val frames = (abs(deltaMs).toLong() * sampleRate / 1000L).toInt()
            val bytes = (frames.toLong() * bytesPerFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (deltaMs > 0 && bytes > 0) {
                // Insert silence before this buffer.
                val remaining = inputBuffer.remaining()
                val out = replaceOutputBuffer(bytes + remaining)
                repeat(bytes) { out.put(0) }
                out.put(inputBuffer)
                out.flip()
                inputBuffer.position(inputBuffer.limit())
                return
            } else if (deltaMs < 0 && bytes > 0) {
                // Drop the first frames of this buffer.
                val drop = (bytes / bytesPerFrame * bytesPerFrame).coerceAtMost(inputBuffer.remaining())
                inputBuffer.position(inputBuffer.position() + drop)
                appliedMs = target
            }
        }
        if (!inputBuffer.hasRemaining()) {
            if (!logged) { logged = true }
            replaceOutputBuffer(0)
            return
        }
        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        appliedMs = AudioDelayBus.ms
    }
}

/** Decoder preference: software decoders are moved to the front; the default list is kept as a safe fallback. */
@UnstableApi
class PreferSoftwareCodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): List<MediaCodecInfo> {
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (all.size < 2) return all
        return all.sortedBy { if (it.hardwareAccelerated) 1 else 0 }
    }
}

/**
 * Renderers with the optional audio delay processor and a decoder preference.
 * Everything else is Media3's default (hardware decoding first, decoder fallback enabled).
 */
@UnstableApi
class NabdRenderersFactory(
    context: Context,
    decoder: DecoderMode,
    private val delay: AudioDelayProcessor,
    softAudio: Boolean = false
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
        if (decoder == DecoderMode.Software) setMediaCodecSelector(PreferSoftwareCodecSelector())
    }
}

/**
 * Builds the ExoPlayer used by the player screen and by multi-view.
 * The player is reused for every episode so switching titles never recreates the engine.
 */
@UnstableApi
fun buildPlayer(ctx: Context, store: Store, live: Boolean): ExoPlayer {
    val profile = BufferProfile.of(store.bufferProfile)
    val (connect, read) = when (profile) {
        BufferProfile.LowLatency -> 8_000 to 12_000
        BufferProfile.Balanced -> 12_000 to 20_000
        BufferProfile.HighStability -> 20_000 to 35_000
    }
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setUserAgent(httpUa)
        .setConnectTimeoutMs(connect)
        .setReadTimeoutMs(read)
        .setKeepPostFor302Redirects(true)

    // Live: small buffers for fast channel changes. VOD: room for smooth seeking. The profile scales both.
    val (minBuf, maxBuf, playbackBuf, rebufferBuf) = when (profile) {
        BufferProfile.LowLatency ->
            if (live) longArrayOf(2_000, 20_000, 800, 1_200) else longArrayOf(8_000, 40_000, 1_000, 2_000)
        BufferProfile.Balanced ->
            if (live) longArrayOf(4_000, 40_000, 1_500, 3_000) else longArrayOf(15_000, 90_000, 2_500, 5_000)
        BufferProfile.HighStability ->
            if (live) longArrayOf(8_000, 90_000, 2_500, 5_000) else longArrayOf(30_000, 240_000, 5_000, 10_000)
    }
    val load = DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBuf.toInt(), maxBuf.toInt(), playbackBuf.toInt(), rebufferBuf.toInt())
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    AudioDelayBus.ms = store.audioDelayMs
    val delay = AudioDelayProcessor()
    val renderers = NabdRenderersFactory(ctx, DecoderMode.of(store.decoderMode), delay, store.softAudio)

    val builder = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http)))
        .setLoadControl(load)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(store.seekInterval * 1000L)
        .setSeekForwardIncrementMs(store.seekInterval * 1000L)
    val p = builder.build()
    p.setWakeMode(C.WAKE_MODE_NETWORK)
    p.setAudioAttributes(
        androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true
    )
    val subOn = store.subEnabled
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(store.prefAudioLang)
        .setPreferredTextLanguage(store.prefSubLang)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subOn)
        .build()
    runCatching { p.setPlaybackSpeed(store.defaultSpeed) }
    runCatching {
        p.setVideoChangeFrameRateStrategy(
            if (store.afrMode == "off") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        )
    }
    return p
}

/**
 * Frame-rate matching for TVs whose system "match content frame rate" is off: picks the display mode (same resolution)
 * whose refresh rate is a clean multiple of the video's frame rate (23.976 fps → 24/48 Hz, 25 → 50, 29.97 → 59.94 …).
 */
@UnstableApi
fun matchDisplayRefreshRate(act: android.app.Activity, player: ExoPlayer) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: return
    val display = (if (Build.VERSION.SDK_INT >= 30) act.display else legacyDisplay(act)) ?: return
    val cur = display.mode
    val best = display.supportedModes
        .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        .mapNotNull { m ->
            val ratio = m.refreshRate / fps
            val n = Math.round(ratio)
            if (n >= 1 && abs(ratio - n) < 0.02f) Triple(m, n, abs(ratio - n)) else null
        }
        .minWithOrNull(compareBy<Triple<android.view.Display.Mode, Int, Float>>({ it.second }, { it.third }))
        ?.first ?: return
    if (best.modeId == cur.modeId) return
    val w = act.window
    w.attributes = w.attributes.also { it.preferredDisplayModeId = best.modeId }
}

/** Gives the display mode back to the system (called when the player closes). */
fun resetDisplayMode(act: android.app.Activity) {
    val w = act.window
    if (w.attributes.preferredDisplayModeId != 0) w.attributes = w.attributes.also { it.preferredDisplayModeId = 0 }
}

@Suppress("DEPRECATION")
private fun legacyDisplay(act: android.app.Activity): android.view.Display? = act.windowManager.defaultDisplay

/** Content-type hints for URLs that do not carry a useful file extension. */
fun mediaItemFor(url: String): MediaItem {
    val clean = url.substringBefore('?').lowercase()
    val mime = when {
        clean.endsWith(".m3u8") || clean.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        else -> null
    }
    return MediaItem.Builder().setUri(url).apply { mime?.let { setMimeType(it) } }.build()
}

/* ------------------------------------------------------------------ */
/* Track helpers                                                       */
/* ------------------------------------------------------------------ */

data class PlayerTrack(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val language: String,
    val mime: String,
    val channels: Int,
    val sampleRate: Int,
    val bitrate: Int,
    val selected: Boolean
)

fun ExoPlayer.audioTracks(): List<PlayerTrack> = tracksOf(C.TRACK_TYPE_AUDIO)
fun ExoPlayer.textTracks(): List<PlayerTrack> = tracksOf(C.TRACK_TYPE_TEXT)

private fun ExoPlayer.tracksOf(type: Int): List<PlayerTrack> =
    currentTracks.groups.filter { it.type == type && it.length > 0 }.flatMap { group ->
        (0 until group.length).mapNotNull { i ->
            runCatching {
                val f = group.getTrackFormat(i)
                PlayerTrack(
                    group = group,
                    index = i,
                    label = trackLabel(f, i),
                    language = f.language ?: "",
                    mime = f.sampleMimeType ?: f.codecs ?: "",
                    channels = f.channelCount,
                    sampleRate = f.sampleRate,
                    bitrate = f.bitrate,
                    selected = group.isTrackSelected(i)
                )
            }.getOrNull()
        }
    }

private fun trackLabel(f: Format, index: Int): String {
    val lang = languageName(f.language)
    val extras = ArrayList<String>()
    if (!lang.isNullOrBlank()) extras += lang
    if (f.label != null && f.label != lang) extras += f.label!!
    if (f.channelCount > 0) extras += if (f.channelCount == 1) S(R.string.a_42e49f89) else S(R.string.x_channels_n, f.channelCount)
    if (f.sampleRate > 0) extras += "${f.sampleRate / 1000} kHz"
    if (f.bitrate > 0) extras += "${f.bitrate / 1000} kbps"
    if (extras.isEmpty()) extras += S(R.string.x_track_n, index + 1)
    return extras.joinToString(" · ")
}

fun languageName(code: String?): String? {
    if (code.isNullOrBlank()) return null
    val c = code.trim().lowercase()
    return when (c) {
        "ar", "ara" -> S(R.string.a_9970632f)
        "en", "eng" -> S(R.string.a_10c4fe32)
        "fr", "fra", "fre" -> S(R.string.a_3c43e065)
        "tr", "tur" -> S(R.string.a_3c806cbc)
        "es", "spa" -> S(R.string.a_af7387d1)
        "de", "deu", "ger" -> S(R.string.a_a21349c4)
        "it", "ita" -> S(R.string.a_1848f666)
        "ru", "rus" -> S(R.string.a_22a237c7)
        "hi", "hin" -> S(R.string.a_cdba8a77)
        "ur", "urd" -> S(R.string.a_54d58b07)
        "fa", "fas", "per" -> S(R.string.a_1cd4382e)
        "ku", "kur" -> S(R.string.a_63f834ac)
        "sv" -> S(R.string.a_af29ee5d)
        "nl" -> S(R.string.a_4a1b8ff8)
        "pt" -> S(R.string.a_67944c77)
        "ja" -> S(R.string.a_85a0d36c)
        "ko" -> S(R.string.a_7655ccb9)
        "zh" -> S(R.string.a_00dc9c86)
        "und" -> S(R.string.a_3bba7511)
        else -> c
    }
}

fun ExoPlayer.selectTrack(type: Int, track: PlayerTrack) {
    runCatching {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
            .build()
    }
}

/** Removes a manual override and lets the language preference decide again. */
fun ExoPlayer.clearTrackOverride(type: Int) {
    runCatching {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .build()
    }
}

fun ExoPlayer.setSubtitlesEnabled(enabled: Boolean) {
    runCatching {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }
}

/* ------------------------------------------------------------------ */
/* Diagnostics                                                         */
/* ------------------------------------------------------------------ */

fun hdrLabel(f: Format?): String {
    val ci = f?.colorInfo ?: return "SDR"
    val transfer = ci.colorTransfer
    return when (transfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> if (ci.colorSpace == C.COLOR_SPACE_BT2020) "Wide Color (BT.2020)" else "SDR"
    }
}

fun playbackStats(p: ExoPlayer): String {
    val v = p.videoFormat
    val a = p.audioFormat
    val kbps = (v?.bitrate ?: -1).takeIf { it > 0 }?.div(1000) ?: 0
    val abr = (a?.bitrate ?: -1).takeIf { it > 0 }?.div(1000) ?: 0
    val fps = v?.frameRate?.takeIf { it > 0f }?.let { String.format("%.2f", it) } ?: "-"
    val dropped = runCatching { p.videoDecoderCounters?.droppedBufferCount ?: 0 }.getOrDefault(0)
    val decoded = runCatching { p.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }.getOrDefault(0)
    val audioTracks = p.audioTracks()
    val textTracks = p.textTracks()
    return buildString {
        append("${v?.width ?: 0}×${v?.height ?: 0} · $fps fps · $kbps kbps\n")
        append(S(R.string.x_st_video, v?.codecs ?: "-", hdrLabel(v)))
        append(S(R.string.x_st_audio, a?.codecs ?: "-", a?.channelCount ?: 0, a?.sampleRate?.div(1000) ?: 0, abr))
        append(S(R.string.x_st_tracks, audioTracks.size, textTracks.size))
        append(S(R.string.x_st_buffer, p.totalBufferedDuration / 1000, S(if (p.isPlaying) R.string.x_playing else R.string.x_paused), p.playbackParameters.speed))
        append(S(R.string.x_st_frames, decoded, dropped))
        if (Build.VERSION.SDK_INT >= 29) append(S(R.string.x_st_live, if (p.isCurrentMediaItemLive) S(R.string.x_live_word) else "VOD", AudioDelayBus.ms))
    }
}
