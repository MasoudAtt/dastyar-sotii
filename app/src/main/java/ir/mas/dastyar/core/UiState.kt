package ir.mas.dastyar.core

import ir.mas.dastyar.contacts.Contact
import ir.mas.dastyar.sms.SmsMessage

/**
 * تمام حالت‌های ممکن رابط کاربری/مکالمه. نوع دقیق state تعیین می‌کند که
 * جمله بعدی کاربر چگونه تفسیر شود (مثلاً در AwaitingCallConfirmation فقط
 * بله/خیر بررسی می‌شود، نه یک Intent جدید).
 */
sealed class UiState {

    data object Idle : UiState()

    data object Listening : UiState()

    data object Thinking : UiState()

    data class Speaking(val text: String) : UiState()

    data class AwaitingCallConfirmation(
        val contactName: String,
        val phoneNumber: String
    ) : UiState()

    data class AwaitingContactChoice(
        val candidates: List<Contact>
    ) : UiState()

    data class ReadingSms(
        val message: SmsMessage,
        val index: Int,
        val total: Int
    ) : UiState()

    data class InfoMessage(val text: String) : UiState()

    data class ErrorState(val text: String) : UiState()
}
