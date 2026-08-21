package ir.mas.dastyar.calling

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

sealed class CallResult {
    data object Placed : CallResult()
    data object NoPermission : CallResult()
    data object Failed : CallResult()
}

/**
 * برقراری مستقیم تماس. طبق تحلیل امکان‌سنجی، CALL_PHONE برخلاف READ_SMS
 * نیازی به تبدیل‌شدن اپ به Default Dialer ندارد؛ فقط پرمیشن دنجروس معمولی
 * لازم است.
 *
 * این تابع هرگز نباید مستقیماً از روی خروجی خام LLM صدا زده شود — همیشه باید
 * پس از عبور از ConversationStateMachine و تأیید صریح صوتی کاربر فراخوانی شود.
 */
class CallManager(private val context: Context) {

    fun placeCall(phoneNumber: String): CallResult {
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return CallResult.NoPermission
        }

        return try {
            // شماره تلفن مستقیماً در tel: قرار می‌گیرد (بدون Uri.encode) چون '+' برای
            // کدهای بین‌المللی باید دست‌نخورده باقی بماند.
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            CallResult.Placed
        } catch (e: SecurityException) {
            CallResult.NoPermission
        } catch (e: Exception) {
            CallResult.Failed
        }
    }
}
