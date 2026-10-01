package io.github.bloodbear111.motogps.ui

import android.app.Application
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import io.github.bloodbear111.motogps.ble.BlePermissions
import io.github.bloodbear111.motogps.ble.MotoBleCentral
import io.github.bloodbear111.motogps.ble.MotoBleConnectionState
import io.github.bloodbear111.motogps.ble.MotoBleDiscovery
import io.github.bloodbear111.motogps.ble.MotoBleScanner
import io.github.bloodbear111.motogps.gateway.GatewayConfiguration
import io.github.bloodbear111.motogps.gateway.GatewayFailure
import io.github.bloodbear111.motogps.gateway.GatewayPlace
import io.github.bloodbear111.motogps.gateway.GatewayResult
import io.github.bloodbear111.motogps.gateway.MotoGatewayClient
import io.github.bloodbear111.motogps.navigation.AndroidLocationSource
import io.github.bloodbear111.motogps.navigation.AmapLocationSettings
import io.github.bloodbear111.motogps.navigation.AmapLocationSource
import io.github.bloodbear111.motogps.navigation.LocationSourceRouter
import io.github.bloodbear111.motogps.navigation.MotoNavCore
import io.github.bloodbear111.motogps.navigation.NavCommandExecutor
import io.github.bloodbear111.motogps.navigation.NavigationSession
import io.github.bloodbear111.motogps.navigation.Wgs84Point
import io.github.bloodbear111.motogps.protocol.GoldenCheckResult
import io.github.bloodbear111.motogps.protocol.MotoNativeLibrary
import io.github.bloodbear111.motogps.protocol.MotoProtocolCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot of everything the connect/self-test/settings screens need. */
data class SelfTestReport(
    val running: Boolean = false,
    val nativeAvailable: Boolean = true,
    val checks: List<GoldenCheckResult> = emptyList(),
    val error: String? = null,
) {
    val passedCount: Int get() = checks.count { it.passed }
    val failedCount: Int get() = checks.count { !it.passed }
}

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = MotoBleScanner(application)
    private val central = MotoBleCentral(application, viewModelScope)

    val connectionState: StateFlow<MotoBleConnectionState> = central.state
    val frameSize: StateFlow<Int> = central.frameSize

    private val _discoveries = MutableStateFlow<List<MotoBleDiscovery>>(emptyList())
    val discoveries: StateFlow<List<MotoBleDiscovery>> = _discoveries.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _selfTest = MutableStateFlow(SelfTestReport())
    val selfTest: StateFlow<SelfTestReport> = _selfTest.asStateFlow()

    private val _gatewayAddress = MutableStateFlow<String?>(null)
    val gatewayAddress: StateFlow<String?> = _gatewayAddress.asStateFlow()

    // --- Navigation session -------------------------------------------------
    // The shared C++ NavCore owns navigation state; the session is the only
    // thing that feeds it fixes and mirrors its snapshot to the display.
    private val navCore = MotoNavCore()
    private val gatewayClient = MotoGatewayClient()
    private val navExecutor = NavCommandExecutor(
        gateway = gatewayClient,
        gatewayBaseUrl = { _gatewayAddress.value },
        clock = { SystemClock.elapsedRealtime() },
    )
    /**
     * Which providers were subscribed and which answered; bring-up visibility.
     * Kept as a list because only the newest line is useless: the interesting
     * part is the set of lastKnown entries together.
     */
    private val _locationEvents = MutableStateFlow<List<String>>(emptyList())
    val locationEvents: StateFlow<List<String>> = _locationEvents.asStateFlow()

    /** AMap Android key, shown masked; the key itself never leaves the device. */
    private val _amapKeyLabel = MutableStateFlow(AmapLocationSettings.describeKey(null))
    val amapKeyLabel: StateFlow<String> = _amapKeyLabel.asStateFlow()

    private val _amapConsent = MutableStateFlow(false)
    val amapConsent: StateFlow<Boolean> = _amapConsent.asStateFlow()

    private fun recordLocationEvent(event: String) {
        // Six lines was not enough to see a whole bring-up: the permission and
        // app-op lines pushed the provider answers off the card.
        _locationEvents.value = (_locationEvents.value + event).takeLast(20)
    }

    private val navSession = NavigationSession(
        core = navCore,
        executor = navExecutor,
        location = LocationSourceRouter(
            platform = AndroidLocationSource(
                context = application,
                onEvent = ::recordLocationEvent,
            ),
            // Read at collection time, not at construction: storing a key or
            // giving consent then only needs a restart of navigation (or the
            // "重新定位" button) rather than a reinstall.
            amap = {
                val key = AmapLocationSettings.loadKey(application)
                if (key != null && AmapLocationSettings.isConsentGiven(application)) {
                    AmapLocationSource(
                        context = application,
                        apiKey = key,
                        onEvent = ::recordLocationEvent,
                    )
                } else {
                    null
                }
            },
            onEvent = ::recordLocationEvent,
        ),
        display = central,
        scope = viewModelScope,
    )

    val navigation: StateFlow<NavigationSession.Snapshot> = navSession.state

    private val _places = MutableStateFlow<List<GatewayPlace>>(emptyList())
    val places: StateFlow<List<GatewayPlace>> = _places.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _destinationError = MutableStateFlow<String?>(null)
    val destinationError: StateFlow<String?> = _destinationError.asStateFlow()

    /**
     * True when the last gateway call failed at the transport layer.
     *
     * "gateway unreachable" is almost never the server: it is a phone or router
     * still holding the previous IP for the gateway hostname after the server
     * moved. The resolved address is already in the message; this flag adds the
     * one action that clears it, on the screen where the failure appears.
     */
    private val _gatewayUnreachable = MutableStateFlow(false)
    val gatewayUnreachable: StateFlow<Boolean> = _gatewayUnreachable.asStateFlow()

    /** Searches POIs through the configured gateway. Nothing is cached or faked. */
    fun searchDestination(keywords: String) {
        if (_searching.value) return
        _searching.value = true
        _destinationError.value = null
        _gatewayUnreachable.value = false
        viewModelScope.launch {
            when (val result = gatewayClient.searchPlaces(_gatewayAddress.value, keywords)) {
                is GatewayResult.Success -> _places.value = result.value
                is GatewayResult.Failure -> {
                    _places.value = emptyList()
                    _destinationError.value = result.failure.message ?: "search failed"
                    _gatewayUnreachable.value = result.failure is GatewayFailure.Transport
                }
            }
            _searching.value = false
        }
    }

    /**
     * Starts navigating to [place]. The core plans the route; the session
     * executes the request and starts feeding fixes.
     */
    fun startNavigation(place: GatewayPlace) {
        navSession.start(
            destination = Wgs84Point(
                longitudeDeg = place.location.longitudeDeg,
                latitudeDeg = place.location.latitudeDeg,
            ),
            label = place.name,
        )
    }

    fun stopNavigation() {
        navSession.stop()
    }

    /** Re-registers the location client and clears the diagnostics for a fresh read. */
    fun restartLocation() {
        _locationEvents.value = emptyList()
        navSession.restartLocation()
    }

    private var scanJob: Job? = null

    init {
        _gatewayAddress.value = GatewayConfiguration.resolve(
            stored = readStoredGateway(application),
            bundledDefault = BuildConfigGatewayDefault,
        )
        AmapLocationSettings.loadKey(application)?.let {
            _amapKeyLabel.value = AmapLocationSettings.describeKey(it)
        }
        _amapConsent.value = AmapLocationSettings.isConsentGiven(application)
    }

    /**
     * Stores the AMap **Android platform** key.
     *
     * @return an error message to show, or null when the key was accepted.
     */
    fun saveAmapApiKey(input: String): String? = try {
        val key = AmapLocationSettings.normalizeKey(input)
        AmapLocationSettings.saveKey(getApplication(), key)
        _amapKeyLabel.value = AmapLocationSettings.describeKey(key)
        null
    } catch (error: AmapLocationSettings.KeyError) {
        error.message
    }

    /**
     * Records the rider's answer about sending the position to AMap. The SDK is
     * only initialised after this is true.
     */
    fun setAmapConsent(agreed: Boolean) {
        AmapLocationSettings.setConsent(getApplication(), agreed)
        _amapConsent.value = agreed
    }

    /**
     * Scanning needs BLUETOOTH_SCAN, and reading a device name additionally needs
     * BLUETOOTH_CONNECT. Both are checked through [BlePermissions] and a revoked
     * permission is caught as a `SecurityException` below, so the app reports a
     * clear error instead of crashing mid-scan.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val context = getApplication<Application>()
        if (!BlePermissions.hasScanPermission(context)) {
            _scanError.value = "缺少蓝牙扫描权限"
            return
        }
        if (!BlePermissions.isBluetoothEnabled(context)) {
            _scanError.value = "蓝牙未开启"
            return
        }
        if (scanJob?.isActive == true) return

        _discoveries.value = emptyList()
        _scanError.value = null
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                scanner.scan().collect { discovery ->
                    val current = _discoveries.value.toMutableList()
                    val index = current.indexOfFirst { it.address == discovery.address }
                    if (index >= 0) current[index] = discovery else current.add(discovery)
                    _discoveries.value = current.sortedByDescending { it.rssi }
                }
            } catch (error: SecurityException) {
                _scanError.value = "蓝牙权限已被撤销，请重新授权"
            } catch (error: CancellationException) {
                // stopScan() cancels this job - including from connect(), which
                // stops scanning before it connects. Cancellation is not a
                // failure, so rethrow it; showing it left a live-looking
                // "StandaloneCoroutine was cancelled" under the device list.
                throw error
            } catch (error: Throwable) {
                _scanError.value = error.message ?: "扫描失败"
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connect(discovery: MotoBleDiscovery) {
        stopScan()
        val context = getApplication<Application>()
        if (!BlePermissions.hasScanPermission(context)) {
            _scanError.value = "缺少蓝牙权限，无法连接圆屏"
            return
        }
        try {
            central.connect(discovery.device)
        } catch (error: SecurityException) {
            _scanError.value = "蓝牙权限已被撤销，请重新授权"
        }
    }

    /** Disconnecting also needs BLUETOOTH_CONNECT; a revoked grant is reported. */
    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            central.disconnect("用户主动断开")
        } catch (error: SecurityException) {
            _scanError.value = "蓝牙权限已被撤销，请重新授权"
        }
    }

    /**
     * Runs the golden-vector self-check against the shared C++ codec. Must not
     * touch the JNI layer on the main thread, hence the dispatcher hop.
     */
    fun runSelfTest() {
        if (_selfTest.value.running) return
        _selfTest.value = SelfTestReport(running = true)
        viewModelScope.launch {
            val report = withContext(Dispatchers.Default) {
                if (!MotoNativeLibrary.isLoaded) {
                    SelfTestReport(
                        running = false,
                        nativeAvailable = false,
                        error = MotoNativeLibrary.loadFailure?.message,
                    )
                } else {
                    try {
                        MotoProtocolCodec().use { codec ->
                            SelfTestReport(running = false, checks = codec.runGoldenSelfTest())
                        }
                    } catch (error: Throwable) {
                        SelfTestReport(
                            running = false,
                            error = error.message ?: error::class.java.simpleName,
                        )
                    }
                }
            }
            _selfTest.value = report
        }
    }

    fun saveGatewayAddress(input: String): String? {
        return try {
            val normalized = GatewayConfiguration.normalize(input)
            getApplication<Application>()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit {
                    putString(GatewayConfiguration.PREFERENCES_KEY, normalized)
                }
            _gatewayAddress.value = normalized
            null
        } catch (error: GatewayConfiguration.AddressError) {
            error.message
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        navSession.stop()
        navCore.close()
        central.close()
        super.onCleared()
    }

    private fun readStoredGateway(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(GatewayConfiguration.PREFERENCES_KEY, null)

    private companion object {
        const val PREFERENCES = "moto_gps"

        /**
         * No gateway ships with the app: the project does not operate a public
         * navigation service, so an unconfigured install must say so rather than
         * silently pointing at someone else's host.
         */
        val BuildConfigGatewayDefault: String? = null
    }
}
