package com.music.bitchord.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.music.bitchord.ui.components.revealOnAppear
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.ic_google_g
import com.music.bitchord.sharedui.resources.ic_logo
import org.jetbrains.compose.resources.painterResource
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.sonara.AnimatedSonaraMark
import com.music.bitchord.ui.sonara.FadingDotGrid
import com.music.bitchord.ui.sonara.MonoLabel
import com.music.bitchord.ui.sonara.SonaraColors
import com.music.bitchord.ui.sonara.SonaraPassCard
import com.music.bitchord.ui.sonara.PassAvatar
import androidx.compose.foundation.shape.RoundedCornerShape
import com.music.bitchord.ui.sonara.memberSinceLabel
import com.music.bitchord.ui.sonara.memberSinceMillis
import com.music.bitchord.ui.sonara.passIdFor
import androidx.compose.ui.platform.LocalContext

/**
 * The welcome: a title page, then a name for the listener's Sonara Pass (drawn live as they
 * type it), then the YouTube sign-in.
 *
 * Shown once — to new installs and, because the flag it sets is new, to anyone updating
 * from a build without it. The last page is skipped for an account that is already signed
 * in; asking somebody to connect what is connected reads as the app having forgotten them.
 *
 * Always drawn dark, whatever the theme: ink, a slow red ember and a grid of dots — the
 * Pass's own materials, so the card is at home on every page.
 */
@Composable
fun OnboardingScreen(
    signedIn: Boolean,
    initialName: String,
    /** Saves the party name. Called on Next, before the last page. */
    onNameChosen: (String) -> Unit,
    /** Opens the Google sign-in. The caller finishes onboarding once it lands. */
    onSignIn: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(PAGE_WELCOME) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val passMillis = remember { memberSinceMillis(context) }
    val passId = remember(passMillis) { passIdFor(passMillis) }
    val memberSince = remember(passMillis) { memberSinceLabel(passMillis) }

    BackHandler(enabled = page > PAGE_WELCOME) { page -= 1 }
    // "Sign in with Google" goes through the app's own sign-in WebView, raised
    // over this page; the account landing is what ends the welcome.
    LaunchedEffect(signedIn, page) {
        if (signedIn && page == PAGE_CONNECT) onFinish()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(SonaraColors.Ink)
            // Swallows every touch the pages don't, so nothing reaches the app
            // laid out underneath — and a tap on the backdrop puts the keyboard
            // away, which is where a thumb goes to dismiss one.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        WelcomeBackdrop(page)

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState > initialState
                val slide = tween<IntOffset>(460, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320, 100)))
                    .togetherWith(
                        slideOutHorizontally(slide) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)),
                    )
            },
            label = "onboardingPage",
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) { shown ->
            OnboardingPage {
                when (shown) {
                    PAGE_WELCOME -> WelcomePage(onNext = { page = PAGE_NAME })
                    PAGE_NAME -> NamePage(
                        name = name,
                        passId = passId,
                        memberSince = memberSince,
                        onNameChange = { name = it.take(NAME_LIMIT) },
                        onNext = next@{
                            val chosen = name.trim()
                            if (chosen.isEmpty()) return@next
                            focusManager.clearFocus()
                            onNameChosen(chosen)
                            if (signedIn) onFinish() else page = PAGE_CONNECT
                        },
                    )
                    else -> ConnectPage(
                        name = name.trim(),
                        passId = passId,
                        memberSince = memberSince,
                        onBack = { page = PAGE_NAME },
                        onSignIn = onSignIn,
                        onSkip = onFinish,
                    )
                }
            }
        }

        // Choose the look before anything else: the welcome itself is redrawn in it. Last in the
        // box, so it sits over the page's tap-anywhere and receives its own touches.
        com.music.bitchord.ui.theme.ThemeQuickSwitch(
            Modifier
                .align(Alignment.TopEnd)
                .systemBarsPadding()
                .padding(top = 8.dp, end = 16.dp),
        )
    }
}

/**
 * Ink, a grid of dots fading down the page, and an ember of the mark's red that drifts
 * slowly round the top of the screen and settles somewhere new on each page.
 */
