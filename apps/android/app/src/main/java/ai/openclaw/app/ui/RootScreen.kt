package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun RootScreen(viewModel: MainViewModel) {
  // Skip onboarding — always show main shell
  ShellScreen(viewModel = viewModel, modifier = Modifier.fillMaxSize())
}
