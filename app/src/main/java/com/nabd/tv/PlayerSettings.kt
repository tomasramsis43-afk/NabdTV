package com.nabd.tv

import androidx.annotation.StringRes

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/** Buffering profile. Larger profiles tolerate unstable networks at the cost of a slower start. */
enum class BufferProfile(@StringRes val labelRes: Int,@StringRes val detailRes: Int) {
    LowLatency(R.string.a_8edfd265, R.string.a_1433812e),
    Balanced(R.string.a_fc882c8d, R.string.a_80cea2af),
    HighStability(R.string.a_462d0865, R.string.a_95b8f0f6);

    val label: String get() = S(labelRes)
    val detail: String get() = S(detailRes)


    val key: String get() = name

    companion object {
        fun of(key: String?): BufferProfile = entries.firstOrNull { it.name == key } ?: Balanced
    }
}

/** Hardware / software decoder preference. ExoPlayer falls back automatically when a decoder is missing. */
enum class DecoderMode(@StringRes val labelRes: Int) {
    Auto(R.string.a_036b31dd),
    Hardware(R.string.a_616710a7),
    Software(R.string.a_3c0669dc);

    val label: String get() = S(labelRes)


    companion object {
        fun of(key: String?): DecoderMode = entries.firstOrNull { it.name == key } ?: Auto
    }
}

/** Video scaling mode used by the player surface. */
@OptIn(UnstableApi::class)
enum class AspectMode(@StringRes val labelRes: Int) {
    Fit(R.string.a_72c919d5),
    Smart(R.string.a_72429e55),
    Fill(R.string.a_57b70fa3),
    Stretch(R.string.a_77fa7c87),
    Ratio16x9(R.string.ratio_16_9),
    Ratio4x3(R.string.ratio_4_3),
    Crop(R.string.a_1afb1274);

    val label: String get() = S(labelRes)


    val resizeMode: Int
        get() = when (this) {
            Fill, Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

    /** When set, the video is placed inside a forced-ratio box (letterboxed) instead of the full screen. */
    val forcedRatio: Float?
        get() = when (this) {
            Ratio16x9 -> 16f / 9f
            Ratio4x3 -> 4f / 3f
            else -> null
        }

    companion object {
        fun of(key: String?): AspectMode = entries.firstOrNull { it.name == key } ?: Fit
    }
}

enum class ResumeMode(@StringRes val labelRes: Int) {
    Ask(R.string.a_e71cb0e5),
    Always(R.string.a_f4e927c4),
    Never(R.string.a_788a8260);

    val label: String get() = S(labelRes)


    companion object {
        fun of(key: String?): ResumeMode = entries.firstOrNull { it.name == key } ?: Always
    }
}

enum class OrientationPref(@StringRes val labelRes: Int) {
    Auto(R.string.a_a05c2504),
    Landscape(R.string.a_a7af94af),
    Portrait(R.string.a_41af045c);

    val label: String get() = S(labelRes)


    companion object {
        fun of(key: String?): OrientationPref = entries.firstOrNull { it.name == key } ?: Auto
    }
}

/** Up/Down behaviour on a TV remote while the player controls are hidden. */
enum class UpDownAction(@StringRes val labelRes: Int) {
    Volume(R.string.a_beb4436d),
    Menu(R.string.a_90ca90ee),
    Channels(R.string.a_b7e9d917);

    val label: String get() = S(labelRes)


    companion object {
        fun of(key: String?): UpDownAction = entries.firstOrNull { it.name == key } ?: Volume
    }
}

/** An external subtitle file copied into the app, plus the encoding used to decode it. */
data class ExternalSubtitle(val path: String, val name: String, val encoding: String = "auto")

/** All selectable speeds, from very slow to very fast. */
val PLAYER_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)

/** All selectable seek steps in seconds. */
val SEEK_INTERVALS = listOf(5, 10, 15, 30, 60)
