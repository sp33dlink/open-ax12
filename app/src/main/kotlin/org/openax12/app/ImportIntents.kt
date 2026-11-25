package org.openax12.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The model archive an incoming intent carries, as a URI string for `ModelImport`: the data of an
 * `ACTION_VIEW` or the `EXTRA_STREAM` of an `ACTION_SEND`. Null for every other intent (launcher,
 * notification). The file is only validated by the importer, never by this filter.
 */
internal fun importUriOf(intent: Intent?): String? = when (intent?.action) {
    Intent.ACTION_VIEW -> intent.data
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    else -> null
}?.toString()
