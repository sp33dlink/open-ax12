package org.openax12.feature.models

import kotlinx.coroutines.flow.MutableStateFlow
import org.openax12.core.data.DataResult
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.ModelRepository
import org.openax12.core.data.StorageInfo
import org.openax12.core.model.ModelExtras
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.ConfigSession
import org.openax12.core.runtime.SaveState
import java.lang.reflect.Proxy

/**
 * Interface fake built with a dynamic proxy: only the functions that a test registers exist,
 * every other call fails the test. Suspend functions work because the proxy receives the
 * continuation as an extra argument and simply returns the value.
 */
internal inline fun <reified T : Any> proxyFake(crossinline lookup: (String) -> ((List<Any?>) -> Any?)?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val handler = lookup(method.name) ?: error("Unexpected call of ${T::class.simpleName}.${method.name}")
        // Drop the Continuation of suspend functions.
        handler((args ?: emptyArray()).toList().filter { it !is kotlin.coroutines.Continuation<*> })
    } as T

/** A [ModelRepository] fake with the state flows the view models read. */
internal class RepoFake(models: List<ModelEntry> = emptyList()) {
    val models = MutableStateFlow(models)
    val calls = mutableListOf<String>()
    val handlers = mutableMapOf<String, (List<Any?>) -> Any?>()

    val repository: ModelRepository by lazy {
        val base: Map<String, (List<Any?>) -> Any?> = mapOf(
            "getModels" to { _ -> this.models },
            "getStorage" to { _ -> MutableStateFlow(StorageInfo(libraryRoot = "/x")) },
            "getExternalChanges" to { _ -> MutableStateFlow(emptyList<Any>()) },
            "readExtras" to { _ -> DataResult.Success(ModelExtras()) },
        )
        proxyFake<ModelRepository> { handlers[it] ?: base[it] }
    }
}

/** A [ConfigSession] fake. */
internal class SessionFake {
    val dirty = MutableStateFlow(false)
    val deviceInSync = MutableStateFlow(true)
    val workingConfig = MutableStateFlow(WorkingConfig.EMPTY)
    val calls = mutableListOf<String>()
    val handlers = mutableMapOf<String, (List<Any?>) -> Any?>()

    val session: ConfigSession by lazy {
        val base: Map<String, (List<Any?>) -> Any?> = mapOf(
            "getDirty" to { _ -> dirty },
            "getDeviceInSync" to { _ -> deviceInSync },
            "getWorkingConfig" to { _ -> workingConfig },
            "getSaveState" to { _ -> MutableStateFlow<SaveState>(SaveState.Idle) },
        )
        proxyFake<ConfigSession> { handlers[it] ?: base[it] }
    }
}
