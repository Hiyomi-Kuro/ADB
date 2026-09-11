package com.kaori.adb

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kaori.adb.service.FloatingBubbleService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            finishAndRemoveTask()
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, FloatingBubbleService::class.java)
        )
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
