package org.openax12.core.data

/** Why a data-layer operation failed. The UI maps these to localized messages. */
enum class DataError {
    /** The name is empty, too long or contains characters not allowed in a folder name. */
    NAME_INVALID,

    /** A model (or folder) with that name already exists. Nothing was overwritten. */
    NAME_EXISTS,

    /** The model, snapshot, log or file does not exist (any more). */
    NOT_FOUND,

    /** The operation is not allowed on the current model (e.g. deleting it). */
    IS_CURRENT_MODEL,

    /** The radio is not ready, or an SDK call failed / timed out. */
    RADIO_FAILED,

    /** The library is on public storage and the storage permission is missing. */
    STORAGE_PERMISSION,

    /** Reading or writing a file failed. */
    IO,

    /** The file is not a valid archive / model / backup (see the result's detail). */
    INVALID_FILE,

    /** The file was written by a newer app version and cannot be imported safely. */
    SCHEMA_TOO_NEW,

    /** The file exceeds the size or entry limits. */
    TOO_LARGE,

    /** Another operation of the same kind is still running. */
    BUSY,
}

/**
 * Result of a data-layer operation. Repositories never throw for expected failures; they return
 * [Failure] with a [DataError].
 */
sealed interface DataResult<out T> {

    /** The operation completed. */
    data class Success<T>(val value: T) : DataResult<T>

    /**
     * @property detail English diagnostic text for logs / the diagnostics screen; not for end users
     */
    data class Failure(val error: DataError, val detail: String = "") : DataResult<Nothing>

    /** True for [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** The value, or null on failure. */
    fun getOrNull(): T? = (this as? Success)?.value

    /** The error, or null on success. */
    fun errorOrNull(): DataError? = (this as? Failure)?.error
}

/** Transforms the value of a successful result. */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(value))
    is DataResult.Failure -> this
}
