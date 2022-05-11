package com.pyamsoft.widefi.server.proxy

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.bus.EventBus
import com.pyamsoft.widefi.server.BaseServer
import com.pyamsoft.widefi.server.ServerDefaults
import com.pyamsoft.widefi.server.ServerInternalApi
import com.pyamsoft.widefi.server.ServerPreferences
import com.pyamsoft.widefi.server.event.ConnectionEvent
import com.pyamsoft.widefi.server.event.ErrorEvent
import com.pyamsoft.widefi.server.proxy.connector.ProxyManager
import com.pyamsoft.widefi.server.status.RunningStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
internal class WifiSharedProxy
@Inject
internal constructor(
    private val preferences: ServerPreferences,
    @ServerInternalApi private val dispatcher: CoroutineDispatcher,
    @ServerInternalApi private val errorBus: EventBus<ErrorEvent>,
    @ServerInternalApi private val connectionBus: EventBus<ConnectionEvent>,
    @ServerInternalApi private val factory: ProxyManager.Factory,
    status: ProxyStatus,
) : BaseServer(status), SharedProxy {

  private val mutex = Mutex()
  private val jobs = mutableListOf<ProxyJob>()

  /** We own our own scope here because the proxy lifespan is separate */
  private val scope by lazy { CoroutineScope(context = dispatcher) }

  /** Get the port for the proxy */
  @CheckResult
  private suspend fun getPort(): Int =
      withContext(context = dispatcher) {
        return@withContext preferences.getPort()
      }

  @CheckResult
  private fun CoroutineScope.proxyLoop(type: SharedProxy.Type, port: Int): ProxyJob {
    val tcp = factory.create(type = type, port = port)

    Timber.d("${type.name} Begin proxy server loop")
    val job = launch(context = dispatcher) { tcp.loop() }
    return ProxyJob(type = type, job = job)
  }

  private suspend fun shutdown() {
    clearJobs()

    // Clear busses
    errorBus.send(ErrorEvent.Clear)
    connectionBus.send(ConnectionEvent.Clear)
  }

  private suspend fun clearJobs() {
    Timber.d("Cancelling jobs")
    mutex.withLock { jobs.removeEach { it.job.cancel() } }
  }

  override fun start() {
    scope.launch(context = dispatcher) {
      shutdown()

      val port = getPort()
      if (port > 65000 || port < 1024) {
        Timber.w("Port is invalid: $port")
        status.set(RunningStatus.Error(message = "Port is invalid: $port"))
        return@launch
      }

      status.set(RunningStatus.Starting)

      coroutineScope {
        val tcp = proxyLoop(type = SharedProxy.Type.TCP, port = port)
        val udp = proxyLoop(type = SharedProxy.Type.UDP, port = port)

        mutex.withLock {
          jobs.add(tcp)
          jobs.add(udp)
        }

        Timber.d("Started proxy server on port: $port")
        status.set(RunningStatus.Running)
      }
    }
  }

  override fun stop() {
    scope.launch(context = dispatcher) {
      Timber.d("Stopping proxy server")

      status.set(RunningStatus.Stopping)

      shutdown()

      status.set(RunningStatus.NotRunning)
    }
  }

  override suspend fun onErrorEvent(block: (ErrorEvent) -> Unit) {
    return errorBus.onEvent { block(it) }
  }

  override suspend fun onConnectionEvent(block: (ConnectionEvent) -> Unit) {
    return connectionBus.onEvent { block(it) }
  }

  private inline fun <T> MutableList<T>.removeEach(block: (T) -> Unit) {
    while (this.isNotEmpty()) {
      block(this.removeFirst())
    }
  }

  private data class ProxyJob(
      val type: SharedProxy.Type,
      val job: Job,
  )
}
