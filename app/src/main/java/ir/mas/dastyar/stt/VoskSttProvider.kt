package ir.mas.dastyar.stt

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * تشخیص گفتار کاملاً آفلاین با Vosk.
 *
 * چرا لازم شد: موتور SpeechRecognizer اندروید در عمل سرویس ابری گوگل است.
 * در تست روی گوشی واقعی، بدون اینترنت خطای NETWORK_OR_SERVICE_UNAVAILABLE
 * می‌داد — یعنی اپ برای کاربری که اینترنت پایدار ندارد اصلاً کار نمی‌کرد.
 *
 * مدل فارسی (vosk-model-small-fa) داخل خود APK قرار می‌گیرد، پس از لحظه
 * نصب و بدون هیچ اتصالی کار می‌کند. مدل هنگام اولین اجرا یک‌بار از assets
 * به حافظه داخلی اپ باز می‌شود، چون Vosk به مسیر واقعی فایل نیاز دارد و
 * فایل‌های داخل assets مسیر واقعی ندارند.
 */
class VoskSttProvider(private val context: Context) : SpeechToTextProvider {

    private companion object {
        const val MODEL_ASSET = "model-fa"
        const val MODEL_DIR = "vosk-model-fa"
        const val MARKER = ".unpacked"
        const val SAMPLE_RATE = 16000.0f
    }

    @Volatile
    private var model: Model? = null

    @Volatile
    private var statusText: String = "موتور آفلاین: در حال آماده‌سازی…"

    private var speechService: SpeechService? = null

    init {
        // بارگذاری مدل چند ثانیه طول می‌کشد و نباید نخ اصلی را قفل کند.
        Thread {
            val result = runCatching {
                val directory = ensureModelUnpacked()
                Model(directory.absolutePath)
            }
            val main = Handler(Looper.getMainLooper())
            result.onSuccess { loaded ->
                main.post {
                    model = loaded
                    statusText = "ورودی صدا (Vosk): آماده — کاملاً آفلاین"
                }
            }.onFailure { error ->
                main.post {
                    statusText = "ورودی صدا (Vosk): بارگذاری نشد — ${error.message}"
                }
            }
        }.start()
    }

    // -------------------- باز کردن مدل از assets --------------------

    private fun ensureModelUnpacked(): File {
        val target = File(context.filesDir, MODEL_DIR)
        val marker = File(target, MARKER)
        if (marker.exists()) return target

        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        copyAsset(MODEL_ASSET, target)
        marker.createNewFile()
        return target
    }

    /** کپی بازگشتی یک پوشه از assets؛ اگر مسیر فایل باشد، خود فایل کپی می‌شود. */
    private fun copyAsset(assetPath: String, target: File) {
        val children = runCatching { context.assets.list(assetPath) }.getOrNull() ?: emptyArray()

        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        for (child in children) {
            copyAsset("$assetPath/$child", File(target, child))
        }
    }

    // -------------------- API --------------------

    override fun isAvailable(): Boolean = model != null

    override fun statusDescription(): String = statusText

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (SttError) -> Unit,
        onPartialResult: (String) -> Unit
    ) {
        // نام‌های جدا، چون در ادامه متدهای هم‌نام override می‌شوند.
        val deliver = onResult
        val notifyError = onError
        val notifyPartial = onPartialResult

        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyError(SttError.NO_PERMISSION)
            return
        }

        val loadedModel = model
        if (loadedModel == null) {
            notifyError(SttError.NETWORK_OR_SERVICE_UNAVAILABLE)
            return
        }

        stopListening()

        val delivered = AtomicBoolean(false)

        val started = runCatching {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service

            service.startListening(object : RecognitionListener {

                override fun onPartialResult(hypothesis: String?) {
                    extract(hypothesis, "partial")?.let { notifyPartial(it) }
                }

                /** وقتی Vosk خودش سکوت را تشخیص می‌دهد و جمله را تمام‌شده می‌داند. */
                override fun onResult(hypothesis: String?) {
                    val text = extract(hypothesis, "text")
                    if (!text.isNullOrBlank() && delivered.compareAndSet(false, true)) {
                        stopListening()
                        deliver(text)
                    }
                }

                /** وقتی ما دستور توقف داده‌ایم. */
                override fun onFinalResult(hypothesis: String?) {
                    val text = extract(hypothesis, "text")
                    if (delivered.compareAndSet(false, true)) {
                        if (!text.isNullOrBlank()) deliver(text)
                        else notifyError(SttError.NO_SPEECH_DETECTED)
                    }
                }

                override fun onError(exception: Exception?) {
                    if (delivered.compareAndSet(false, true)) {
                        notifyError(SttError.UNKNOWN)
                    }
                }

                override fun onTimeout() {
                    if (delivered.compareAndSet(false, true)) {
                        notifyError(SttError.NO_SPEECH_DETECTED)
                    }
                }
            })
        }

        if (started.isFailure) {
            notifyError(SttError.AUDIO_PROBLEM)
        }
    }

    override fun stopListening() {
        val service = speechService ?: return
        runCatching { service.stop() }
    }

    override fun destroy() {
        runCatching { speechService?.shutdown() }
        speechService = null
    }

    /** خروجی Vosk یک رشته JSON است، مثل {"text": "به علی زنگ بزن"} */
    private fun extract(json: String?, key: String): String? = runCatching {
        if (json.isNullOrBlank()) null
        else JSONObject(json).optString(key, "").takeIf { it.isNotBlank() }
    }.getOrNull()
}

/**
 * ابتدا موتور آفلاین را امتحان می‌کند و فقط اگر در دسترس نبود سراغ موتور
 * سیستمی (ابری) می‌رود. این‌طور اپ روی گوشی بدون اینترنت کار می‌کند و روی
 * گوشی‌هایی که مدل آفلاین بارگذاری نشده هم بی‌استفاده نمی‌ماند.
 */
class FallbackSttProvider(
    private val primary: SpeechToTextProvider,
    private val secondary: SpeechToTextProvider
) : SpeechToTextProvider {

    private fun active(): SpeechToTextProvider =
        if (primary.isAvailable()) primary else secondary

    override fun isAvailable(): Boolean = primary.isAvailable() || secondary.isAvailable()

    override fun statusDescription(): String =
        primary.statusDescription() + " || " + secondary.statusDescription()

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (SttError) -> Unit,
        onPartialResult: (String) -> Unit
    ) {
        active().startListening(onResult, onError, onPartialResult)
    }

    override fun stopListening() {
        runCatching { primary.stopListening() }
        runCatching { secondary.stopListening() }
    }

    override fun destroy() {
        runCatching { primary.destroy() }
        runCatching { secondary.destroy() }
    }
}
