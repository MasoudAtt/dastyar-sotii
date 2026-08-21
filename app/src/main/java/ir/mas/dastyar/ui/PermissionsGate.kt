package ir.mas.dastyar.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.mas.dastyar.R

/** پرمیشن‌های ضروری برای عملکرد اصلی اپ (طبق سند امکان‌سنجی). */
val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS
)

/**
 * تا زمانی که همه پرمیشن‌های ضروری اعطا نشده باشند، محتوای اصلی اپ نمایش
 * داده نمی‌شود؛ در عوض یک صفحه توضیح ساده و دکمه بزرگ «اعطای دسترسی‌ها» نشان داده می‌شود.
 */
@Composable
fun PermissionsGate(content: @Composable () -> Unit) {
    val context = LocalContext.current

    fun allGranted(): Boolean = REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = allGranted()
    }

    if (granted) {
        content()
        return
    }

    val grantButtonLabel = stringResource(R.string.perm_grant_button)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.perm_rationale_title),
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = stringResource(R.string.perm_rationale_body),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
        )
        Button(
            onClick = { launcher.launch(REQUIRED_PERMISSIONS) },
            modifier = Modifier.semantics { contentDescription = grantButtonLabel }
        ) {
            Text(text = grantButtonLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}
