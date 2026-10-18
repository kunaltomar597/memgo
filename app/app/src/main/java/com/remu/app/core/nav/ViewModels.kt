package com.remu.app.core.nav

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/** A ViewModel built by hand from the container, with a SavedStateHandle for form state. */
@Composable
inline fun <reified VM : ViewModel> remuViewModel(key: String? = null, crossinline create: (SavedStateHandle) -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create(createSavedStateHandle()) } })
