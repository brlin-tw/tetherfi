package com.pyamsoft.tetherfi.server.lock

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.pyamsoft.pydroid.core.Enforcer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.tetherfi.server.ServerPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
internal class LockerImpl
@Inject
internal constructor(
    context: Context,
    private val preferences: ServerPreferences,
) : Locker {

  private val wakeLock by lazy {
    val powerManager = context.getSystemService<PowerManager>().requireNotNull()
    return@lazy powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
  }

  private val mutex = Mutex()
  private var acquired = false

  @SuppressLint("WakelockTimeout")
  private suspend fun acquireWakelock() {
    mutex.withLock {
      if (!acquired) {
        Timber.d("Acquire CPU wakelock: $WAKE_LOCK_TAG")
        wakeLock.acquire()
        acquired = true
      }
    }
  }

  private suspend fun releaseWakelock() {
    mutex.withLock {
      if (acquired) {
        Timber.d("Release CPU wakelock: $WAKE_LOCK_TAG")
        wakeLock.release()
        acquired = false
      }
    }
  }

  override suspend fun acquire() =
      withContext(context = Dispatchers.IO + NonCancellable) {
        Enforcer.assertOffMainThread()

        releaseWakelock()

        if (preferences.keepWakeLock()) {
          acquireWakelock()
        }
      }

  override suspend fun release() =
      withContext(context = Dispatchers.IO + NonCancellable) {
        Enforcer.assertOffMainThread()

        releaseWakelock()
      }

  companion object {
    private const val WAKE_LOCK_TAG = "com.pyamsoft.tetherfi:PROXY_WAKE_LOCK"
  }
}