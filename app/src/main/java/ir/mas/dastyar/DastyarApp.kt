package ir.mas.dastyar

import android.app.Application
import ir.mas.dastyar.calling.CallManager
import ir.mas.dastyar.contacts.ContactsResolver
import ir.mas.dastyar.intent.LlmProvider
import ir.mas.dastyar.intent.RuleBasedLlmProvider
import ir.mas.dastyar.sms.SmsReader
import ir.mas.dastyar.stt.AndroidSystemSttProvider
import ir.mas.dastyar.stt.SpeechToTextProvider
import ir.mas.dastyar.tts.AndroidSystemTtsProvider
import ir.mas.dastyar.tts.TextToSpeechProvider

/**
 * محل ساده و متمرکز «سیم‌کشی» وابستگی‌ها (بدون فریم‌ورک DI اضافه، برای سادگی MVP).
 *
 * هر پیاده‌سازی provider که اینجا انتخاب می‌شود، طبق لایه Abstraction پروژه
 * به‌راحتی قابل جایگزینی است — مثلاً تعویض [AndroidSystemSttProvider] با
 * یک VoskSttProvider آفلاین در آینده، فقط همین یک خط باید تغییر کند.
 */
class DastyarApp : Application() {

    lateinit var sttProvider: SpeechToTextProvider
        private set

    lateinit var ttsProvider: TextToSpeechProvider
        private set

    lateinit var llmProvider: LlmProvider
        private set

    lateinit var contactsResolver: ContactsResolver
        private set

    lateinit var callManager: CallManager
        private set

    lateinit var smsReader: SmsReader
        private set

    override fun onCreate() {
        super.onCreate()

        sttProvider = AndroidSystemSttProvider(this)
        ttsProvider = AndroidSystemTtsProvider(this)
        llmProvider = RuleBasedLlmProvider()
        contactsResolver = ContactsResolver(this)
        callManager = CallManager(this)
        smsReader = SmsReader(this)
    }
}
