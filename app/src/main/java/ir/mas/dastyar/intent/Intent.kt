package ir.mas.dastyar.intent

/**
 * خروجی معتبر و ساختاریافته‌ای که تنها این ماژول اجازه تولید آن را دارد.
 * هیچ بخش دیگری از اپ (از جمله LlmProvider) نباید مستقیماً عملیات حساس
 * (تماس / خواندن پیامک) را اجرا کند؛ همه چیز باید از این مدل عبور کند.
 */
sealed class ParsedIntent {

    /** گفتگوی عادی. اگر پاسخ از پیش تولید نشده باشد null است. */
    data class Chat(val reply: String? = null) : ParsedIntent()

    /** درخواست تماس با یک مخاطب؛ contactName خام از گفتار کاربر استخراج شده است. */
    data class CallContact(val contactName: String) : ParsedIntent()

    /** درخواست خواندن پیامک‌های دریافتی. */
    data object ReadSms : ParsedIntent()

    /** خروجی نامعتبر/مبهم از مدل. هیچ عملیات حساسی نباید بر اساس این اجرا شود. */
    data class Invalid(val reason: String) : ParsedIntent()
}

/** دستورات ناوبری هنگام خواندن پیامک؛ اینها هرگز به LLM نیازی ندارند و می‌توانند کاملاً Regex باشند. */
enum class SmsNavigationCommand {
    NEXT, PREVIOUS, REPEAT, STOP, UNKNOWN
}

/**
 * لایه Abstraction اصلی روی «مغز» اپ.
 *
 * قرارداد امنیتی مهم: پیاده‌سازی‌های این اینترفیس هرگز نباید مستقیماً به APIهای
 * حساس اندروید (Contacts/Call/SMS) دسترسی داشته باشند. آنها فقط اجازه دارند
 * یکی از حالت‌های ParsedIntent را برگردانند؛ اجرای واقعی عملیات همیشه در
 * ConversationViewModel و پس از تأیید کاربر (برای CALL_CONTACT) انجام می‌شود.
 *
 * برای CALL_CONTACT فقط نام مخاطب (نه کل متن گفتار، نه دفترچه مخاطبین) باید
 * به این لایه داده شود. برای READ_SMS هیچ متن پیامکی هرگز نباید به این لایه
 * وارد شود.
 *
 * پیاده‌سازی پیش‌فرض MVP: [RuleBasedLlmProvider] (رایگان، آفلاین، بدون هیچ
 * وابستگی خارجی). در آینده می‌توان OnDeviceLlmProvider (مدل کوچک محلی مثل
 * Gemma/Qwen کوانتیزه) یا یک provider ابری اختیاری را جایگزین کرد، بدون
 * نیاز به تغییر بقیه اپ.
 */
interface LlmProvider {

    /**
     * متن گفتار کاربر (خروجی STT) را می‌گیرد و یکی از حالت‌های ParsedIntent
     * را برمی‌گرداند. اگر مدل/قوانین نتوانند با اطمینان تصمیم بگیرند، باید
     * ParsedIntent.Invalid یا ParsedIntent.Chat برگردانده شود — هرگز نباید
     * حدسی برای CALL_CONTACT یا READ_SMS بدون اطمینان کافی زده شود.
     */
    suspend fun classify(userUtterance: String): ParsedIntent

    /** نام قابل‌نمایش provider، برای لاگ/دیباگ. */
    val providerName: String
}

/**
 * پیاده‌سازی پیش‌فرض MVP برای [LlmProvider].
 *
 * چرا Rule-based و نه یک مدل زبانی واقعی؟
 *  - وظیفه این لایه محدود و مشخص است (۳ حالت + استخراج نام)، نه گفت‌وگوی باز؛
 *    بنابراین یک طبقه‌بند مبتنی بر الگو می‌تواند دقیق و قابل‌پیش‌بینی باشد.
 *  - صفر هزینه، صفر وابستگی به شبکه/تحریم، صفر ریسک امنیتی از خروجی غیرمنتظره مدل.
 *  - محافظه‌کارانه است: اگر جمله با هیچ الگوی شناخته‌شده‌ای مطابقت نداشته باشد،
 *    هرگز CALL_CONTACT یا READ_SMS حدس زده نمی‌شود — به CHAT برمی‌گردد.
 *    این دقیقاً رفتاری است که برای جملات مبهم مثل «فردا باید با علی صحبت کنم»
 *    یا «علی رو می‌بینی؟» لازم است (نباید باعث تماس شوند).
 *
 * این پیاده‌سازی طبق قرارداد [LlmProvider] کاملاً قابل تعویض است. در فاز بعدی
 * می‌توان یک provider مدل زبانی محلی/ابری را جایگزین کرد تا کیفیت گفت‌وگوی
 * آزاد (CHAT) بهتر شود؛ تشخیص Intent (بخش حساس امنیتی) می‌تواند حتی در آن
 * حالت هم توسط همین لایه Regex به‌عنوان یک لایه اعتبارسنجی دوم باقی بماند.
 */
