package com.geeksville.mesh.convoy

import androidx.compose.runtime.mutableStateOf

/**
 * CARTLIST2-2026-09-28 (Fred): the SELECT CART picker, callable from anywhere -- the check-in opens it, REC closes it,
 * the SELECT CART button opens it, and later Group Com or any screen can. The panel itself is unchanged; only its
 * on/off switch lives here (same pattern as RadioConfigLauncher / RidesLauncher).
 */
object CartPickerLauncher {
    val showing = mutableStateOf(false)
    fun open() { showing.value = true }
    fun close() { showing.value = false }
}
