package ir.mas.dastyar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
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
 * جهت متن همیشه راست‌به‌چپ (فارسی) در نظر گرفته شده، صرف‌نظر از locale
 * سیستم، چون کل تجربه اپ فارسی است.
 */
@Composable
fun MainScreen(viewModel: ConversationViewModel) {
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        val state by viewModel.state.collectAsState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
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
        }
    }
}

@Composable
private fun MicButton(state: UiState, onClick: () -> Unit) {
    val isListening = state is UiState.Listening
    val enabled = state !is UiState.Thinking && state !is UiState.Speaking

    val description = when {
        isListening -> stringResource(R.string.mic_button_desc_listening)
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
