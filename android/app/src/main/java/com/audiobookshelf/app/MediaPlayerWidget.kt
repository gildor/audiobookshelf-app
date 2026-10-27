package com.audiobookshelf.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.player.PlayerNotificationService
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.AppWidgetTarget
import com.bumptech.glide.request.transition.Transition

/**
 * Implementation of App Widget functionality.
 */
class MediaPlayerWidget : AppWidgetProvider() {
  companion object {
    const val ACTION_PLAY_PAUSE = BuildConfig.APPLICATION_ID + ".widget.PLAY_PAUSE"
    const val ACTION_FORWARD = BuildConfig.APPLICATION_ID + ".widget.FORWARD"
    const val ACTION_REWIND = BuildConfig.APPLICATION_ID + ".widget.REWIND"
  }

  val tag = "MediaPlayerWidget"
  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    DbManager.initialize(context)
    for (widgetId in appWidgetIds) {
      updateAppWidget(context, appWidgetManager, widgetId, DeviceManager.widgetPlaybackSession ?: DeviceManager.deviceData.lastPlaybackSession, DeviceManager.widgetIsPlaying, !DeviceManager.widgetIsPlaying)
    }
  }

  override fun onEnabled(context: Context) {
    Log.i(tag, "onEnabled check context ${context.packageName}")

    DbManager.initialize(context)

    DeviceManager.deviceData.lastPlaybackSession?.let {
      val appWidgetManager = AppWidgetManager.getInstance(context)
      val componentName = ComponentName(context, MediaPlayerWidget::class.java)
      val ids = appWidgetManager.getAppWidgetIds(componentName)
      Log.d(tag, "Setting initial widget state with last playback session ${it.displayTitle}")
      for (widgetId in ids) {
        updateAppWidget(context, appWidgetManager, widgetId, it, false, true)
      }
    }

    // Enter relevant functionality for when the first widget is created
    DeviceManager.initializeWidgetUpdater(context)
  }
}

internal fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, playbackSession: PlaybackSession?, isPlaying:Boolean, isAppClosed:Boolean) {
  val tag = "MediaPlayerWidget"
  val views = RemoteViews(context.packageName, R.layout.media_player_widget)
  Log.i(tag, "updateAppWidget ${playbackSession?.displayTitle ?: "No Title"} isPlaying=$isPlaying isAppClosed=$isAppClosed")
  val wholeWidgetClickPI = bookPendingIntent(context, playbackSession)

  val playPausePI = widgetControlPendingIntent(context, MediaPlayerWidget.ACTION_PLAY_PAUSE)
  views.setOnClickPendingIntent(R.id.widgetPlayPauseButton, playPausePI)

  val fastForwardPI = widgetControlPendingIntent(context, MediaPlayerWidget.ACTION_FORWARD)
  views.setOnClickPendingIntent(R.id.widgetFastForwardButton, fastForwardPI)

  val rewindPI = widgetControlPendingIntent(context, MediaPlayerWidget.ACTION_REWIND)
  views.setOnClickPendingIntent(R.id.widgetRewindButton, rewindPI)

  // Show/Hide button container
  views.setViewVisibility(R.id.widgetButtonContainer, if (playbackSession == null) View.GONE else View.VISIBLE)

  views.setOnClickPendingIntent(R.id.widgetBackground, wholeWidgetClickPI)

  val imageUri = playbackSession?.getCoverUri(context) ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/" + R.drawable.icon)
  val awt: AppWidgetTarget = object : AppWidgetTarget(context.applicationContext, R.id.widgetAlbumArt, views, appWidgetId) {
    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
      super.onResourceReady(resource, transition)
    }
  }

  val artist = playbackSession?.displayAuthor ?: "Unknown"
  views.setTextViewText(R.id.widgetArtistText, artist)

  val title = playbackSession?.displayTitle ?: "Unknown"
  views.setTextViewText(R.id.widgetMediaTitle, title)

  val options = RequestOptions().override(300, 300).placeholder(R.drawable.icon).error(R.drawable.icon)
  Glide.with(context.applicationContext).asBitmap().load(imageUri).apply(options).into(awt)

  Log.i(tag, "Update App Widget | Is Playing=$isPlaying | isAppClosed=$isAppClosed")

  val playPauseResource = if (isPlaying && !isAppClosed) androidx.mediarouter.R.drawable.ic_media_pause_dark else androidx.mediarouter.R.drawable.ic_media_play_dark
  views.setImageViewResource(R.id.widgetPlayPauseButton, playPauseResource)

  // Instruct the widget manager to update the widget
  appWidgetManager.updateAppWidget(appWidgetId, views)
}

internal fun bookPendingIntent(context: Context, session: PlaybackSession?): PendingIntent {
  val intent = Intent(context, MainActivity::class.java).apply {
    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
    if (session != null) {
      val uri = Uri.Builder().scheme("audiobookshelf").authority("widget")
      mapOf(
        "libraryItemId" to session.libraryItemId,
        "episodeId" to session.episodeId,
        "localLibraryItemId" to session.localLibraryItem?.id,
        "localEpisodeId" to session.localEpisodeId,
        "serverConnectionConfigId" to session.serverConnectionConfigId
      ).forEach { (key, value) ->
        if (!value.isNullOrEmpty()) uri.appendQueryParameter(key, value)
      }
      data = uri.build()
    }
  }
  return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

private fun widgetControlPendingIntent(context: Context, action: String): PendingIntent {
  val intent = Intent(context, PlayerNotificationService::class.java).setAction(action)
  val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    PendingIntent.getForegroundService(context, 0, intent, flags)
  } else {
    PendingIntent.getService(context, 0, intent, flags)
  }
}
