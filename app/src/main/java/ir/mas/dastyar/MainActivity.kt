package ir.mas.dastyar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import ir.mas.dastyar.core.ConversationViewModelFactory
import ir.mas.dastyar.ui.MainScreen
import ir.mas.dastyar.ui.PermissionsGate
import ir.mas.dastyar.ui.theme.DastyarTheme

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<ir.mas.dastyar.core.ConversationViewModel> {
        ConversationViewModelFactory(application as DastyarApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DastyarTheme {
                PermissionsGate {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
