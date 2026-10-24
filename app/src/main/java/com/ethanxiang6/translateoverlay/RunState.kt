package com.ethanxiang6.translateoverlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lets the app screen show whether the overlay service is running. */
object RunState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    fun set(value: Boolean) {
        _running.value = value
    }
}
