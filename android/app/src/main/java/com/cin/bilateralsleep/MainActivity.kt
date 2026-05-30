package com.cin.bilateralsleep

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cin.bilateralsleep.ui.SleepScreen
import com.cin.bilateralsleep.ui.theme.BilateralSleepTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way, playback works */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            BilateralSleepTheme {
                SleepScreen()
            }
        }
    }

    /**
     * The foreground-service notification is OS-mandated; on Android 13+ it is
     * only visible with this runtime permission. Denial does not stop playback.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
