package io.clarionchain.keel

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.clarionchain.keel.ui.KeelRoot
import io.clarionchain.keel.ui.theme.KeelTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeelTheme {
                KeelRoot()
            }
        }
    }
}
