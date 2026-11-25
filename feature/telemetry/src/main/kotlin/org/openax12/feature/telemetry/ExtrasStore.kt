package org.openax12.feature.telemetry

import kotlinx.coroutines.flow.StateFlow
import org.openax12.core.data.ModelRepository
import org.openax12.core.model.ModelExtras

/**
 * The per-model data this module edits (telemetry layout, sensor overrides, alarms). Narrow on
 * purpose so the view models can be tested without the whole model repository.
 *
 * Changes are persisted at once and never make the model setup "dirty" (CONTRACTS.md, "Typical
 * flows": editing the telemetry layout uses `ModelRepository.updateExtras`).
 */
internal interface ExtrasStore {

    /** Extras of the model that is loaded right now. */
    val extras: StateFlow<ModelExtras>

    /** Applies [transform] to the extras and persists them. */
    suspend fun update(transform: (ModelExtras) -> ModelExtras)
}

/** [ExtrasStore] over the model repository. */
internal class ModelExtrasStore(private val models: ModelRepository) : ExtrasStore {
    override val extras: StateFlow<ModelExtras> get() = models.currentExtras

    override suspend fun update(transform: (ModelExtras) -> ModelExtras) {
        models.updateExtras(transform)
    }
}
