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
 * یکسان‌سازی متن فارسی پیش از تطبیق الگو.
 *
 * چرا لازم است: موتور تشخیص گفتار گاهی حروف عربی (ي/ك) برمی‌گرداند، گاهی
 * نیم‌فاصله می‌گذارد، و فاصله‌ها یکدست نیستند. بدون این مرحله، الگوها روی
 * گوشی واقعی شکست می‌خورند حتی وقتی متن برای چشم انسان درست به‌نظر می‌رسد.
 *
 * توجه: «آ» عمداً به «ا» تبدیل نمی‌شود، چون نام مخاطب استخراج‌شده بعداً برای
 * جست‌وجو در دفترچه مخاطبین استفاده می‌شود و این تبدیل «آرش» را خراب می‌کند.
 */
object PersianText {

    fun normalize(input: String): String {
        return input
            .trim()
            .replace('‌', ' ')      // نیم‌فاصله -> فاصله
            .replace('ي', 'ی') // ي عربی -> ی فارسی
            .replace('ك', 'ک') // ك عربی -> ک فارسی
            .replace(Regex("[\\u064B-\\u0652]"), "")   // اعراب
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E]"), "") // نویسه‌های کنترل جهت
            .replace(Regex("\\s+"), " ")
    }

    /** نسخه «فشرده» برای مقایسه نام‌ها: بدون فاصله و بدون تفاوت آ/ا و ه/ة. */
    fun foldForComparison(input: String): String {
        return normalize(input)
            .replace('آ', 'ا') // آ -> ا
            .replace('ة', 'ه') // ة -> ه
            .replace(Regex("\\s"), "")
            .lowercase()
    }
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
 */
interface LlmProvider {

    /**
     * متن گفتار کاربر (خروجی STT) را می‌گیرد و یکی از حالت‌های ParsedIntent
     * را برمی‌گرداند. اگر مدل/قوانین نتوانند با اطمینان تصمیم بگیرند، باید
     * ParsedIntent.Invalid یا ParsedIntent.Chat برگردانده شود — هرگز نباید
     * حدسی برای CALL_CONTACT بدون اطمینان کافی زده شود.
     */
    suspend fun classify(userUtterance: String): ParsedIntent

    /** نام قابل‌نمایش provider، برای لاگ/دیباگ. */
    val providerName: String
}

/**
 * پیاده‌سازی پیش‌فرض MVP برای [LlmProvider].
 *
 * دو سطح سخت‌گیری متفاوت، عمدی:
 *  - CALL_CONTACT (پرریسک: یک تماس واقعی برقرار می‌شود) → فقط با الگوی صریح
 *    فعل تماس، و با فیلتر جملات غیردستوری مثل «فردا باید با علی صحبت کنم».
 *  - READ_SMS (کم‌ریسک: فقط بلند خوانده می‌شود) → کافی است کاربر به پیام
 *    اشاره کند. سخت‌گیری بیش از حد اینجا فقط باعث می‌شود اپ بی‌فایده شود.
 *
 * نکته‌ای که در تست روی گوشی واقعی مشخص شد: موتور تشخیص گفتار فارسیِ رسمی
 * برمی‌گرداند («بخوان»، «پیغام»)، نه محاوره‌ای («بخون»، «پیام»). هر دو شکل
 * باید پشتیبانی شوند.
 */
class RuleBasedLlmProvider : LlmProvider {

    override val providerName: String = "RuleBasedLlmProvider (آفلاین)"

    override suspend fun classify(userUtterance: String): ParsedIntent {
        val text = PersianText.normalize(userUtterance)
        if (text.isBlank()) return ParsedIntent.Invalid("متن خالی است")

        // ۱) ارسال پیامک پشتیبانی نمی‌شود — ولی باید پاسخ روشن داده شود،
        //    نه اینکه اشتباهاً به «خواندن پیامک» تعبیر شود.
        if (mentionsMessage(text) && sendVerbPattern.containsMatchIn(text)) {
            return ParsedIntent.Chat("فعلاً فقط می‌توانم پیامک‌های دریافتی را بخوانم؛ توانایی فرستادن پیامک را ندارم.")
        }

        // ۲) تماس (حساس‌ترین عملیات)
        extractCallContactName(text)?.let { name ->
            return ParsedIntent.CallContact(name)
        }

        // ۳) خواندن پیامک
        if (mentionsMessage(text)) {
            return ParsedIntent.ReadSms
        }

        return ParsedIntent.Chat(reply = cannedReply(text))
    }

    // -------------------- CALL_CONTACT --------------------

    /** بخش «نام» در الگوها: فقط حروف فارسی و فاصله، بین ۲ تا ۳۰ نویسه. */
    private val namePart = "([\\u0621-\\u06CC ]{2,30}?)"

