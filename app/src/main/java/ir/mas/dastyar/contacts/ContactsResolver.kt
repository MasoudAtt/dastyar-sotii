package ir.mas.dastyar.contacts

import android.content.Context
import android.provider.ContactsContract

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
 */
class ContactsResolver(private val context: Context) {

    fun findByName(rawName: String): ContactLookupResult {
        val query = rawName.trim()
        if (query.isBlank()) return ContactLookupResult.NoMatch

        val matches = queryContacts(query)
        return when {
            matches.isEmpty() -> ContactLookupResult.NoMatch
            matches.size == 1 -> ContactLookupResult.SingleMatch(matches.first())
            else -> ContactLookupResult.MultipleMatches(matches)
        }
    }

    private fun queryContacts(nameQuery: String): List<Contact> {
        val results = mutableListOf<Contact>()
        val resolver = context.contentResolver

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
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

        return results
    }
}