@Composable
private fun WelcomeBackdrop(page: Int) {
    val drift = rememberInfiniteTransition(label = "ember")
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 14_000, easing = LinearEasing)),
        label = "ember-t",
    )
    val anchor by animateFloatAsState(
        targetValue = when (page) { PAGE_WELCOME -> 0.82f; PAGE_NAME -> 0.25f; else -> 0.7f },
        animationSpec = tween(900, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "ember-anchor",
    )
    val tokens = com.music.bitchord.ui.theme.LocalThemeTokens.current
    val ember = SonaraColors.Red
    val emberDeep = SonaraColors.Ember
    val ink = SonaraColors.Ink
    val hero = remember { com.music.bitchord.ui.theme.ThemeBackdropPainter() }
    if (tokens.motif == com.music.bitchord.ui.theme.ThemeMotif.NONE) {
        FadingDotGrid(Modifier.fillMaxSize(), color = Color.White.copy(alpha = 0.075f), fade = 0.9f)
    } else {
        // Vice's sunset or Doom's crest over the ink, as Home will show it.
        Canvas(Modifier.fillMaxSize()) { hero.draw(this, tokens, ink) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(
            size.width * (anchor + 0.08f * sin(t)),
            size.height * (0.16f + 0.05f * sin(t * 2f + 1f)),
        )
        val r = size.width * 0.95f
        drawCircle(
            Brush.radialGradient(
                listOf(ember.copy(alpha = 0.42f), emberDeep.copy(alpha = 0.22f), Color.Transparent),
                center = c,
                radius = r,
            ),
            radius = r,
            center = c,
        )
        // The page's foot sinks back to ink under the buttons.
        drawRect(
            Brush.verticalGradient(0.55f to Color.Transparent, 1f to ink.copy(alpha = 0.9f)),
        )
    }
}

/** The column every page is laid out in, capped so a tablet doesn't stretch it. */
@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp),
            content = content,
        )
    }
}

/**
 * The title page: the mark drawn large, and the app's name in type as big as the screen
 * allows. A tap anywhere moves on, as does the button.
 */
