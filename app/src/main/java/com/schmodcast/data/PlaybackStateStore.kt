package com.schmodcast.data

import android.content.Context

private const val PREFS_NAME = "playback_state"
private const val KEY_CURRENT_EPISODE_ID = "current_episode_id"

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
}
