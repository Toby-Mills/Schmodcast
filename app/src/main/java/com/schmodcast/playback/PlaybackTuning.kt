package com.schmodcast.playback

import java.util.Locale

// Shared by the phone UI (QueueViewModel/QueueScreen) and PlaybackService's Android Auto
// custom session commands, so both surfaces skip/cycle speed by the same amounts instead of
// drifting apart.

const val SKIP_FORWARD_MS = 2 * 60 * 1000L
const val SKIP_BACK_MS = 30 * 1000L

val SPEED_OPTIONS = listOf(1f, 1.2f, 1.4f, 1.6f, 1.8f, 2f)

fun nextSpeed(current: Float): Float {
    val index = SPEED_OPTIONS.indexOf(current).coerceAtLeast(0)
    return SPEED_OPTIONS[(index + 1) % SPEED_OPTIONS.size]
}

fun formatSpeedLabel(speed: Float): String =
    String.format(Locale.US, "%.1fx", speed).replace(".0x", "x")
