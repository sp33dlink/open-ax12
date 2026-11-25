package org.openax12.core.data

import android.content.Context
import androidx.core.content.FileProvider

/**
 * The `FileProvider` through which exported models (`.oax12`), backups and flight logs are
 * shared. Declared in this module's manifest with the authority `<applicationId>.openax12.files`;
 * a subclass of its own so that it cannot clash with another `FileProvider` of the app.
 *
 * Shareable locations (`res/xml/openax12_file_paths.xml`): the share cache
 * (`<cache>/share/`) and the library root in both of its possible places.
 */
class OpenAx12FileProvider : FileProvider(R.xml.openax12_file_paths) {

    companion object {
        /** Directory inside the app's cache that holds files prepared for sharing. */
        const val SHARE_DIR: String = "share"

        /** MIME type used for exported model archives and backups. */
        const val MIME_ZIP: String = "application/zip"

        /** MIME type of flight logs. */
        const val MIME_CSV: String = "text/csv"

        /** The provider's authority for the app [context] belongs to. */
        fun authority(context: Context): String = context.packageName + ".openax12.files"
    }
}
