package com.synctra.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.synctra.app.ui.SynctraScreen
import com.synctra.app.ui.theme.SynctraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SynctraTheme {
                SynctraScreen()
            }
        }
    }
}
