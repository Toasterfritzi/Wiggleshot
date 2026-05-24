package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.data.WiggleDatabase
import com.example.data.WiggleRepository
import com.example.ui.WiggleApp
import com.example.ui.WiggleViewModel
import com.example.ui.WiggleViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val database = WiggleDatabase.getDatabase(applicationContext)
        val repository = WiggleRepository(database.wiggleDao())
        
        val viewModel: WiggleViewModel by viewModels {
            WiggleViewModelFactory(repository)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WiggleApp(viewModel = viewModel)
                }
            }
        }
    }
}