class RuleBasedLlmProvider : LlmProvider {

    override val providerName: String = "RuleBasedLlmProvider (آفلاین)"

    override suspend fun classify(userUtterance: String): ParsedIntent {
        val text = normalize(userUtterance)
        if (text.isBlank()) return ParsedIntent.Invalid("متن خالی است")

        extractCallContactName(text)?.let { name ->
            return ParsedIntent.CallContact(name)
        }

        if (isReadSmsRequest(text)) {
            return ParsedIntent.ReadSms
        }

        return ParsedIntent.Chat(reply = cannedReply(text))
    }

    /** حذف نیم‌فاصله/فاصله اضافه و یکسان‌سازی برای تطبیق ساده‌تر Regex. */
    private fun normalize(input: String): String {
        return input
            .trim()
            .replace('‌', ' ') // نیم‌فاصله -> فاصله معمولی برای سادگی تطبیق
            .replace(Regex("\\s+"), " ")
    }

    // -------------------- CALL_CONTACT --------------------

    private val callPatterns: List<Regex> = listOf(
        // «به علی زنگ بزن»، «لطفاً به علی زنگ بزن»
        Regex("به\\s+([آ-ی ]{2,30}?)\\s+(?:زنگ\\s*بزن|تماس\\s*بگیر)"),
        // «با محمد تماس بگیر»، «می‌تونی با علی تماس بگیری؟»
        Regex("با\\s+([آ-ی ]{2,30}?)\\s+تماس\\s*بگیر"),
        // «می‌خوام با علی صحبت کنم»، «می‌خوام با مامانم صحبت کنم»
        Regex("می\\s*خوا(?:م|هم)\\s+با\\s+([آ-ی ]{2,30}?)\\s+صحبت\\s*کن"),
        // «با علی صحبت کنم» به همراه یک فعل خواستن نزدیک به آن (بدون فاصله زیاد)
        Regex("(?:میشه|میشه که)\\s+با\\s+([آ-ی ]{2,30}?)\\s+تماس\\s*بگیر")
    )

    /** الگوهایی که با وجودشان، حتی اگر فعل تماس هم دیده شود، نباید CALL_CONTACT برگردد. */
    private val obligationOrReportedSpeechMarkers = listOf(
        "باید", "قراره", "قرار بود", "دیروز", "فردا", "هفته دیگه", "بعداً", "بعدا"
    )

    private fun extractCallContactName(text: String): String? {
        if (obligationOrReportedSpeechMarkers.any { text.contains(it) }) {
            // مثل «فردا باید با علی صحبت کنم» — این یک دستور مستقیم نیست.
            return null
        }

        for (pattern in callPatterns) {
            val match = pattern.find(text) ?: continue
            val rawName = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cleaned = cleanContactName(rawName)
            if (cleaned.isNotBlank()) return cleaned
        }
        return null
    }

    private fun cleanContactName(raw: String): String {
        // کلماتی که ممکن است اشتباهی داخل گروه Regex بیفتند را حذف می‌کنیم.
        val stopWords = setOf("لطفا", "لطفاً", "میشه", "میشه که", "یه", "رو")
        return raw
            .split(" ")
            .filter { it.isNotBlank() && it !in stopWords }
            .joinToString(" ")
            .trim()
    }

    // -------------------- READ_SMS --------------------

    // توجه: چون normalize() نیم‌فاصله را به فاصله معمولی تبدیل می‌کند، این کلیدواژه‌ها
    // هم باید بدون نیم‌فاصله (با فاصله ساده) نوشته شوند تا با متن نرمال‌شده تطبیق یابند.
    private val readSmsKeywords = listOf("پیام جدید", "پیامک جدید", "پیام هام", "پیامام")
    private val readSmsVerbHints = listOf("بخون", "دارم", "چیه")

    private fun isReadSmsRequest(text: String): Boolean {
        val mentionsMessages = text.contains("پیام") || text.contains("اس ام اس") || text.contains("پیامک")
        if (!mentionsMessages) return false
        return readSmsKeywords.any { text.contains(it) } ||
            (mentionsMessages && readSmsVerbHints.any { text.contains(it) })
    }

    // -------------------- CHAT (پاسخ‌های ساده از پیش‌تعریف‌شده) --------------------

    private fun cannedReply(text: String): String {
        return when {
            text.contains("سلام") -> "سلام! خوبم، ممنون. چه کاری از دستم برمیاد؟"
            text.contains("خوبی") -> "خوبم، مرسی که پرسیدی. شما چطورید؟"
            text.contains("ممنون") || text.contains("متشکر") -> "خواهش می‌کنم."
            text.contains("خداحافظ") -> "خداحافظ، هر وقت کاری داشتی صدام کن."
            else -> "متوجه نشدم دقیقاً چه کاری می‌خواهید. می‌توانید بگویید «به … زنگ بزن» یا «پیام‌هام رو بخون»."
        }
    }
}
