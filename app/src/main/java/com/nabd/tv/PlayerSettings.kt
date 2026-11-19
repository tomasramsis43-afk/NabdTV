package com.nabd.tv

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout

/** Buffering profile. Larger profiles tolerate unstable networks at the cost of a slower start. */
enum class BufferProfile(val label: String, val detail: String) {
    LowLatency("زمن استجابة منخفض", "بدء سريع وتنقّل فوري — مناسب للشبكات الثابتة"),
    Balanced("متوازن", "بدء سريع مع مخزن كافٍ لتجنّب التقطيع"),
    HighStability("استقرار عالٍ", "مخزن أكبر للشبكات البطيئة أو المتقطعة");

    val key: String get() = name

    companion object {
        fun of(key: String?): BufferProfile = entries.firstOrNull { it.name == key } ?: Balanced
    }
}

/** Hardware / software decoder preference. ExoPlayer falls back automatically when a decoder is missing. */
enum class DecoderMode(val label: String) {
    Auto("تلقائي (الأفضل)"),
    Hardware("عتاد (HW)"),
    Software("برمجي (SW)");

    companion object {
        fun of(key: String?): DecoderMode = entries.firstOrNull { it.name == key } ?: Auto
    }
}

/** Video scaling mode used by the player surface. */
@OptIn(UnstableApi::class)
enum class AspectMode(val label: String) {
    Fit("ملاءمة (النسبة الأصلية)"),
    Smart("ذكي (ملء آمن)"),
    Fill("ملء الشاشة (قص الحواف)"),
    Stretch("تمديد"),
    Ratio16x9("16:9"),
    Ratio4x3("4:3"),
    Crop("قص وتكبير");

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

enum class ResumeMode(val label: String) {
    Ask("اسألني قبل الاستكمال"),
    Always("استكمل دائماً"),
    Never("ابدأ من البداية دائماً");

    companion object {
        fun of(key: String?): ResumeMode = entries.firstOrNull { it.name == key } ?: Always
    }
}

enum class OrientationPref(val label: String) {
    Auto("تلقائي (حسب الجهاز)"),
    Landscape("أفقي"),
    Portrait("عمودي");

    companion object {
        fun of(key: String?): OrientationPref = entries.firstOrNull { it.name == key } ?: Auto
    }
}

/** Up/Down behaviour on a TV remote while the player controls are hidden. */
enum class UpDownAction(val label: String) {
    Volume("الصوت"),
    Menu("قائمة المشغّل"),
    Channels("التنقل بين القنوات");

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
