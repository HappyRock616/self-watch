/*
 * Copyright (C) 2026 Self Watch contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version. See the LICENSE file.
 */
package app.selfwatch.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.selfwatch.AppGraph
import app.selfwatch.db.SettingKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Shell state: the selected tab (kept across process death) and whether food tracking is on. */
class MainViewModel(
    private val saved: SavedStateHandle,
    foodEnabled: Flow<Boolean>,
) : ViewModel() {

    private val tabFlow = MutableStateFlow(saved.get<String>(KEY_TAB)?.let(::tabOrNull) ?: Tab.BODY)

    /** The selected tab. */
    val tab: StateFlow<Tab> = tabFlow.asStateFlow()

    /** Food tracking switch (on until the setting is read). */
    val foodEnabled: StateFlow<Boolean> = foodEnabled
        .catch { emit(true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** Selects [tab]. */
    fun select(tab: Tab) {
        tabFlow.value = tab
        saved[KEY_TAB] = tab.name
    }

    /** Factory with the graph. */
    companion object {
        private const val KEY_TAB = "tab"
        private const val STOP_TIMEOUT_MS = 5_000L

        private fun tabOrNull(name: String): Tab? = Tab.entries.firstOrNull { it.name == name }

        /** Builds the view model from [graph]. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    createSavedStateHandle(),
                    graph.settings.observe(SettingKeys.FOOD_ENABLED).map { it?.toBooleanStrictOrNull() ?: true },
                )
            }
        }
    }
}
