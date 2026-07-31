package com.schmodcast.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.schmodcast.MainActivity
import com.schmodcast.R
import com.schmodcast.data.model.Episode
import com.schmodcast.playback.PlaybackService

// The widget never manages its own playback state - PlaybackService (the single source of truth
// for "what's playing", same as it already owns the persistent notification) pushes RemoteViews
// here via pushUpdate() whenever the current episode or play state changes. onUpdate only covers
// the system-triggered cases (widget just added/resized, device rebooted) where no live state
// exists yet, so it renders a placeholder rather than trying to start/bind the service itself -
// a BroadcastReceiver's onUpdate must return quickly and isn't a good fit for that.
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = placeholderViews(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    private fun placeholderViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
            setImageViewResource(R.id.widget_play_pause, R.drawable.ic_play)
            setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
            // Unlike the other placeholder buttons, play/pause must reach PlaybackService (not
            // just open the app): this is the cold-start path (widget just added, or the process
            // died since - no pushUpdate() has ever fired) that pendingAutoPlayOnLoad in
            // PlaybackService.onStartCommand exists to handle, loading whichever episode was last
            // in progress and starting it. Wiring this to openAppIntent instead silently made the
            // button a no-op for playback.
            setOnClickPendingIntent(
                R.id.widget_play_pause,
                actionIntent(context, PlaybackService.ACTION_PLAY_PAUSE, requestCode = 1),
            )
            setOnClickPendingIntent(R.id.widget_skip_back, openAppIntent(context))
            setOnClickPendingIntent(R.id.widget_skip_forward, openAppIntent(context))
        }

    companion object {
        private fun componentName(context: Context) = ComponentName(context, NowPlayingWidgetProvider::class.java)

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(componentName(context)).isNotEmpty()

        // Sole caller is PlaybackService, whenever currentEpisodeId or play state changes -
        // mirroring how it's also the sole owner of the persistent notification. `artwork` is
        // null until PlaybackService's own fetch/cache completes; the next call (once it
        // resolves) fills it in, so there's no need to block this call on the network fetch.
        fun pushUpdate(context: Context, episode: Episode?, isPlaying: Boolean, artwork: Bitmap?) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(componentName(context))
            if (ids.isEmpty()) return

            val views = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(
                    R.id.widget_title,
                    episode?.title ?: context.getString(R.string.app_name),
                )
                if (artwork != null) {
                    setImageViewBitmap(R.id.widget_artwork, artwork)
                } else {
                    setImageViewResource(R.id.widget_artwork, R.mipmap.ic_launcher)
                }
                setImageViewResource(R.id.widget_play_pause, if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                setContentDescription(R.id.widget_play_pause, if (isPlaying) "Pause" else "Play")

                val openApp = openAppIntent(context)
                setOnClickPendingIntent(R.id.widget_root, openApp)
                setOnClickPendingIntent(
                    R.id.widget_play_pause,
                    actionIntent(context, PlaybackService.ACTION_PLAY_PAUSE, requestCode = 1),
                )
                setOnClickPendingIntent(
                    R.id.widget_skip_back,
                    actionIntent(context, PlaybackService.ACTION_SKIP_BACK, requestCode = 2),
                )
                setOnClickPendingIntent(
                    R.id.widget_skip_forward,
                    actionIntent(context, PlaybackService.ACTION_SKIP_FORWARD, requestCode = 3),
                )
            }

            ids.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
        }

        // Tapping anywhere outside the transport buttons (or the placeholder state before any
        // episode has ever loaded) reopens the app - MainActivity is singleTask (see manifest),
        // same as the persistent notification's own tap behavior.
        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )

        // Must be getForegroundService, not getService: when PlaybackService isn't already
        // running (cold start - the exact case this button now has to handle), a widget tap only
        // carries the OS's temporary exemption to start a *foreground* service. A plain
        // getService PendingIntent gets silently rejected by ActivityManager in that state
        // ("Background start not allowed ... startFg?=false", confirmed via logcat) - onCreate
        // never even runs, so nothing happens. PlaybackService is already manifest-declared for
        // this (foregroundServiceType="mediaPlayback" + FOREGROUND_SERVICE_MEDIA_PLAYBACK) and
        // Media3 promotes it to the foreground state itself as soon as player.play()/prepare()
        // runs, well inside the window the OS allows after a foreground-service-start intent.
        // Each button gets its own requestCode so their PendingIntents don't collide.
        private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                requestCode,
                Intent(context, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
