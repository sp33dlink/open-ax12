package org.openax12.feature.telemetry

import androidx.annotation.StringRes
import android.content.Context

/**
 * A one-shot message from a view model to the screen (shown as a toast). Carries the string
 * resource, not the text, so the view models stay free of a `Context`.
 */
internal data class UiMessage(
    @param:StringRes val res: Int,
    val args: List<Any> = emptyList(),
    val isError: Boolean = false,
) {
    /** The message text in the language of [context]. */
    fun resolve(context: Context): String = context.getString(res, *args.toTypedArray())
}
