package com.audiobookshelf.app

import android.app.Application
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.player.PlayerNotificationListener
import com.audiobookshelf.app.player.PlayerNotificationService
import io.paperdb.Paper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [WidgetPlaybackTest.NoKeystore::class])
class WidgetPlaybackTest {
  // Local widget playback doesn't use credentials. Robolectric has no AndroidKeyStore.
  @Implements(SecureStorage::class, isInAndroidSdk = false)
  class NoKeystore {
    @Implementation fun __constructor__(context: Context) {}
  }
  private lateinit var app: Application

  @Before fun setup() {
    app = RuntimeEnvironment.getApplication()
    Settings.Secure.putString(app.contentResolver, Settings.Secure.ANDROID_ID, "test-device")
    DbManager.initialize(app)
    DeviceManager.deviceData.lastPlaybackSession = null
    DeviceManager.serverConnectionConfig = null
    Paper.book("localLibraryItems").destroy()
    Paper.book("localMediaProgress").destroy()
    PlayerNotificationListener.isForegroundService = false
    PlayerNotificationService.isStarted = false
  }

  @After fun cleanup() {
    Paper.book("localLibraryItems").destroy()
    Paper.book("localMediaProgress").destroy()
    DeviceManager.deviceData.lastPlaybackSession = null
  }

