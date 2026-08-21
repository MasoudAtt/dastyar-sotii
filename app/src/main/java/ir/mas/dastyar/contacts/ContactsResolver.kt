package ir.mas.dastyar.contacts

import android.content.Context
import android.provider.ContactsContract
import ir.mas.dastyar.intent.PersianText

data class Contact(
    val displayName: String,
    val phoneNumber: String
)

sealed class ContactLookupResult {
    data class SingleMatch(val contact: Contact) : ContactLookupResult()
    data class MultipleMatches(val contacts: List<Contact>) : ContactLookupResult()
    data object NoMatch : ContactLookupResult()
}

/**
 * جست‌وجوی مخاطب در Contacts Provider اندروید بر اساس نامی که از گفتار
 * کاربر استخراج شده است. این کلاس تنها بخشی از اپ است که مستقیماً به
 * دفترچه مخاطبین دسترسی دارد؛ نتیجه هرگز به LLM بازگردانده نمی‌شود.
 *
 * جست‌وجو دو مرحله‌ای است، چون تطبیق ساده SQL برای فارسی کافی نیست:
 *  ۱) LIKE مستقیم روی نام (سریع، حالت عادی).
 *  ۲) اگر چیزی پیدا نشد، همه مخاطبین خوانده و با نام «یکسان‌سازی‌شده»
 *     مقایسه می‌شوند — تا تفاوت‌های ی/ي، ک/ك، آ/ا، نیم‌فاصله و فاصله
 *     باعث شکست جست‌وجو نشود (مثلاً «مریم صادقی» در برابر «مریم‌صادقی»).
 */
class ContactsResolver(private val context: Context) {

    fun findByName(rawName: String): ContactLookupResult {
        val query = rawName.trim()
        if (query.isBlank()) return ContactLookupResult.NoMatch

        val direct = queryContacts(query)
        if (direct.isNotEmpty()) return toResult(direct)

        val folded = PersianText.foldForComparison(query)
        if (folded.length < 2) return ContactLookupResult.NoMatch

        val fuzzy = queryContacts(null).filter { contact ->
            PersianText.foldForComparison(contact.displayName).contains(folded)
        }
        return toResult(fuzzy)
    }

    private fun toResult(matches: List<Contact>): ContactLookupResult = when {
        matches.isEmpty() -> ContactLookupResult.NoMatch
        matches.size == 1 -> ContactLookupResult.SingleMatch(matches.first())
        else -> ContactLookupResult.MultipleMatches(matches.take(5))
    }

    /** اگر [nameQuery] برابر null باشد، همه مخاطبین دارای شماره خوانده می‌شوند. */
    private fun queryContacts(nameQuery: String?): List<Contact> {
        val results = mutableListOf<Contact>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = if (nameQuery == null) null
        else "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = if (nameQuery == null) null else arrayOf("%$nameQuery%")

        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameIdx =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seenNumbers = mutableSetOf<String>()

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val number = if (numberIdx >= 0) cursor.getString(numberIdx) else null
                    if (name.isNullOrBlank() || number.isNullOrBlank()) continue

                    // از تکرار همان شماره (مثلاً چند حساب همگام‌شده) جلوگیری می‌کنیم.
                    val normalizedNumber = number.filter { it.isDigit() || it == '+' }
                    if (normalizedNumber in seenNumbers) continue
                    seenNumbers += normalizedNumber

                    results += Contact(displayName = name, phoneNumber = number)
                }
            }
        }

        return results
    }

    /** تعداد کل مخاطبین دارای شماره — فقط برای گزارش وضعیت/عیب‌یابی. */
    fun countAll(): Int = queryContacts(null).size
}
