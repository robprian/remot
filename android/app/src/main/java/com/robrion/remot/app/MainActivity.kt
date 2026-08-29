package com.robrion.remot

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robrion.remot.services.ServiceStatus
import com.robrion.remot.ui.AppRoot
import com.robrion.remot.ui.theme.RemotTheme

class MainActivity : ComponentActivity() {

    private var viewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge on Android 15/16 draws behind the status/navigation bars
        // by default; we must handle insets in Compose (see AppRoot) so content
        // never hides under the system UI.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            RemotTheme {
                val vm: MainViewModel = viewModel<MainViewModel>().also { viewModel = it }

                // Notification permission (Android 13+)
                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { /* result handled implicitly */ }

                // MediaProjection consent -> on OK, start hosting for the pending controller
                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK && result.data != null) {
                        vm.approveIncoming(result.resultCode, result.data!!)
                    } else {
                        vm.declineIncoming()
                    }
                }

                AppRoot(
                    vm = vm,
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onAllowIncoming = {
                        val mpm = getSystemService(MediaProjectionManager::class.java)
                        projectionLauncher.launch(mpm.createScreenCaptureIntent())
                    },
                    onOpenAccessibilitySettings = { ServiceStatus.openAccessibilitySettings(this) },
                    onOpenNotificationSettings = { ServiceStatus.openNotificationListenerSettings(this) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start health polling + re-read real service state whenever the app
        // returns to the foreground (including returning from Settings).
        viewModel?.onForeground()
    }

    override fun onPause() {
        // Stop polling when backgrounded — never hammer the network in the
        // background, never leak coroutines.
        viewModel?.onBackground()
        super.onPause()
    }
}
