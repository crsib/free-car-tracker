package me.crsib.freecartracker.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import me.crsib.freecartracker.CarTrackerApp
import me.crsib.freecartracker.di.AppContainer

/** Builds a [ViewModel] wired with the app's [AppContainer], scoped to the current nav entry. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as CarTrackerApp).container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
