package ir.mas.dastyar.tts

import android.content.Context
import android.os.Bundle
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

    fun speak(text: String, onDone: () -> Unit = {}, onError: (() -> Unit)? = null)

    fun stop()

    fun destroy()
}

class AndroidSystemTtsProvider(context: Context) : TextToSpeechProvider {

    private var ready = false
    private var faSupported = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
        }
    }

    private fun ensureLanguage(): Boolean {
        val result = tts.setLanguage(Locale("fa", "IR"))
        faSupported = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        return faSupported
    }

    override fun isAvailable(): Boolean = ready

    override fun speak(text: String, onDone: () -> Unit, onError: (() -> Unit)?) {
        if (!ready) {
            onError?.invoke()
            return
        }
        ensureLanguage()

        // نام‌های جدا برای جلوگیری از تداخل نام با متدهای override شده هم‌نام در ادامه.
        val notifyDone = onDone
        val notifyError = onError

        val utteranceId = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                notifyDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                notifyError?.invoke()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                notifyError?.invoke()
            }
        })

        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }

    override fun destroy() {
        tts.shutdown()
    }
}