@Composable
private fun ColumnScope.WelcomePage(onNext: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onNext,
            ),
    ) {
        Spacer(Modifier.height(36.dp))
        MonoLabel(
            "Sonara · v${appVersion()}",
            color = Color.White.copy(alpha = 0.55f),
            size = 11.sp,
            modifier = Modifier.revealOnAppear(order = 0),
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AnimatedSonaraMark(Modifier.size(250.dp), startDelayMs = 420L)
        }
        Text(
            text = stringResource(R.string.onboarding_welcome_to),
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, letterSpacing = (-0.6).sp),
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.revealOnAppear(order = 1, rise = 18.dp),
        )
        Text(
            text = "Sonara",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 84.sp,
                lineHeight = 86.sp,
                letterSpacing = (-3.6).sp,
            ),
            color = Color.White,
            modifier = Modifier.revealOnAppear(order = 2, rise = 26.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_tagline),
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            color = Color.White.copy(alpha = 0.66f),
            modifier = Modifier.revealOnAppear(order = 3),
        )
        Spacer(Modifier.height(30.dp))
    }
    PillButton(
        label = stringResource(R.string.onboarding_get_started),
        onClick = onNext,
        primary = true,
    )
    Spacer(Modifier.height(12.dp))
    MonoLabel(
        stringResource(R.string.onboarding_tap_anywhere),
        color = Color.White.copy(alpha = 0.4f),
        size = 10.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(16.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.NamePage(
    name: String,
    passId: String,
    memberSince: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    // With the keyboard up there is room for the heading or the card at full size, not
    // both; the card is what the page is about, so it shrinks instead of going.
    val keyboardUp = WindowInsets.isImeVisible
    // The card gives a small twitch with each letter, as though it were being written on.
    val tilt = remember { Animatable(-3f) }
    LaunchedEffect(name.length) {
        tilt.animateTo(-3f + if (name.length % 2 == 0) 1.4f else -1.4f, spring(dampingRatio = 0.4f, stiffness = 900f))
        tilt.animateTo(-3f, spring(dampingRatio = 0.5f, stiffness = 300f))
    }
    Spacer(Modifier.height(if (keyboardUp) 4.dp else 48.dp))
    AnimatedVisibility(
        visible = !keyboardUp,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Title(stringResource(R.string.onboarding_pass_title))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_pass_body),
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 17.sp,
                color = Color.White.copy(alpha = 0.66f),
            )
        }
    }
    // With the keyboard up the card gives way to a strip of itself — the holder's initial,
    // name and pass number — which keeps answering each keystroke in the room left.
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (keyboardUp) {
            PassStrip(name = name.trim(), passId = passId)
        } else {
            SonaraPassCard(
                name = name.trim(),
                memberSince = memberSince,
                passId = passId,
                modifier = Modifier
                    .revealOnAppear(order = 1, rise = 40.dp)
                    .graphicsLayer { rotationZ = tilt.value },
            )
        }
    }

    Text(
        text = stringResource(R.string.onboarding_name_prompt),
        style = MaterialTheme.typography.titleMedium,
        fontSize = 17.sp,
        color = Color.White.copy(alpha = 0.78f),
        modifier = Modifier.padding(start = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    NameField(value = name, onValueChange = onNameChange, onDone = onNext)
    // The footnote steps aside while typing; the strip above has its room.
    if (!keyboardUp) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.onboarding_name_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    Spacer(Modifier.height(if (keyboardUp) 14.dp else 24.dp))
    PillButton(
        label = stringResource(R.string.onboarding_next),
        onClick = onNext,
        enabled = name.isNotBlank(),
        primary = true,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.ConnectPage(
    name: String,
    passId: String,
    memberSince: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSkip: () -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    BackButton(onBack)
    Spacer(Modifier.height(20.dp))
    if (name.isNotEmpty()) {
        MonoLabel(stringResource(R.string.onboarding_hello, name), color = SonaraColors.Coral, size = 12.sp)
        Spacer(Modifier.height(8.dp))
    }
    Title(stringResource(R.string.onboarding_connect_title))
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.onboarding_connect_body),
        style = MaterialTheme.typography.bodyLarge,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        color = Color.White.copy(alpha = 0.72f),
    )

    // The pass, finished, set down at an angle: the thing just made, kept in view.
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        SonaraPassCard(
            name = name,
            memberSince = memberSince,
            passId = passId,
            modifier = Modifier
                .revealOnAppear(order = 1, rise = 60.dp)
                .graphicsLayer {
                    rotationZ = 4f
                    scaleX = 0.86f
                    scaleY = 0.86f
                },
        )
    }

    PillButton(
        label = stringResource(R.string.onboarding_sign_in_google),
        onClick = onSignIn,
        primary = true,
        leading = {
            Image(
                painter = painterResource(Res.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
    )
    Spacer(Modifier.height(12.dp))
    PillButton(
        label = stringResource(R.string.onboarding_skip_sign_in),
        onClick = onSkip,
        primary = false,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun PassStrip(name: String, passId: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.horizontalGradient(listOf(SonaraColors.PassTop, SonaraColors.PassBottom)))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PassAvatar(name = name, url = null, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MonoLabel("Sonara Pass · $passId", color = Color.White.copy(alpha = 0.55f), size = 9.sp)
            Text(
                text = name.ifBlank { "Listener" },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
}

/** Replay's heading, scaled up: the one line each page exists to say. */
@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.2).sp,
        ),
        color = Color.White,
    )
}

/**
 * The name, set in the heading's own face and weight so what you type reads
 * as part of the page rather than a form on top of it.
 */
@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val style = MaterialTheme.typography.displayLarge.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_name_hint),
                style = style,
                color = Color.White.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A full-width capsule. Primary is white on the mesh, the way Apple Music's
 * own welcome sheet does it; secondary is the same shape in frosted white, so
 * the pair reads as a choice rather than a button and an afterthought.
 */
@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "pillScale")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(180), label = "pillAlpha")
    // Sonara's white pill; under Vice and Doom the primary is filled with the theme's accent in
    // its own shape (Doom's cut plate), with the theme's on-accent label.
    val theme = com.music.bitchord.ui.theme.LocalThemeTokens.current
    val themed = theme.id != com.music.bitchord.ui.theme.ThemeId.SONARA
    val shape = if (theme.angular) theme.controlShape else CircleShape
    val fill = when {
        !primary -> Color.White.copy(alpha = 0.16f)
        themed -> theme.accent
        else -> Color.White
    }
    val labelColor = when {
        !primary -> Color.White
        themed -> theme.onAccent
        else -> Color.Black
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(shape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = BitChordIcons.ChevronRight,
            contentDescription = stringResource(R.string.onboarding_back),
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { scaleX = -1f },
        )
    }
}

private const val PAGE_WELCOME = 0
private const val PAGE_NAME = 1
private const val PAGE_CONNECT = 2

/** Long enough for a full name; the party server keeps the first 80. */
private const val NAME_LIMIT = 40
