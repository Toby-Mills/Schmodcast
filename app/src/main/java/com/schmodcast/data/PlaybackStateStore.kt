package com.schmodcast.data

import android.content.Context

private const val PREFS_NAME = "playback_state"
private const val KEY_CURRENT_EPISODE_ID = "current_episode_id"
private const val KEY_PLAYBACK_SPEED = "playback_speed"
private const val DEFAULT_PLAYBACK_SPEED = 1f

// Tracks which episode was last loaded into the player, independent of the queue's contents,
// so the app can resume the same episode (via EpisodeEntity.lastPositionMs) on next launch
// instead of always restarting from the queue head.
class PlaybackStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentEpisodeId: String?
        get() = prefs.getString(KEY_CURRENT_EPISODE_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CURRENT_EPISODE_ID, value).apply()
        }

    // Speed is set on the single ExoPlayer instance from either surface (the phone UI's
    // SpeedCycleButton via MediaController, or Android Auto's CUSTOM_COMMAND_CYCLE_SPEED acting
    // on the player directly) - PlaybackService persists it here on every change so a fresh
    // ExoPlayer (new process/service instance) restores it instead of always starting at 1x.
    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)
        set(value) {
            prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply()
        }
}
