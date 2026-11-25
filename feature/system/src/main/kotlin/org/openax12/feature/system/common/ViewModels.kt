package org.openax12.feature.system.common

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openax12.core.runtime.AppGraph
import org.openax12.core.radio.Radio

/**
 * Creates (or finds) the ViewModel of a screen with its dependencies taken from [graph]. There is
 * no DI framework: [create] is the only place that knows which graph members the ViewModel needs.
 */
@Composable
internal inline fun <reified VM : ViewModel> graphViewModel(
    graph: AppGraph,
    crossinline create: (AppGraph) -> VM,
): VM = viewModel(
    key = "system." + VM::class.java.simpleName,
    factory = viewModelFactory { initializer { create(graph) } },
)

/** True while the receiver link is up (LQ > 0). Cheap: it only emits on change. */
internal fun Radio.linkUpFlow(): Flow<Boolean> = live.map { it.status.isLinkUp }.distinctUntilChanged()

/** Atomic update helper for a [MutableStateFlow] holding an immutable value. */
internal inline fun <T> MutableStateFlow<T>.edit(transform: (T) -> T) {
    while (true) {
        val old = value
        if (compareAndSet(old, transform(old))) return
    }
}
