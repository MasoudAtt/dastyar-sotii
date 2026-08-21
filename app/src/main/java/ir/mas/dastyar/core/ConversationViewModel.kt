package ir.mas.dastyar.core

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ir.mas.dastyar.DastyarApp
import ir.mas.dastyar.calling.CallManager
import ir.mas.dastyar.calling.CallResult
import ir.mas.dastyar.contacts.Contact
import ir.mas.dastyar.contacts.ContactLookupResult
import ir.mas.dastyar.contacts.ContactsResolver
import ir.mas.dastyar.intent.LlmProvider
import ir.mas.dastyar.intent.ParsedIntent
import ir.mas.dastyar.intent.SmsNavigationCommand
import ir.mas.dastyar.sms.SmsNavigationController
import ir.mas.dastyar.sms.SmsReader
import ir.mas.dastyar.stt.SpeechToTextProvider
import ir.mas.dastyar.stt.SttError
import ir.mas.dastyar.tts.TextToSpeechProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * هماهنگ‌کننده اصلی مکالمه: Voice → STT → (تفسیر بر اساس state فعلی) →
 * LLM/Intent → Action → TTS.
 *
 * قوانین امنیتی که این کلاس تضمین می‌کند (طبق طراحی معماری پروژه):
 *  1. CALL_CONTACT هرگز بدون عبور از AwaitingCallConfirmation و شنیدن «بله»
 *     صریح از کاربر اجرا نمی‌شود.
 *  2. متن SMS هرگز به LlmProvider داده نمی‌شود — SmsNavigationController با
 *     Regex ساده کار می‌کند.
 *  3. خروجی نامعتبر/مبهم LLM هرگز باعث اجرای CALL/SMS نمی‌شود.
 */
