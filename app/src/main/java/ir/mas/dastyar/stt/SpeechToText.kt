package ir.mas.dastyar.stt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * لایه Abstraction روی موتور تبدیل گفتار به متن.
 *
 * پیاده‌سازی MVP فعلی ([AndroidSystemSttProvider]) از سرویس تشخیص گفتار
 * سیستم اندروید استفاده می‌کند تا مسیر کامل صدا→متن→Intent→عملیات هر چه
 * زودتر قابل تست روی گوشی واقعی باشد. طبق تحلیل امکان‌سنجی پروژه، این
 * پیاده‌سازی به Google Play Services وابسته است که در ایران همیشه قابل
 * اعتماد نیست؛ به همین دلیل قدم بعدی برنامه‌ریزی‌شده جایگزینی آن با یک
 * provider آفلاین (Vosk) است — بدون نیاز به تغییر در بقیه اپ، چون همه از
 * همین اینترفیس استفاده می‌کنند.
 */
interface SpeechToTextProvider {

    /** آیا این پیاده‌سازی روی این گوشی/نسخه اندروید قابل استفاده است؟ */
    fun isAvailable(): Boolean

    /** توضیح کوتاه و قابل‌نمایش از وضعیت موتور، برای عیب‌یابی روی گوشی واقعی. */
    fun statusDescription(): String

    /**
     * شروع شنیدن. فقط یکی از callbackها برای هر بار شنیدن صدا زده می‌شود.
     * @param onPartialResult در صورت پشتیبانی، متن جزئی حین صحبت‌کردن (اختیاری، ممکن است هرگز صدا زده نشود)
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (SttError) -> Unit,
        onPartialResult: (String) -> Unit = {}
    )

    fun stopListening()

    fun destroy()
}

enum class SttError {
    NO_PERMISSION,
    NO_SPEECH_DETECTED,
    NETWORK_OR_SERVICE_UNAVAILABLE,
    UNKNOWN
}

/**
 * پیاده‌سازی موقت MVP: از موتور تشخیص گفتار سیستم اندروید (که معمولاً توسط
 * برنامه Google یا Gboard تأمین می‌شود) با locale فارسی (fa-IR) استفاده می‌کند.
 *
 * محدودیت شناخته‌شده: نیازمند نصب/فعال‌بودن سرویس گوگل روی گوشی است. طبق
 * سند امکان‌سنجی، این می‌تواند در ایران ناپایدار باشد؛ به همین دلیل این
 * پیاده‌سازی «موقت» تلقی می‌شود تا زمانی که یک provider آفلاین جایگزین شود.
 */
class AndroidSystemSttProvider(private val context: Context) : SpeechToTextProvider {

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun statusDescription(): String {
        val services = runCatching {
            context.packageManager
                .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
                .size
        }.getOrDefault(-1)

        return if (isAvailable()) {
            "ورودی صدا (STT): سرویس تشخیص گفتار پیدا شد (تعداد: $services)"
        } else {
            "ورودی صدا (STT): هیچ سرویس تشخیص گفتاری روی این گوشی پیدا نشد (تعداد: $services)"
        }
    }

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (SttError) -> Unit,
        onPartialResult: (String) -> Unit
    ) {
        // نام جدا برای جلوگیری از تداخل نام با متد override شده onError(Int) در ادامه.
        val notifyError = onError

        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyError(SttError.NO_PERMISSION)
            return
        }

        if (!isAvailable()) {
            notifyError(SttError.NETWORK_OR_SERVICE_UNAVAILABLE)
            return
        }

        destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                val mapped = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.NO_SPEECH_DETECTED
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> SttError.NETWORK_OR_SERVICE_UNAVAILABLE
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.NO_PERMISSION
                    else -> SttError.UNKNOWN
                }
                notifyError(mapped)
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    onResult(text)
                } else {
                    notifyError(SttError.NO_SPEECH_DETECTED)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartialResult(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        r.startListening(intent)
    }

    override fun stopListening() {
        recognizer?.stopListening()
    }

    override fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
