package com.asoraksh.hermeseditor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-screen Help overlay that keeps the editor composed underneath so scroll and selection survive. */
@Composable
internal fun HelpPage(isRtl: Boolean, onClose: () -> Unit, onReplayIntro: () -> Unit) {
    val titles = stringArrayResource(R.array.help_titles)
    val bodies = stringArrayResource(R.array.help_bodies)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ControlButton(ControlIcon.Back, stringResource(R.string.back), onClick = onClose)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.help), style = MaterialTheme.typography.titleLarge)
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = onReplayIntro,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) { Text(stringResource(R.string.onboarding_replay)) }
                    HorizontalDivider()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
                        titles.forEachIndexed { i, title ->
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text(bodies.getOrElse(i) { "" }, fontSize = 16.sp, lineHeight = 25.sp)
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OnboardingLanguageDialog(onLanguage: (String) -> Unit) {
    Dialog(onDismissRequest = { }) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Choose language / زبان را انتخاب کنید",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onLanguage("en") }) { Text("English") }
                    Button(onClick = { onLanguage("fa") }) { Text("فارسی") }
                }
            }
        }
    }
}

@Composable
internal fun OnboardingGuide(
    language: String,
    step: Int,
    onBack: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    val safeStep = step.coerceIn(0, 3)
    val title = when (safeStep) {
        0 -> stringResource(R.string.onboarding_new_title)
        1 -> stringResource(R.string.onboarding_save_title)
        2 -> stringResource(R.string.onboarding_history_title)
        else -> stringResource(R.string.onboarding_zoom_title)
    }
    val body = when (safeStep) {
        0 -> stringResource(R.string.onboarding_new_body)
        1 -> stringResource(R.string.onboarding_save_body)
        2 -> stringResource(R.string.onboarding_history_body)
        else -> stringResource(R.string.onboarding_zoom_body)
    }
    val progress = if (language == "fa") {
        listOf("۱ از ۴", "۲ از ۴", "۳ از ۴", "۴ از ۴")[safeStep]
    } else {
        "${safeStep + 1} / 4"
    }
    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides if (language == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, top = 120.dp, end = 24.dp, bottom = 270.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.onboarding_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                progress,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(body, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (safeStep > 0) {
                                TextButton(onClick = onBack) { Text(stringResource(R.string.onboarding_back)) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = onNext) {
                                Text(
                                    stringResource(
                                        if (safeStep == 3) R.string.onboarding_done
                                        else R.string.onboarding_next,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
