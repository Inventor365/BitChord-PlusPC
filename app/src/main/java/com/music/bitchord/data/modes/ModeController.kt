package com.music.bitchord.data.modes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.data.model.ArtworkSizing
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.ui.performance.RefreshRateRuntime
import com.music.bitchord.ui.player.MusicVideoEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The single source of truth for Data Saver, Low Power Mode and High Performance: the listener's
 * [preferences], the device's state, which modes are [active], and the [policy] they resolve to
 * (see [ModePolicy]). Applies that policy to every lever — video, refresh rate, audio ceiling,
 * effects, artwork size — and exposes it for the levers that read it on demand (prefetch,
 * background sync, update downloads, Automix lookahead).
 *
 * Modes never write the listener's other settings; each lever takes an override that disappears
 * when the mode does.
 */
object ModeController {
    private lateinit var appContext: Context
    private lateinit var store: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _preferences = MutableStateFlow(ModePreferences())
    val preferences: StateFlow<ModePreferences> = _preferences.asStateFlow()

    private val _device = MutableStateFlow(DeviceState())
    val device: StateFlow<DeviceState> = _device.asStateFlow()

    private val _active = MutableStateFlow(ActiveModes())
    val active: StateFlow<ActiveModes> = _active.asStateFlow()

    private val _policy = MutableStateFlow(EffectivePolicy.Unrestricted)
    val policy: StateFlow<EffectivePolicy> = _policy.asStateFlow()

    private var lowPowerWasOn = false
    private var lowPowerSnoozed = false

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = appContext.getSharedPreferences("sonara_modes", Context.MODE_PRIVATE)
        _preferences.value = load()
        watchDevice()
        scope.launch {
            // High Performance keeps its switch (and stored value) in AppSettings.
            combine(_preferences, _device, AppSettings.highPerformanceMode, AppSettings.meteredConnection) { prefs, device, high, metered ->
                Triple(prefs.copy(highPerformance = high), device.copy(metered = metered), Unit)
            }.collect { (prefs, device, _) -> recompute(prefs, device) }
        }
    }

    /** Changes the listener's mode settings (and saves them). */
    fun update(transform: (ModePreferences) -> ModePreferences) {
        val next = transform(_preferences.value)
        if (next.lowPower != LowPowerSetting.AUTO) lowPowerSnoozed = false
        _preferences.value = next
        save(next)
    }

    /**
     * Turns automatic Low Power off until the phone is charged or the battery recovers — the
     * indicator's "Turn off for now". A manual ON is changed in Settings instead.
     */
    fun snoozeLowPower() {
        lowPowerSnoozed = true
        recompute(_preferences.value.copy(highPerformance = AppSettings.highPerformanceMode.value), _device.value)
    }

    private fun recompute(prefs: ModePreferences, device: DeviceState) {
        // A snooze lasts until the phone is plugged in or the battery is clearly back.
        if (lowPowerSnoozed && (device.charging ||
                (device.batteryPercent ?: 0) > prefs.lowPowerBatteryThreshold + ModePolicy.BATTERY_HYSTERESIS && !device.systemPowerSave)
        ) {
            lowPowerSnoozed = false
        }
        val active = ModePolicy.active(prefs, device, lowPowerWasOn, lowPowerSnoozed)
        lowPowerWasOn = active.lowPower == ModeTrigger.BATTERY_LOW
        val policy = ModePolicy.resolve(
            prefs,
            active,
            reasonDataSaver = appContext.getString(R.string.data_saver),
            reasonLowPower = appContext.getString(R.string.low_power_mode),
        )
        _active.value = active
        if (_policy.value != policy) {
            _policy.value = policy
            apply(policy)
        }
    }

    /** Pushes the policy to the levers that hold state of their own. */
    private fun apply(policy: EffectivePolicy) {
        MusicVideoEngine.setOverride(policy.video)
        RefreshRateRuntime.setOverride(policy.refresh)
        AppSettings.modeAudioCeiling.value = policy.audioCap
        AppSettings.setModeEffects(policy.effects, policy.animatedCovers)
        ArtworkSizing.maxPx = if (policy.smallArtwork) SMALL_ARTWORK_PX else null
    }

    private fun watchDevice() {
        val battery = ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = readBattery(intent)
            },
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        battery?.let(::readBattery)
        val power = appContext.getSystemService(PowerManager::class.java)
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        fun readSystemModes() {
            _device.value = _device.value.copy(
                systemPowerSave = power?.isPowerSaveMode == true,
                systemDataSaver = connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            )
        }
        readSystemModes()
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
        }
        ContextCompat.registerReceiver(
            appContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = readSystemModes()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        // "On charge" means plugged in: a full battery on the charger counts, and the status
        // field can lag the plug (and stays CHARGING when a test unplugs it with dumpsys).
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        _device.value = _device.value.copy(batteryPercent = percent, charging = charging)
    }

    private fun load(): ModePreferences = ModePreferences(
        dataSaver = enumOr(store.getString(KEY_DATA_SAVER, null), DataSaverSetting.OFF),
        dataSaverAudioCap = enumOr(store.getString(KEY_DS_AUDIO, null), AudioQuality.MEDIUM),
        dataSaverVideo = enumOr(store.getString(KEY_DS_VIDEO, null), ModeVideo.BANNER),
        lowPower = enumOr(store.getString(KEY_LOW_POWER, null), LowPowerSetting.OFF),
        lowPowerFollowsSystem = store.getBoolean(KEY_LP_SYSTEM, true),
        lowPowerBatteryThreshold = store.getInt(KEY_LP_THRESHOLD, 20),
        lowPowerVideo = enumOr(store.getString(KEY_LP_VIDEO, null), ModeVideo.BANNER),
    )

    private fun save(p: ModePreferences) {
        store.edit()
            .putString(KEY_DATA_SAVER, p.dataSaver.name)
            .putString(KEY_DS_AUDIO, p.dataSaverAudioCap.name)
            .putString(KEY_DS_VIDEO, p.dataSaverVideo.name)
            .putString(KEY_LOW_POWER, p.lowPower.name)
            .putBoolean(KEY_LP_SYSTEM, p.lowPowerFollowsSystem)
            .putInt(KEY_LP_THRESHOLD, p.lowPowerBatteryThreshold)
            .putString(KEY_LP_VIDEO, p.lowPowerVideo.name)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    /** Artwork size under Data Saver: sharp at list and card sizes, a third of the bytes of a full cover. */
    private const val SMALL_ARTWORK_PX = 480

    private const val KEY_DATA_SAVER = "data_saver"
    private const val KEY_DS_AUDIO = "data_saver_audio_cap"
    private const val KEY_DS_VIDEO = "data_saver_video"
    private const val KEY_LOW_POWER = "low_power"
    private const val KEY_LP_SYSTEM = "low_power_follows_system"
    private const val KEY_LP_THRESHOLD = "low_power_battery_threshold"
    private const val KEY_LP_VIDEO = "low_power_video"
}