    /**
     * فعل‌های تماس، هم محاوره‌ای هم رسمی.
     * ترتیب گزینه‌ها مهم است: شکل بلندتر باید اول بیاید تا در الگوهایی که
     * بعد از فعل چیزی می‌آید («زنگ بزن به علی») کل فعل مصرف شود.
     */
    private val callVerb =
        "(?:زنگ\\s*(?:بزنید|بزنی|بزن)|تماس\\s*(?:بگیرید|بگیری|بگیر|بده)|تلفن\\s*(?:بزنید|بزن|کن))"

    private val callPatterns: List<Regex> = listOf(
        // «به علی زنگ بزن» / «با محمد تماس بگیر» / «به مامان تلفن بزن»
        Regex("(?:به|با)\\s+$namePart\\s+$callVerb"),
        // «زنگ بزن به علی» / «تماس بگیر با محمد»
        Regex("$callVerb\\s+(?:به|با)\\s+$namePart"),
        // «شماره علی را بگیر»
        Regex("شماره\\s+$namePart\\s+(?:را|رو)?\\s*بگیر"),
        // «می‌خواهم با علی صحبت کنم» / «می‌خوام با مامان حرف بزنم»
        Regex("می\\s*خوا(?:م|هم|هد)\\s+با\\s+$namePart\\s+(?:صحبت|حرف)\\s*(?:کن|بزن)")
    )

    /**
     * الگوهایی که با وجودشان، حتی اگر فعل تماس هم دیده شود، نباید CALL_CONTACT برگردد.
     * مثال: «فردا باید با علی صحبت کنم» یک گزارش است، نه یک دستور.
     */
    private val obligationOrReportedSpeechMarkers = listOf(
        "باید", "قراره", "قرار بود", "قرار است", "دیروز", "فردا",
        "هفته دیگه", "هفته آینده", "بعدا", "بعداً", "قبلا", "قبلاً"
    )

    private fun extractCallContactName(text: String): String? {
        if (obligationOrReportedSpeechMarkers.any { text.contains(it) }) return null

        for (pattern in callPatterns) {
            val match = pattern.find(text) ?: continue
            // بسته به الگو، نام می‌تواند در گروه ۱ باشد (تنها گروه ثبت‌شده در همه الگوها).
            val rawName = match.groupValues.getOrNull(1)?.trim().orEmpty()
            val cleaned = cleanContactName(rawName)
            if (cleaned.length >= 2) return cleaned
        }
        return null
    }

    private val nameStopWords = setOf(
        "لطفا", "لطفاً", "میشه", "می", "شه", "که", "یه", "یک",
        "را", "رو", "الان", "زود", "سریع", "آقای", "خانم", "جناب"
    )

    private fun cleanContactName(raw: String): String {
        return raw
            .split(" ")
            .filter { it.isNotBlank() && it !in nameStopWords }
            .joinToString(" ")
            .trim()
    }

    // -------------------- READ_SMS --------------------

    /**
     * اشاره به «پیام» در همه شکل‌های رایج.
     * «پیغام» عمداً جداگانه آمده — زیررشته «پیام» نیست (پ-ی-غ-ا-م در برابر پ-ی-ا-م)
     * و در تست واقعی دقیقاً همین باعث شد دستور کاربر تشخیص داده نشود.
     */
    private val messageNouns = listOf(
        "پیامک", "پیام", "پیغام", "اس ام اس", "اسمس", "sms", "SMS", "مسیج"
    )

    private val sendVerbPattern = Regex("(?:بفرست|ارسال\\s*کن|بنویس|می\\s*خوام\\s*بفرستم)")

    private fun mentionsMessage(text: String): Boolean =
        messageNouns.any { text.contains(it) }

    // -------------------- CHAT (پاسخ‌های ساده از پیش‌تعریف‌شده) --------------------

    private fun cannedReply(text: String): String {
        return when {
            text.contains("سلام") || text.contains("درود") ->
                "سلام! چه کاری از دستم برمیاد؟"
            text.contains("خوبی") || text.contains("چطوری") ->
                "خوبم، مرسی که پرسیدی. شما چطورید؟"
            text.contains("ممنون") || text.contains("متشکر") || text.contains("مرسی") ->
                "خواهش می‌کنم."
            text.contains("خداحافظ") || text.contains("بای") ->
                "خداحافظ، هر وقت کاری داشتی صدام کن."
            text.contains("اسمت") || text.contains("کی هستی") ->
                "من دستیار صوتی شما هستم. می‌توانم تماس بگیرم یا پیامک‌ها را بخوانم."
            text.contains("چیکار") || text.contains("چه کار") || text.contains("کمک") ->
                "می‌توانید بگویید «به علی زنگ بزن» یا «پیام‌هایم را بخوان»."
            text.contains("ساعت") ->
                "متأسفانه هنوز نمی‌توانم ساعت را بگویم."
            else ->
                "متوجه نشدم. می‌توانید بگویید «به … زنگ بزن» یا «پیام‌هایم را بخوان»."
        }
    }
}
