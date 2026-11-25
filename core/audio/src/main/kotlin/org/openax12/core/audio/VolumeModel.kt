package org.openax12.core.audio

import org.openax12.core.model.AudioCategory

/**
 * Master and per-category volumes. Thread-safe; pure (no Android).
 *
 * The effective linear gain of a category is `taper(master × category)` where master is the
 * override when one is set. The taper is quadratic, which is close enough to the ear's
 * logarithmic response that a 50 % slider sounds roughly half as loud.
 */
internal class VolumeModel {

    private val categories = IntArray(AudioCategory.entries.size) { 100 }
    private var master = 100
    private var masterOverride: Int? = null

    /** Sets the volume of [category] in percent; returns true if it changed. */
    @Synchronized
    fun setCategory(category: AudioCategory, percent: Int): Boolean {
        val clamped = percent.coerceIn(0, 100)
        if (categories[category.ordinal] == clamped) return false
        categories[category.ordinal] = clamped
        return true
    }

    /** Sets the master volume in percent; returns true if it changed. */
    @Synchronized
    fun setMaster(percent: Int): Boolean {
        val clamped = percent.coerceIn(0, 100)
        if (master == clamped) return false
        master = clamped
        return true
    }

    /** Overrides the master volume (null = no override); returns true if it changed. */
    @Synchronized
    fun setOverride(percent: Int?): Boolean {
        val clamped = percent?.coerceIn(0, 100)
        if (masterOverride == clamped) return false
        masterOverride = clamped
        return true
    }

    /** Volume of [category] in percent, without master. */
    @Synchronized
    fun category(category: AudioCategory): Int = categories[category.ordinal]

    /** The master volume in effect (override or master) in percent. */
    @Synchronized
    fun effectiveMaster(): Int = masterOverride ?: master

    /** Linear amplitude gain (0..1) for [category]. */
    @Synchronized
    fun gain(category: AudioCategory): Float =
        taper((masterOverride ?: master) / 100f * (categories[category.ordinal] / 100f))

    companion object {
        /** Maps a 0..1 slider position to a linear amplitude gain. */
        fun taper(position: Float): Float {
            if (position.isNaN()) return 0f
            val clamped = position.coerceIn(0f, 1f)
            return clamped * clamped
        }
    }
}
