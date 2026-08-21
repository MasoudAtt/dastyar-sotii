package ir.mas.dastyar.sms

import android.content.Context
import android.provider.Telephony
import ir.mas.dastyar.intent.SmsNavigationCommand

data class SmsMessage(
    val sender: String,
    val body: String,
    val timestampMillis: Long
)

/**
 * خواندن پیامک‌های صندوق ورودی از Telephony Provider، مرتب‌شده از جدید به قدیم.
 *
 * نکته حریم‌خصوصی مهم طبق طراحی امنیتی پروژه: متن پیامک‌ها هرگز از این کلاس
 * به بیرون از دستگاه (و مخصوصاً هرگز به LlmProvider) ارسال نمی‌شود؛ فقط
 * مستقیماً توسط TextToSpeechProvider خوانده می‌شود.
 */
class SmsReader(private val context: Context) {

    fun loadRecentMessages(limit: Int = 20): List<SmsMessage> {
        val results = mutableListOf<SmsMessage>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                val address = if (addressIdx >= 0) cursor.getString(addressIdx) else null
                val body = if (bodyIdx >= 0) cursor.getString(bodyIdx) else null
                val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L

                if (body.isNullOrBlank()) continue
                results += SmsMessage(
                    sender = address ?: "ناشناس",
                    body = body,
                    timestampMillis = date
                )
            }
        }

        return results
    }
}

/**
 * مدیریت وضعیت «کدام پیام الان در حال خوانده‌شدن است» و تشخیص دستورات
 * ناوبری فارسی («بعدی»/«قبلی»/«دوباره بخون»/«متوقف شو»). این دستورات به‌عمد
 * با Regex ساده تشخیص داده می‌شوند و نیازی به LLM ندارند.
 */
class SmsNavigationController(private val messages: List<SmsMessage>) {

    private var currentIndex: Int = -1

    val hasMessages: Boolean get() = messages.isNotEmpty()
    val total: Int get() = messages.size

    /** موقعیت فعلی به‌صورت شماره یک‌مبنا (برای نمایش «پیام ۲ از ۵»)؛ اگر هنوز شروع نشده، ۰. */
    val currentPosition: Int get() = currentIndex + 1

    fun current(): SmsMessage? = messages.getOrNull(currentIndex)

    fun first(): SmsMessage? {
        if (messages.isEmpty()) return null
        currentIndex = 0
        return messages[currentIndex]
    }

    fun next(): SmsMessage? {
        if (currentIndex + 1 >= messages.size) return null
        currentIndex += 1
        return messages[currentIndex]
    }

    fun previous(): SmsMessage? {
        if (currentIndex - 1 < 0) return null
        currentIndex -= 1
        return messages[currentIndex]
    }

    companion object {
        private val nextPatterns = listOf("بعدی", "پیام بعد", "بعد")
        private val prevPatterns = listOf("قبلی", "پیام قبل", "قبل")
        private val repeatPatterns = listOf("دوباره", "تکرار", "دوباره بخون")
        private val stopPatterns = listOf("متوقف", "بس", "کافیه", "تمام", "توقف")

        fun parseCommand(utterance: String): SmsNavigationCommand {
            val text = utterance.trim()
            return when {
                stopPatterns.any { text.contains(it) } -> SmsNavigationCommand.STOP
                repeatPatterns.any { text.contains(it) } -> SmsNavigationCommand.REPEAT
                nextPatterns.any { text.contains(it) } -> SmsNavigationCommand.NEXT
                prevPatterns.any { text.contains(it) } -> SmsNavigationCommand.PREVIOUS
                else -> SmsNavigationCommand.UNKNOWN
            }
        }
    }
}
