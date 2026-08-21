package ir.mas.dastyar

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import ir.mas.dastyar.calling.CallManager
import ir.mas.dastyar.contacts.ContactsResolver
import ir.mas.dastyar.intent.LlmProvider
import ir.mas.dastyar.intent.RuleBasedLlmProvider
import ir.mas.dastyar.sms.SmsReader
import ir.mas.dastyar.stt.AndroidSystemSttProvider
import ir.mas.dastyar.stt.SpeechToTextProvider
import ir.mas.dastyar.tts.AndroidSystemTtsProvider
import ir.mas.dastyar.tts.TextToSpeechProvider
import kotlin.system.exitProcess

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

    private var startedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        startedAtMillis = System.currentTimeMillis()

        sttProvider = AndroidSystemSttProvider(this)
        ttsProvider = AndroidSystemTtsProvider(this)
        llmProvider = RuleBasedLlmProvider()
        contactsResolver = ContactsResolver(this)
        callManager = CallManager(this)
        smsReader = SmsReader(this)

        installCrashRecovery()
    }

    /**
     * آخرین لایه ایمنی: اگر با وجود همه محافظت‌ها اپ کرش کند، خودش دوباره
     * باز می‌شود — چون کاربر نابیناست و ممکن است اصلاً متوجه بسته‌شدن اپ نشود.
     *
     * محافظت در برابر حلقه بی‌پایان: اگر کرش در کمتر از ۳۰ ثانیه پس از شروع
     * رخ دهد (یعنی احتمالاً همان کرشِ راه‌اندازی است)، اپ دوباره باز نمی‌شود.
     */
    private fun installCrashRecovery() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val ranLongEnough = System.currentTimeMillis() - startedAtMillis > 30_000L

            if (ranLongEnough) {
                runCatching {
                    val restartIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        this,
                        0,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    alarmManager.set(
                        AlarmManager.RTC,
                        System.currentTimeMillis() + 1_500L,
                        pendingIntent
                    )
                }
            }

            runCatching { previousHandler?.uncaughtException(thread, throwable) }
            Process.killProcess(Process.myPid())
            exitProcess(2)
        }
    }
}
