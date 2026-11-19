package com.music.bitchord.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.modes.ActiveModes
import com.music.bitchord.data.modes.DataSaverSetting
import com.music.bitchord.data.modes.LowPowerSetting
import com.music.bitchord.data.modes.ModeController
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.screens.batteryUnrestricted

/** One mode as the button and the panel show it: its glyph and tint, in a fixed order. */
private enum class IndicatedMode(val icon: ImageVector, val tint: Color) {
    DATA_SAVER(Icons.Rounded.DataSaverOn, Color(0xFF2FA8FF)),
    LOW_POWER(Icons.Rounded.BatterySaver, Color(0xFFFFB800)),
    HIGH_PERFORMANCE(Icons.Rounded.Speed, Color(0xFFFF5A4E)),
}

private fun ActiveModes.indicated(): List<IndicatedMode> = buildList {
    if (dataSaver != null) add(IndicatedMode.DATA_SAVER)
    if (lowPower != null) add(IndicatedMode.LOW_POWER)
    if (highPerformance) add(IndicatedMode.HIGH_PERFORMANCE)
}

/**
 * The modes button in the top bar: the glyph of each mode in effect, grouped in one pill, or a
 * plain gauge when none is. Tapping it opens [ModesPanel] — three tiles that switch the modes
 * on and off, with a word on each. The tuning lives on the Performance settings page.
 */
@Composable
fun ModeIndicator(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val active by ModeController.active.collectAsStateWithLifecycle()
    val shown = active.indicated()
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val label = stringResource(R.string.modes_title)

    Row(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable {
                haptics.play(Haptic.Select)
                panelOpen = true
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "modeGlyphs",
        ) { modes ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (modes.isEmpty()) {
                    // Nothing on: the gauge, quiet, so the modes are always one tap away.
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.size(17.dp),
                    )
                } else {
                    for (mode in modes) {
                        Icon(mode.icon, contentDescription = null, tint = mode.tint, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }

    if (panelOpen) {
        ModesPanel(
            onDismiss = { panelOpen = false },
            onOpenSettings = {
                panelOpen = false
                onOpenSettings()
            },
        )
    }
}

/** The window blurs what is behind it, where the device and the listener's settings allow. */
private fun crossWindowBlur(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true

/** Three tiles, one per mode: tap to switch it. A word under each; the rest is in Settings. */
@Composable
private fun ModesPanel(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val active by ModeController.active.collectAsStateWithLifecycle()
    val prefs by ModeController.preferences.collectAsStateWithLifecycle()
    val highPerformance by AppSettings.highPerformanceMode.collectAsStateWithLifecycle()
    val reduceBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    var batteryWarning by rememberSaveable { mutableStateOf(false) }
    // Back from the system's battery page: turn High Performance on if the listener did set
    // Unrestricted there — that is what they went there for.
    val batterySettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (batteryUnrestricted(context)) AppSettings.setHighPerformanceMode(true)
    }
    val glass = !reduceBlur && remember { crossWindowBlur(context) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Frosted glass: the page behind is blurred by the system compositor, and the card
        // over it is a thin tint, not a slab. Without blur (Reduce blur, Low Power, an older or
        // blur-less device) it is a solid card over a dimmer scrim instead.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val blurPx = with(LocalDensity.current) { 42.dp.roundToPx() }
        SideEffect {
            window ?: return@SideEffect
            if (glass) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.also { it.blurBehindRadius = blurPx }
                window.setDimAmount(if (dark) 0.22f else 0.12f)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setDimAmount(0.45f)
            }
        }
        val cardColor = if (glass) {
            (if (dark) Color(0xFF1C1C1E) else Color(0xFFF7F7F9)).copy(alpha = if (dark) 0.62f else 0.72f)
        } else {
            MaterialTheme.colorScheme.surface
        }
        val shape = RoundedCornerShape(30.dp)
        Column(
            modifier = Modifier
                .width(328.dp)
                .clip(shape)
                .background(cardColor)
                .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
                .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dataSaverOn = active.dataSaver != null
                ModeTile(
                    mode = IndicatedMode.DATA_SAVER,
                    on = dataSaverOn,
                    name = stringResource(R.string.data_saver),
                    note = if (prefs.dataSaver == DataSaverSetting.CELLULAR_ONLY) {
                        stringResource(R.string.mode_state_mobile)
                    } else {
                        stringResource(R.string.mode_hint_data_saver)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    haptics.play(Haptic.Select)
                    ModeController.update { it.copy(dataSaver = if (dataSaverOn) DataSaverSetting.OFF else DataSaverSetting.ON) }
                }

                val lowPowerOn = active.lowPower != null
                ModeTile(
                    mode = IndicatedMode.LOW_POWER,
                    on = lowPowerOn,
                    name = stringResource(R.string.mode_tile_low_power),
                    note = if (prefs.lowPower == LowPowerSetting.AUTO) {
                        stringResource(R.string.mode_state_auto)
                    } else {
                        stringResource(R.string.mode_hint_low_power)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    haptics.play(Haptic.Select)
                    when {
                        // Automatic and on right now: off until the next charge, still automatic.
                        lowPowerOn && prefs.lowPower == LowPowerSetting.AUTO -> ModeController.snoozeLowPower()
                        lowPowerOn -> ModeController.update { it.copy(lowPower = LowPowerSetting.OFF) }
                        else -> ModeController.update { it.copy(lowPower = LowPowerSetting.ON) }
                    }
                }

                ModeTile(
                    mode = IndicatedMode.HIGH_PERFORMANCE,
                    on = active.highPerformance,
                    // Asked for but held by Low Power: shown half-lit, and says so.
                    paused = active.highPerformanceSuspended,
                    name = stringResource(R.string.mode_tile_high_performance),
                    note = if (active.highPerformanceSuspended) {
                        stringResource(R.string.mode_state_paused)
                    } else {
                        stringResource(R.string.mode_hint_high_performance)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    haptics.play(Haptic.Select)
                    when {
                        highPerformance -> AppSettings.setHighPerformanceMode(false)
                        batteryUnrestricted(context) -> AppSettings.setHighPerformanceMode(true)
                        else -> batteryWarning = true
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mode_open_settings),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.W500),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }

    if (batteryWarning) {
        AlertDialog(
            onDismissRequest = { batteryWarning = false },
            title = { Text(stringResource(R.string.high_performance_before_enabling)) },
            text = { Text(stringResource(R.string.high_performance_battery_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    batteryWarning = false
                    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", context.packageName, null))
                    val intent = details.takeIf { it.resolveActivity(context.packageManager) != null }
                        ?: Intent(Settings.ACTION_SETTINGS)
                    batterySettings.launch(intent)
                }) { Text(stringResource(R.string.open_battery_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { batteryWarning = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** A round glass button that fills with the mode's colour while it is on, its name and a word under it. */
@Composable
private fun ModeTile(
    mode: IndicatedMode,
    on: Boolean,
    name: String,
    note: String,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    onClick: () -> Unit,
) {
    val fill by animateColorAsState(
        targetValue = when {
            on -> mode.tint
            paused -> mode.tint.copy(alpha = 0.28f)
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        },
        animationSpec = tween(200),
        label = "modeTileFill",
    )
    val glyph by animateColorAsState(
        targetValue = if (on) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        animationSpec = tween(200),
        label = "modeTileGlyph",
    )
    val state = stringResource(if (on) R.string.mode_status_on else R.string.mode_status_off)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Switch, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mode.icon, contentDescription = null, tint = glyph, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.W600),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 14.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
