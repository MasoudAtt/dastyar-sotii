package ir.mas.dastyar.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * لایه Abstraction روی موتور تبدیل متن به گفتار.
 *
 * پیاده‌سازی MVP فعلی ([AndroidSystemTtsProvider]) از موتور TTS سیستم اندروید
 * استفاده می‌کند. طبق سند امکان‌سنجی، پشتیبانی فارسی موتورهای TTS سیستمی
 * ناسازگار/ناکامل گزارش شده؛ قدم بعدی جایگزینی با یک موتور آفلاین
 * (با صدای فارسی) به‌علاوه eSpeak-NG به‌عنوان fallback تضمینی است.
 */
interface TextToSpeechProvider {

    fun isAvailable(): Boolean

    /** توضیح کوتاه و قابل‌نمایش از وضعیت موتور، برای عیب‌یابی روی گوشی واقعی. */
    fun statusDescription(): String

    fun speak(text: String, onDone: () -> Unit = {}, onError: (() -> Unit)? = null)

    fun stop()

    fun destroy()
}

class AndroidSystemTtsProvider(context: Context) : TextToSpeechProvider {

    private var ready = false
    private var initStatus: Int? = null
    private var languageResult: Int? = null

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            initStatus = status
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                languageResult = runCatching { tts.setLanguage(Locale("fa", "IR")) }.getOrNull()
            }
        }
    }

    override fun isAvailable(): Boolean = ready

    override fun statusDescription(): String {
        val initText = when (initStatus) {
            null -> "در حال آماده‌سازی"
            TextToSpeech.SUCCESS -> "آماده"
            else -> "راه‌اندازی نشد (موتور TTS نصب نیست؟)"
        }
        val langText = when (languageResult) {
            null -> "زبان هنوز بررسی نشده"
            TextToSpeech.LANG_MISSING_DATA -> "داده صدای فارسی نصب نیست"
            TextToSpeech.LANG_NOT_SUPPORTED -> "فارسی پشتیبانی نمی‌شود"
            else -> "فارسی موجود است"
        }
        return "خروجی صدا (TTS): $initText — $langText"
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (() -> Unit)?) {
        speakWhenReady(text, onDone, onError, attempt = 0)
    }

    /**
     * راه‌اندازی موتور TTS ناهمگام است و ممکن است چند صد میلی‌ثانیه طول بکشد.
     * اگر بلافاصله پس از باز شدن اپ چیزی گفته شود، ممکن است هنوز آماده نباشد؛
     * به‌جای رها کردن بی‌صدا، تا حدود ۳ ثانیه منتظر می‌مانیم.
     */
    private fun speakWhenReady(
        text: String,
        notifyDone: () -> Unit,
        notifyError: (() -> Unit)?,
        attempt: Int
    ) {
        if (!ready) {
            if (attempt >= 10) {
                if (notifyError != null) notifyError.invoke() else notifyDone()
                return
            }
            Handler(Looper.getMainLooper()).postDelayed(
                { speakWhenReady(text, notifyDone, notifyError, attempt + 1) },
                300L
            )
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                notifyDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (notifyError != null) notifyError.invoke() else notifyDone()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (notifyError != null) notifyError.invoke() else notifyDone()
            }
        })

        val params = Bundle()
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            if (notifyError != null) notifyError.invoke() else notifyDone()
        }
    }

    override fun stop() {
        if (ready) tts.stop()
    }

    override fun destroy() {
        if (ready) tts.shutdown()
    }
}
