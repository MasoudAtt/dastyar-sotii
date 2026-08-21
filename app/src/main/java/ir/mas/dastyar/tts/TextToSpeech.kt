package ir.mas.dastyar.tts

import android.content.Context
import android.content.Intent
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
 * یافته مهم از تست روی گوشی واقعی: موتور پیش‌فرض TTS اندروید (Google) اصلاً
 * صدای فارسی ندارد. چون تمام بازخورد این اپ صوتی است، این یعنی اپ عملاً
 * لال است. بنابراین این لایه دیگر فقط به موتور پیش‌فرض تکیه نمی‌کند: همه
 * موتورهای نصب‌شده روی گوشی را امتحان می‌کند و اولین موتوری را که فارسی
 * دارد انتخاب می‌کند.
 */
interface TextToSpeechProvider {

    fun isAvailable(): Boolean

    /** آیا موتور انتخاب‌شده واقعاً می‌تواند فارسی حرف بزند؟ */
    fun supportsPersian(): Boolean

    /** توضیح کوتاه و قابل‌نمایش از وضعیت موتور، برای عیب‌یابی روی گوشی واقعی. */
    fun statusDescription(): String

    fun speak(text: String, onDone: () -> Unit = {}, onError: (() -> Unit)? = null)

    fun stop()

    fun destroy()
}

class AndroidSystemTtsProvider(context: Context) : TextToSpeechProvider {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var probeFinished = false
    private var persianSupported = false
    private var activeEngine: String = "نامشخص"
    private var installedEngines: List<String> = emptyList()

    /** فهرست موتورهایی که به‌ترتیب امتحان می‌شوند؛ رشته خالی یعنی «موتور پیش‌فرض». */
    private var candidates: List<String> = emptyList()
    private var candidateIndex = -1
    private var fallbackAttempted = false

    init {
        installedEngines = runCatching {
            appContext.packageManager
                .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())

        candidates = listOf("") + installedEngines
        probeNext()
    }

    // -------------------- پیدا کردن موتوری که فارسی بلد باشد --------------------

    private fun probeNext() {
        candidateIndex++
        if (candidateIndex >= candidates.size) {
            finishWithoutPersian()
            return
        }
        startEngine(candidates[candidateIndex])
    }

    private fun startEngine(enginePackage: String) {
        shutdownCurrent()
        val listener = TextToSpeech.OnInitListener { status ->
            onEngineInit(status, enginePackage)
        }
        tts = runCatching {
            if (enginePackage.isEmpty()) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, enginePackage)
        }.getOrNull()

        if (tts == null) {
            Handler(Looper.getMainLooper()).post { probeNext() }
        }
    }

    private fun onEngineInit(status: Int, enginePackage: String) {
        if (status != TextToSpeech.SUCCESS) {
            Handler(Looper.getMainLooper()).post { probeNext() }
            return
        }

        val engine = tts
        val languageResult = runCatching { engine?.setLanguage(Locale("fa", "IR")) }.getOrNull()
        val supported = languageResult != null &&
            languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        val engineLabel =
            if (enginePackage.isEmpty()) (engine?.defaultEngine ?: "پیش‌فرض") else enginePackage

        if (supported) {
            persianSupported = true
            activeEngine = engineLabel
            probeFinished = true
            return
        }

        if (fallbackAttempted) {
            // این همان تلاش نهایی بازگشت به موتور پیش‌فرض بود.
            activeEngine = engineLabel
            persianSupported = false
            probeFinished = true
            return
        }

        Handler(Looper.getMainLooper()).post { probeNext() }
    }

    /**
     * هیچ موتوری فارسی نداشت. به موتور پیش‌فرض برمی‌گردیم تا دست‌کم چیزی
     * گفته شود (بهتر از سکوت مطلق) و وضعیت به کاربر گزارش شود.
     */
    private fun finishWithoutPersian() {
        if (fallbackAttempted) {
            probeFinished = true
            return
        }
        fallbackAttempted = true
        startEngine("")
    }

    private fun shutdownCurrent() {
        runCatching { tts?.shutdown() }
        tts = null
    }

    // -------------------- API --------------------

    override fun isAvailable(): Boolean = probeFinished && tts != null

    override fun supportsPersian(): Boolean = persianSupported

    override fun statusDescription(): String {
        val persianText =
            if (persianSupported) "فارسی پشتیبانی می‌شود"
            else "هیچ موتوری صدای فارسی ندارد"
        val state = if (probeFinished) "آماده" else "در حال بررسی موتورها"
        val engineList =
            if (installedEngines.isEmpty()) "هیچ موتوری نصب نیست"
            else installedEngines.joinToString("، ") { it.substringAfterLast('.') }
        return "خروجی صدا (TTS): $state — $persianText — موتور فعال: " +
            "${activeEngine.substringAfterLast('.')} — نصب‌شده: $engineList"
    }

    override fun speak(text: String, onDone: () -> Unit, onError: (() -> Unit)?) {
        speakWhenReady(text, onDone, onError, attempt = 0)
    }

    /**
     * راه‌اندازی و بررسی موتورها ناهمگام است و ممکن است چند ثانیه طول بکشد.
     * به‌جای رها کردن بی‌صدا، تا حدود ۶ ثانیه منتظر می‌مانیم.
     */
    private fun speakWhenReady(
        text: String,
        notifyDone: () -> Unit,
        notifyError: (() -> Unit)?,
        attempt: Int
    ) {
        val engine = tts
        if (!probeFinished || engine == null) {
            if (attempt >= 20) {
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
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            if (notifyError != null) notifyError.invoke() else notifyDone()
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun destroy() {
        shutdownCurrent()
    }
}