class ConversationViewModel(
    private val stt: SpeechToTextProvider,
    private val tts: TextToSpeechProvider,
    private val llm: LlmProvider,
    private val contactsResolver: ContactsResolver,
    private val callManager: CallManager,
    private val smsReader: SmsReader
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * گزارش رویدادهای اخیر، فقط برای عیب‌یابی روی گوشی واقعی.
     * چون همه بازخوردهای این اپ صوتی است، اگر موتور صدا کار نکند کاربر
     * هیچ نشانه‌ای نمی‌بیند؛ این لیست دقیقاً همان نقطه کور را پر می‌کند.
     */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private fun logEvent(message: String) {
        _log.value = (_log.value + message).takeLast(15)
    }

    private var smsController: SmsNavigationController? = null

    /** وضعیت موتورهای صدا را می‌خواند و در گزارش می‌نویسد. */
    fun refreshDiagnostics() {
        logEvent(stt.statusDescription())
        logEvent(tts.statusDescription())
        logEvent("مغز تشخیص دستور: ${llm.providerName}")
    }

    // -------------------- ورودی صدا --------------------

    fun onMicButtonPressed() {
        logEvent("دکمه میکروفون فشرده شد (وضعیت: ${_state.value::class.simpleName})")
        when (_state.value) {
            is UiState.Listening -> {
                stt.stopListening()
            }
            is UiState.Thinking, is UiState.Speaking -> {
                // در حین پردازش/پاسخ، دکمه میکروفون نادیده گرفته می‌شود.
            }
            else -> startListeningTurn()
        }
    }

    /** ورودی متنی (برای تست و برای گوشی‌هایی که تشخیص گفتار ندارند). */
    fun onTextSubmitted(text: String) {
        if (text.isBlank()) return
        logEvent("ورودی متنی: $text")
        onUserUtterance(text)
    }

    private fun startListeningTurn() {
        if (!stt.isAvailable()) {
            val message = "سرویس تشخیص گفتار روی این گوشی در دسترس نیست."
            logEvent(message)
            speak(message) { _state.value = UiState.ErrorState(message) }
            return
        }
        _state.value = UiState.Listening
        logEvent("شروع شنیدن…")
        stt.startListening(
            onResult = { text ->
                logEvent("شنیده شد: $text")
                onUserUtterance(text)
            },
            onError = { error -> onSttError(error) }
        )
    }

    private fun onSttError(error: SttError) {
        val message = when (error) {
            SttError.NO_PERMISSION -> "دسترسی میکروفون داده نشده است."
            SttError.NO_SPEECH_DETECTED -> "صدایی شنیده نشد. دوباره امتحان کنید."
            SttError.NETWORK_OR_SERVICE_UNAVAILABLE -> "سرویس تشخیص گفتار در دسترس نیست."
            SttError.UNKNOWN -> "مشکلی در شنیدن صدا پیش آمد."
        }
        logEvent("خطای شنیدن: $error")
        // پیام روی صفحه باقی می‌ماند تا اگر صدا کار نکند هم کاربر متوجه شود.
        speak(message) { _state.value = UiState.ErrorState(message) }
    }

    // -------------------- تفسیر جمله بر اساس state فعلی --------------------

    private fun onUserUtterance(text: String) {
        // چون الان state به Thinking تغییر می‌کند، context قبلی را از قبل نگه‌داشته‌شده می‌خوانیم.
        val previous = lastMeaningfulState
        _state.value = UiState.Thinking

        when (previous) {
            is UiState.AwaitingCallConfirmation -> handleCallConfirmationAnswer(text, previous)
            is UiState.AwaitingContactChoice -> handleContactChoiceAnswer(text, previous)
            is UiState.ReadingSms -> handleSmsNavigation(text)
            else -> handleFreshIntent(text)
        }
    }

    /** آخرین state معنادار (غیر از Idle/Listening/Thinking) برای تفسیر جمله بعدی. */
    private var lastMeaningfulState: UiState = UiState.Idle

    private fun rememberState(newState: UiState) {
        lastMeaningfulState = newState
        _state.value = newState
    }

    // -------------------- مسیر اصلی: طبقه‌بندی Intent جدید --------------------

    private fun handleFreshIntent(text: String) {
        viewModelScope.launch {
            when (val intent = llm.classify(text)) {
                is ParsedIntent.CallContact -> startCallFlow(intent.contactName)
                is ParsedIntent.ReadSms -> startReadSmsFlow()
                is ParsedIntent.Chat -> {
                    val reply = intent.reply ?: "بله؟"
                    speak(reply) {
                        lastMeaningfulState = UiState.Idle
                        _state.value = UiState.Idle
                    }
                }
                is ParsedIntent.Invalid -> {
                    speak("متوجه نشدم. می‌توانید دوباره واضح‌تر بگویید؟") {
                        lastMeaningfulState = UiState.Idle
                        _state.value = UiState.Idle
                    }
                }
            }
        }
    }

    // -------------------- CALL_CONTACT --------------------

    private fun startCallFlow(contactName: String) {
        when (val result = contactsResolver.findByName(contactName)) {
            is ContactLookupResult.SingleMatch -> {
                val contact = result.contact
                val question = "${contact.displayName} را پیدا کردم. می‌خواهید با او تماس بگیرم؟"
                speak(question) {
                    rememberState(
                        UiState.AwaitingCallConfirmation(
                            contactName = contact.displayName,
                            phoneNumber = contact.phoneNumber
                        )
                    )
                }
            }
            is ContactLookupResult.MultipleMatches -> {
                val names = result.contacts.mapIndexed { i, c -> "${i + 1}. ${c.displayName}" }
                    .joinToString("، ")
                speak("چند مخاطب مشابه پیدا شد: $names. کدام‌یک را می‌خواهید؟") {
                    rememberState(UiState.AwaitingContactChoice(result.contacts))
                }
            }
            is ContactLookupResult.NoMatch -> {
                speak("مخاطبی با نام $contactName پیدا نکردم.") {
                    lastMeaningfulState = UiState.Idle
                    _state.value = UiState.Idle
                }
            }
        }
    }

    private fun handleCallConfirmationAnswer(text: String, pending: UiState.AwaitingCallConfirmation) {
        when {
            isAffirmative(text) -> {
                when (callManager.placeCall(pending.phoneNumber)) {
                    CallResult.Placed -> {
                        rememberState(UiState.InfoMessage("در حال تماس با ${pending.contactName}"))
                        // پس از برقراری تماس، مکالمه به حالت آماده برمی‌گردد.
                        lastMeaningfulState = UiState.Idle
                        _state.value = UiState.Idle
                    }
                    CallResult.NoPermission -> {
                        speak("دسترسی تماس داده نشده است.") {
                            lastMeaningfulState = UiState.Idle
                            _state.value = UiState.Idle
                        }
                    }
                    CallResult.Failed -> {
                        speak("برقراری تماس با مشکل مواجه شد.") {
                            lastMeaningfulState = UiState.Idle
                            _state.value = UiState.Idle
                        }
                    }
                }
            }
            isNegative(text) -> {
                speak("باشه، تماس گرفته نشد.") {
                    lastMeaningfulState = UiState.Idle
                    _state.value = UiState.Idle
                }
            }
            else -> {
                speak("متوجه نشدم. برای تماس با ${pending.contactName} بگویید «بله» یا «خیر».") {
                    _state.value = pending
                }
            }
        }
    }

    private fun handleContactChoiceAnswer(text: String, pending: UiState.AwaitingContactChoice) {
        val chosen = resolveOrdinalChoice(text, pending.candidates)
            ?: pending.candidates.firstOrNull { text.contains(it.displayName) }

        if (chosen == null) {
            speak("متوجه نشدم کدام‌یک را می‌گویید. لطفاً شماره یا نام کامل را بگویید.") {
                _state.value = pending
            }
            return
        }

        speak("${chosen.displayName} را پیدا کردم. می‌خواهید با او تماس بگیرم؟") {
            rememberState(
                UiState.AwaitingCallConfirmation(
                    contactName = chosen.displayName,
                    phoneNumber = chosen.phoneNumber
                )
            )
        }
    }

    // -------------------- READ_SMS --------------------

    private fun startReadSmsFlow() {
        val messages = smsReader.loadRecentMessages()
        val controller = SmsNavigationController(messages)
        smsController = controller

        if (!controller.hasMessages) {
            speak("پیامکی پیدا نکردم.") {
                lastMeaningfulState = UiState.Idle
                _state.value = UiState.Idle
            }
            return
        }

        val first = controller.first()!!
        val intro = "${messages.size} پیام دارید. پیام اول از ${first.sender}: ${first.body}"
        speak(intro) {
            rememberState(UiState.ReadingSms(first, controller.currentPosition, controller.total))
        }
    }

    private fun handleSmsNavigation(text: String) {
        val controller = smsController
        if (controller == null) {
            handleFreshIntent(text)
            return
        }

        when (SmsNavigationController.parseCommand(text)) {
            SmsNavigationCommand.NEXT -> {
                val next = controller.next()
                if (next == null) {
                    speak("پیام بعدی وجود ندارد.") { _state.value = lastMeaningfulState }
                } else {
                    speak("پیام از ${next.sender}: ${next.body}") {
                        rememberState(UiState.ReadingSms(next, controller.currentPosition, controller.total))
                    }
                }
            }
            SmsNavigationCommand.PREVIOUS -> {
                val prev = controller.previous()
                if (prev == null) {
                    speak("پیام قبلی وجود ندارد.") { _state.value = lastMeaningfulState }
                } else {
                    speak("پیام از ${prev.sender}: ${prev.body}") {
                        rememberState(UiState.ReadingSms(prev, controller.currentPosition, controller.total))
                    }
                }
            }
            SmsNavigationCommand.REPEAT -> {
                val current = controller.current()
                if (current == null) {
                    _state.value = lastMeaningfulState
                } else {
                    speak("پیام از ${current.sender}: ${current.body}") {
                        _state.value = lastMeaningfulState
                    }
                }
            }
            SmsNavigationCommand.STOP -> {
                speak("باشه، خواندن پیام‌ها متوقف شد.") {
                    smsController = null
                    lastMeaningfulState = UiState.Idle
                    _state.value = UiState.Idle
                }
            }
            SmsNavigationCommand.UNKNOWN -> {
                speak("برای پیمایش پیام‌ها بگویید «بعدی»، «قبلی»، «دوباره بخون» یا «متوقف شو».") {
                    _state.value = lastMeaningfulState
                }
            }
        }
    }

    // -------------------- کمکی‌ها --------------------

    private val yesWords = setOf("بله", "آره", "اره", "باشه", "درسته", "بزن", "بله بزن")
    private val noWords = setOf("نه", "خیر", "نکن", "نزن")

    // چون کلماتی مثل «نه» می‌توانند زیررشته کلمات دیگری باشند (مثلاً «خونه»)،
    // به‌جای Contains ساده، جمله به کلمات مجزا شکسته می‌شود و تطبیق دقیق انجام می‌گیرد.
    private fun tokenize(text: String): List<String> =
        text.trim().split(Regex("[\\s،.!؟?]+")).filter { it.isNotBlank() }

    private fun isAffirmative(text: String) = tokenize(text).any { it in yesWords }
    private fun isNegative(text: String) = tokenize(text).any { it in noWords }

    private val ordinalWords = mapOf(
        "یک" to 1, "اول" to 1, "۱" to 1,
        "دو" to 2, "دوم" to 2, "۲" to 2,
        "سه" to 3, "سوم" to 3, "۳" to 3,
        "چهار" to 4, "چهارم" to 4, "۴" to 4,
        "پنج" to 5, "پنجم" to 5, "۵" to 5
    )

    private fun resolveOrdinalChoice(text: String, candidates: List<Contact>): Contact? {
        for ((word, index) in ordinalWords) {
            if (text.contains(word)) {
                return candidates.getOrNull(index - 1)
            }
        }
        return null
    }

    private var speakWatchdog: Job? = null

    /**
     * متن را می‌گوید و پس از پایان، [onDone] را اجرا می‌کند.
     *
     * نکته مهم: اگر موتور TTS اصلاً پاسخ ندهد (مثلاً روی گوشی نصب نباشد)،
     * بدون نگهبان زمانی، وضعیت برای همیشه در Speaking گیر می‌کرد و دکمه
     * میکروفون غیرفعال می‌ماند — یعنی «اپ هیچ کاری نمی‌کند». این watchdog
     * تضمین می‌کند که مکالمه در هر شرایطی ادامه پیدا کند.
     */
    private fun speak(text: String, onDone: () -> Unit) {
        _state.value = UiState.Speaking(text)
        logEvent("گفتن: $text")

        val finished = AtomicBoolean(false)
        val finish: () -> Unit = {
            if (finished.compareAndSet(false, true)) onDone()
        }

        speakWatchdog?.cancel()
        speakWatchdog = viewModelScope.launch {
            delay(8_000)
            if (!finished.get()) {
                logEvent("هشدار: موتور صدا پاسخی نداد؛ بدون صدا ادامه می‌دهیم.")
                finish()
            }
        }

        tts.speak(
            text = text,
            onDone = {
                speakWatchdog?.cancel()
                finish()
            },
            onError = {
                speakWatchdog?.cancel()
                logEvent("خطای پخش صدا (TTS).")
                finish()
            }
        )
    }

    override fun onCleared() {
        stt.destroy()
        tts.destroy()
        super.onCleared()
    }
}

class ConversationViewModelFactory(private val app: DastyarApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ConversationViewModel(
            stt = app.sttProvider,
            tts = app.ttsProvider,
            llm = app.llmProvider,
            contactsResolver = app.contactsResolver,
            callManager = app.callManager,
            smsReader = app.smsReader
        ) as T
    }
}
