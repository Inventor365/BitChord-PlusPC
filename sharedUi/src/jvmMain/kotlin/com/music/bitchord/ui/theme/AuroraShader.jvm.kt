package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.Brush

/** No runtime shader here: the pre-rendered aurora frames are drawn instead. Winter is phone-only today. */
actual class AuroraShader actual constructor() {
    actual val supported: Boolean get() = false

    actual fun brush(width: Float, height: Float, timeSec: Float, day: Boolean): Brush? = null
}
