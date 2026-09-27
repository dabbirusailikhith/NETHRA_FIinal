package com.nethra.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nethra.app.AppContainer
import com.nethra.app.NethraApplication

/**
 * ViewModels live at activity scope (there is no navigation library), so a
 * brief, script or transcript survives moving between screens.
 */
@Composable
inline fun <reified VM : ViewModel> nethraViewModel(crossinline create: (NethraApplication, AppContainer) -> VM): VM {
    val app = LocalContext.current.applicationContext as NethraApplication
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create(app, app.container) as T
    })
}
