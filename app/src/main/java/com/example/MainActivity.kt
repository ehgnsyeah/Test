package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.cad.data.CadDatabase
import com.example.cad.data.ProjectRepository
import com.example.cad.ui.CadScreen
import com.example.cad.ui.CadViewModel
import com.example.cad.ui.CadViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // Initialize Database & Repository
    val database = CadDatabase.getDatabase(this)
    val repository = ProjectRepository(database.projectDao())

    setContent {
      // Modern Material 3 Theme Integration
      MyApplicationTheme(darkTheme = true) { // Set to dark theme for night CAD layout comfort by default
        val viewModel = androidx.lifecycle.viewmodel.compose.viewModel<CadViewModel>(
          factory = CadViewModelFactory(application, repository)
        )
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          CadScreen(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
      }
    }
  }
}
