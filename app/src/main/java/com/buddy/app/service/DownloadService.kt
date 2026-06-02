package com.buddy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.buddy.app.data.BuddyModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var modelManager: BuddyModelManager

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1

        private val _downloadStatus = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
        val downloadStatus = _downloadStatus.asStateFlow()
    }

    sealed class DownloadStatus {
        object Idle : DownloadStatus()
        data class Progress(val progress: Int) : DownloadStatus()
        object Success : DownloadStatus()
        data class Error(val message: String) : DownloadStatus()
    }

    override fun onCreate() {
        super.onCreate()
        modelManager = BuddyModelManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        performDownload()
        return START_NOT_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun performDownload() {
        serviceScope.launch {
            _downloadStatus.value = DownloadStatus.Progress(0)
            
            val progressJob = launch {
                modelManager.downloadProgress.collect { progress ->
                    _downloadStatus.value = DownloadStatus.Progress(progress)
                    updateNotification(progress)
                }
            }

            val result = modelManager.downloadModel()
            progressJob.cancel()

            if (result.isSuccess) {
                _downloadStatus.value = DownloadStatus.Success
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _downloadStatus.value = DownloadStatus.Error(errorMsg)
                showErrorNotification(errorMsg)
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
    }

    private fun createNotification(progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Buddy is waking up")
            .setContentText("Downloading AI model... $progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Int) {
        val notification = createNotification(progress)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download failed")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
