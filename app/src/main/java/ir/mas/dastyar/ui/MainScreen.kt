package ir.mas.dastyar.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.mas.dastyar.R
import ir.mas.dastyar.core.ConversationViewModel
import ir.mas.dastyar.core.UiState

/**
 * صفحه اصلی: تک‌دکمه بزرگ میکروفون + یک خط وضعیت. عمداً هیچ عنصر دیگری
 * روی صفحه نیست تا برای فرد کم‌بینا کاملاً ساده و بدون ابهام باشد.
 *
 * در این نسخه (۰.۲) یک بخش عیب‌یابی هم اضافه شده: چون همه بازخوردهای اپ
 * صوتی است، اگر موتور صدای گوشی کار نکند کاربر هیچ نشانه‌ای نمی‌بیند.
 * این بخش در نسخه نهایی حذف می‌شود.
 *
 * جهت متن همیشه راست‌به‌چپ (فارسی) در نظر گرفته شده، صرف‌نظر از locale
 * سیستم، چون کل تجربه اپ فارسی است.
 */
@Composable
fun MainScreen(viewModel: ConversationViewModel) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val state by viewModel.state.collectAsState()
        val logLines by viewModel.log.collectAsState()
        val ttsPersian by viewModel.ttsPersian.collectAsState()
        var typed by remember { mutableStateOf("") }
        val context = LocalContext.current

        LaunchedEffect(Unit) {
            viewModel.refreshDiagnostics()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // هشدار حیاتی: بدون موتور صدای فارسی، اپ کاملاً بی‌صدا است و
            // کاربر نابینا هیچ بازخوردی نمی‌گیرد.
            if (ttsPersian == false) {
                Text(
                    text = "روی این گوشی هیچ موتور صدای فارسی نصب نیست، برای همین اپ " +
                        "نمی‌تواند حرف بزند. یک موتور گفتار با پشتیبانی فارسی " +
                        "(مثل «eSpeak NG») نصب کنید و در تنظیمات، آن را به‌عنوان " +
                        "موتور پیش‌فرض انتخاب کنید.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                ) {
                    Text(text = "باز کردن تنظیمات گفتار", style = MaterialTheme.typography.labelLarge)
                }
            }

            MicButton(
                state = state,
                onClick = { viewModel.onMicButtonPressed() }
            )

            val currentStatusText = statusText(state)
            Text(
                text = currentStatusText,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .semantics {
                        // با تغییر وضعیت، TalkBack به‌صورت خودکار اعلام می‌کند.
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = currentStatusText
                    }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // --- ورودی متنی: برای تست، و برای گوشی‌هایی که تشخیص گفتار ندارند ---
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(text = "یا دستور را اینجا تایپ کنید") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    viewModel.onTextSubmitted(typed)
                    typed = ""
                },
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(text = "اجرا کن", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(32.dp))

            // --- بخش عیب‌یابی (موقت) ---
            Text(
                text = "گزارش وضعیت (موقت، برای عیب‌یابی)",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            logLines.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MicButton(state: UiState, onClick: () -> Unit) {
    val isListening = state is UiState.Listening
    // در حالت Speaking هم باید فعال باشد: فشردن دکمه، خواندن را قطع می‌کند.
    val enabled = state !is UiState.Thinking

    val description = when {
        isListening -> stringResource(R.string.mic_button_desc_listening)
        state is UiState.Speaking ->
            "در حال خواندن. برای قطع کردن و گفتن دستور جدید، دو بار ضربه بزنید."
        else -> stringResource(R.string.mic_button_desc_idle)
    }

    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(CircleShape)
            .background(
                if (isListening) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
            .clickable(enabled = enabled) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = null, // توضیح روی خود Box گذاشته شده تا TalkBack یک‌بار اعلام کند
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(84.dp)
        )
    }
}

@Composable
private fun statusText(state: UiState): String = when (state) {
    is UiState.Idle -> stringResource(R.string.status_ready)
    is UiState.Listening -> stringResource(R.string.mic_button_listening)
    is UiState.Thinking -> stringResource(R.string.mic_button_thinking)
    is UiState.Speaking -> state.text
    is UiState.AwaitingCallConfirmation ->
        "تماس با ${state.contactName}؟ بگویید بله یا خیر."
    is UiState.AwaitingContactChoice -> "کدام مخاطب؟"
    is UiState.ReadingSms -> "پیام ${state.index} از ${state.total}: ${state.message.body}"
    is UiState.InfoMessage -> state.text
    is UiState.ErrorState -> state.text
}