  private fun downloadedBook(): LocalLibraryItem {
    val file = File(app.filesDir, "test.wav")
    val sampleCount = 8000 * 600
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("RIFF".toByteArray()); putInt(36 + sampleCount); put("WAVEfmt ".toByteArray())
      putInt(16); putShort(1); putShort(1); putInt(8000); putInt(8000)
      putShort(1); putShort(8); put("data".toByteArray()); putInt(sampleCount)
    }.array()
    file.outputStream().use { it.write(header); it.write(ByteArray(sampleCount) { 128.toByte() }) }
    val metadata = BookMetadata("Remember me", null, null, null, mutableListOf(), null, null, null, null, null, null, null, false, "Author", null, null, null, null)
    val track = AudioTrack(1, 0.0, 600.0, "Track", Uri.fromFile(file).toString(), "audio/wav", FileMetadata(file.name, "wav", file.path, file.name, file.length()), true, "audio", null)
    val book = Book(metadata, null, emptyList(), null, emptyList(), mutableListOf(track), null, file.length(), 600.0, 1)
    return LocalLibraryItem("local_book", "folder", file.parent!!, file.parent!!, Uri.fromFile(file.parentFile).toString(), false, "book", book, mutableListOf(), null, null, true, null, null, null, null)
  }

  private fun savedSession(): PlaybackSession {
    val item = downloadedBook()
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    return item.getPlaybackSession(null, DeviceInfo("device", "test", "phone", 28, "test")).apply {
      currentTime = 125.0
      updatedAt = System.currentTimeMillis()
    }
  }

  @Test fun bookTapCarriesTheSavedBookAndEpisodeAcrossProcessRecreation() {
    val session = savedSession().apply { localEpisodeId = "local_episode" }
    DeviceManager.setLastPlaybackSession(session)
    val persisted = DbManager().getDeviceData().lastPlaybackSession!!
    val intent = shadowOf(bookPendingIntent(app, persisted)).savedIntent
    assertEquals(MainActivity::class.java.name, intent.component!!.className)
    assertEquals("widget", intent.data!!.host)
    assertEquals("local_book", intent.data!!.getQueryParameter("localLibraryItemId"))
    assertEquals("local_episode", intent.data!!.getQueryParameter("localEpisodeId"))
  }

  @Test fun bookTapOmitsMissingEpisodeIdsInsteadOfEncodingTheWordNull() {
    val intent = shadowOf(bookPendingIntent(app, savedSession())).savedIntent
    assertEquals("local_book", intent.data!!.getQueryParameter("localLibraryItemId"))
    assertNull(intent.data!!.getQueryParameter("localEpisodeId"))
    assertNull(intent.data!!.getQueryParameter("episodeId"))
    assertNull(intent.data!!.getQueryParameter("libraryItemId"))
  }

  @Test fun coldWidgetPlayRestoresTheSavedBookAndPositionWithoutAnActivity() {
    DeviceManager.setLastPlaybackSession(savedSession())
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    try {
      service.onStartCommand(Intent(app, PlayerNotificationService::class.java).setAction(MediaPlayerWidget.ACTION_PLAY_PAUSE), 0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("local_book", service.currentPlaybackSession?.localLibraryItemId)
      assertEquals(125.0, service.currentPlaybackSession!!.currentTime, 0.01)
      assertTrue(service.currentPlayer.playWhenReady)
      assertEquals(1, service.currentPlayer.mediaItemCount)
    } finally {
      controller.destroy()
    }
  }

  @Test fun coldWidgetWithNoSavedBookStopsCleanlyAndPreservesNoForegroundNotification() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    try {
      controller.get().onStartCommand(Intent(app, PlayerNotificationService::class.java).setAction(MediaPlayerWidget.ACTION_PLAY_PAUSE), 0, 1)
      assertTrue(shadowOf(controller.get()).isStoppedBySelf)
      assertFalse(PlayerNotificationListener.isForegroundService)
    } finally {
      controller.destroy()
    }
  }

  @Test fun coldWidgetForwardRestoresPausedAndSeeksFromTheSavedPosition() {
    DeviceManager.setLastPlaybackSession(savedSession())
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    try {
      service.onStartCommand(Intent(app, PlayerNotificationService::class.java).setAction(MediaPlayerWidget.ACTION_FORWARD), 0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertFalse(service.currentPlayer.playWhenReady)
      assertEquals(135.0, service.getCurrentTimeSeconds(), 0.01)
      assertEquals(135.0, DeviceManager.deviceData.lastPlaybackSession!!.currentTime, 0.01)
    } finally {
      controller.destroy()
    }
    assertEquals(135.0, DbManager().getDeviceData().lastPlaybackSession!!.currentTime, 0.01)
  }

  @Test fun deletedDownloadDoesNotStartAnUnrelatedBookOrForgetTheLastBook() {
    val session = savedSession()
    DeviceManager.setLastPlaybackSession(session)
    File(session.audioTracks[0].metadata!!.path).delete()
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    try {
      controller.get().onStartCommand(Intent(app, PlayerNotificationService::class.java).setAction(MediaPlayerWidget.ACTION_PLAY_PAUSE), 0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertNull(controller.get().currentPlaybackSession)
      assertEquals("local_book", DeviceManager.deviceData.lastPlaybackSession!!.localLibraryItemId)
      assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    } finally {
      controller.destroy()
    }
  }

  @Test fun coldWidgetRestoresTheExactPodcastEpisode() {
    val item = downloadedBook()
    val track = item.media.getAudioTracks()[0]
    val first = PodcastEpisode("local_first", 1, null, null, "First episode", null, null, null, null, null, track, emptyList(), 600.0, null, null, null)
    val second = first.copy(id = "local_second", index = 2, title = "Second episode")
    item.mediaType = "podcast"
    item.media = Podcast(PodcastMetadata("Podcast", "Author", null, mutableListOf(), false), null, mutableListOf(), mutableListOf(first, second), false, 2)
    DeviceManager.dbManager.saveLocalLibraryItem(item)
    val session = item.getPlaybackSession(second, DeviceInfo("device", "test", "phone", 28, "test")).apply {
      currentTime = 45.0
      updatedAt = System.currentTimeMillis()
    }
    DeviceManager.setLastPlaybackSession(session)
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    try {
      controller.get().onStartCommand(Intent(app, PlayerNotificationService::class.java).setAction(MediaPlayerWidget.ACTION_PLAY_PAUSE), 0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("local_second", controller.get().currentPlaybackSession?.localEpisodeId)
      assertEquals("Second episode", controller.get().currentPlaybackSession?.displayTitle)
      assertEquals(45.0, controller.get().currentPlaybackSession!!.currentTime, 0.01)
    } finally {
      controller.destroy()
    }
  }
}
