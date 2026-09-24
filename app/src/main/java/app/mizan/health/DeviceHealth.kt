package app.mizan.health

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.mizan.domain.model.HealthStatus
import app.mizan.domain.model.SystemHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

class DeviceHealth(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow(readNetwork())
    val networkStatus: StateFlow<HealthStatus> = _network.asStateFlow()

    init {
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish()
                override fun onLost(network: Network) {
                    _network.value = HealthStatus.UNAVAILABLE
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = publish()
            })
        } catch (_: RuntimeException) {
            _network.value = HealthStatus.UNKNOWN
        }
    }

    fun refresh() = publish()

    fun network(): HealthStatus = _network.value

    /**
     * Network is observed. Everything else stays unknown until a real check
     * reports it. A saved token is not proof the service is healthy now.
     */
    fun snapshot(signedIn: Boolean, simulation: Boolean): SystemHealth = SystemHealth(
        network = network(),
        backend = HealthStatus.UNKNOWN,
        authentication = if (signedIn) HealthStatus.HEALTHY else HealthStatus.UNKNOWN,
        erp = if (simulation) HealthStatus.DEGRADED else HealthStatus.UNKNOWN,
        synchronization = HealthStatus.UNKNOWN,
        policy = if (simulation) HealthStatus.DEGRADED else HealthStatus.UNKNOWN,
        execution = HealthStatus.UNKNOWN,
        checkedAt = Instant.now(),
    )

    private fun publish() {
        _network.value = readNetwork()
    }

    private fun readNetwork(): HealthStatus {
        val network = connectivity.activeNetwork ?: return HealthStatus.UNAVAILABLE
        val caps = connectivity.getNetworkCapabilities(network) ?: return HealthStatus.UNAVAILABLE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return HealthStatus.UNAVAILABLE
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (validated) HealthStatus.HEALTHY else HealthStatus.DEGRADED
    }
}
