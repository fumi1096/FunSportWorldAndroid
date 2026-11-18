package com.funsportworld.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.funsportworld.android.ui.MainScaffold
import com.funsportworld.android.ui.theme.FunSportTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FunSportTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScaffold()
                }
            }
        }
    }
}
