package org.openax12.feature.models

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.openax12.core.data.DataError
import org.openax12.core.data.DataResult
import org.openax12.core.data.ImportProblem
import org.openax12.core.data.ModelEntry
import org.openax12.core.data.SnapshotReason
import org.openax12.core.designsystem.AxIcons
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.model.ModelType
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A one-shot message for the toast host. View models cannot touch resources, so they emit the
 * string resource and its arguments and the screen formats it.
 */
internal data class UiMessage(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
    val kind: AxToastKind = AxToastKind.Info,
)

/** Layout of the model list. */
internal enum class ModelsLayout { GRID, LIST }

/** Sort order of the model list. */
internal enum class ModelSort { NAME, LAST_USED }

/** Sorts [models] for display: by name (case-insensitive) or most recently used first. */
internal fun sortModels(models: List<ModelEntry>, sort: ModelSort): List<ModelEntry> = when (sort) {
    ModelSort.NAME -> models.sortedBy { it.name.lowercase(Locale.ROOT) }
    ModelSort.LAST_USED -> models.sortedWith(
        compareByDescending<ModelEntry> { it.lastUsedMs }.thenBy { it.name.lowercase(Locale.ROOT) },
    )
}

// -- Names -------------------------------------------------------------------------------------

/** Why a typed model name is not acceptable. */
internal enum class NameProblem { EMPTY, TOO_LONG, INVALID_CHARACTERS, EXISTS }

/**
 * Pre-check of model names for the dialogs. The model repository applies the same rules (a model
 * name is also the library folder name on FAT storage: case-insensitive, no `\ / : * ? " < > |`,
 * no leading or trailing dot) and stays authoritative; this only gives early feedback.
 */
internal object ModelNameRules {
    /** Longest allowed model name. */
    const val MAX_LENGTH: Int = 32

    private const val FORBIDDEN = "\\/:*?\"<>|"
    private val reserved = setOf("con", "prn", "aux", "nul") +
        (1..9).flatMap { listOf("com$it", "lpt$it") }
    private val whitespace = Regex("\\s+")
    private val counterSuffix = Regex(" \\(\\d+\\)$")

    /** [raw] trimmed with inner whitespace collapsed. */
    fun normalize(raw: String): String = raw.replace(whitespace, " ").trim()

    /**
     * Checks [raw] against the rules and against [taken] (names that already exist).
     *
     * @param ignore a name that does not count as taken (the model being renamed)
     * @return the problem, or null if the name is fine
     */
    fun check(raw: String, taken: Collection<String>, ignore: String? = null): NameProblem? {
        val name = normalize(raw)
        if (name.isEmpty()) return NameProblem.EMPTY
        if (name.length > MAX_LENGTH) return NameProblem.TOO_LONG
        if (name.any { it.code < 0x20 || it.code == 0x7F || it in FORBIDDEN }) return NameProblem.INVALID_CHARACTERS
        if (name.startsWith('.') || name.endsWith('.')) return NameProblem.INVALID_CHARACTERS
        if (name.lowercase(Locale.ROOT) in reserved) return NameProblem.INVALID_CHARACTERS
        val key = name.lowercase(Locale.ROOT)
        val ignoreKey = ignore?.trim()?.lowercase(Locale.ROOT)
        if (taken.any { it.trim().lowercase(Locale.ROOT).let { other -> other == key && other != ignoreKey } }) {
            return NameProblem.EXISTS
        }
        return null
    }

    /** A free name derived from [base]: [base] itself, else `"base (2)"`, `"base (3)"`, … */
    fun unique(base: String, taken: Collection<String>): String {
        val keys = taken.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) }
        val clean = normalize(base).take(MAX_LENGTH)
        if (clean.lowercase(Locale.ROOT) !in keys) return clean
        val stem = counterSuffix.find(clean)?.let { clean.substring(0, it.range.first).trimEnd() }?.ifEmpty { null } ?: clean
        var counter = 2
        while (true) {
            val suffix = " ($counter)"
            val candidate = stem.take((MAX_LENGTH - suffix.length).coerceAtLeast(1)).trimEnd() + suffix
            if (candidate.lowercase(Locale.ROOT) !in keys) return candidate
            counter++
        }
    }
}

/** String resource of the message for a name problem. */
@StringRes
internal fun NameProblem.messageRes(): Int = when (this) {
    NameProblem.EMPTY -> R.string.models_name_empty
    NameProblem.TOO_LONG -> R.string.models_name_too_long
    NameProblem.INVALID_CHARACTERS -> R.string.models_name_invalid
    NameProblem.EXISTS -> R.string.models_name_exists
}

// -- Last used ---------------------------------------------------------------------------------

/** When a model was last selected, relative to "now". */
internal sealed interface LastUsed {
    /** The model was never selected. */
    data object Never : LastUsed

    /** Earlier today at [time]. */
    data class Today(val time: LocalTime) : LastUsed

    /** Yesterday. */
    data object Yesterday : LastUsed

    /** On [date]. */
    data class On(val date: LocalDate) : LastUsed
}

/** Classifies [lastUsedMs] (wall clock, 0 = never) relative to [nowMs] in [zone]. */
internal fun lastUsedOf(lastUsedMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LastUsed {
    if (lastUsedMs <= 0L) return LastUsed.Never
    val used = Instant.ofEpochMilli(lastUsedMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when (used.toLocalDate()) {
        today -> LastUsed.Today(used.toLocalTime())
        today.minusDays(1) -> LastUsed.Yesterday
        else -> LastUsed.On(used.toLocalDate())
    }
}

/** Display text of a [LastUsed]. */
@Composable
internal fun LastUsed.text(): String = when (this) {
    LastUsed.Never -> stringResource(R.string.models_last_used_never)
    is LastUsed.Today -> stringResource(
        R.string.models_last_used_today,
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time),
    )
    LastUsed.Yesterday -> stringResource(R.string.models_last_used_yesterday)
    is LastUsed.On -> stringResource(
        R.string.models_last_used_on,
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(date),
    )
}

/** Date and time of [epochMs] in the system zone, medium style. */
internal fun formatDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Size as "12 KB" / "1.3 MB". */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}

// -- Model types -------------------------------------------------------------------------------

/** Display name of a model type. */
@StringRes
internal fun ModelType.labelRes(): Int = when (this) {
    ModelType.GLIDER -> R.string.models_type_glider
    ModelType.PLANE -> R.string.models_type_plane
    ModelType.WING -> R.string.models_type_wing
    ModelType.MULTIROTOR -> R.string.models_type_multirotor
    ModelType.HELICOPTER -> R.string.models_type_helicopter
    ModelType.OTHER -> R.string.models_type_other
}

/** Icon of a model type. */
internal fun ModelType.icon(): ImageVector = when (this) {
    ModelType.GLIDER -> AxIcons.Glider
    ModelType.PLANE -> AxIcons.Plane
    ModelType.WING -> AxIcons.FlyingWing
    ModelType.MULTIROTOR -> AxIcons.Multirotor
    ModelType.HELICOPTER -> AxIcons.Helicopter
    ModelType.OTHER -> AxIcons.OtherModel
}

/** Order of the types in pickers. */
internal val ModelTypes: List<ModelType> = ModelType.entries

/** Display name of a snapshot reason. */
@StringRes
internal fun SnapshotReason.labelRes(): Int = when (this) {
    SnapshotReason.SAVE -> R.string.models_snapshot_reason_save
    SnapshotReason.BEFORE_RESTORE -> R.string.models_snapshot_reason_restore
    SnapshotReason.BEFORE_REIMPORT -> R.string.models_snapshot_reason_reimport
}

// -- Failure messages --------------------------------------------------------------------------

/** User-facing message for a repository error. */
@StringRes
internal fun DataError.messageRes(): Int = when (this) {
    DataError.NAME_INVALID -> R.string.models_err_name_invalid
    DataError.NAME_EXISTS -> R.string.models_err_name_exists
    DataError.NOT_FOUND -> R.string.models_err_not_found
    DataError.IS_CURRENT_MODEL -> R.string.models_err_is_current
    DataError.RADIO_FAILED -> R.string.models_err_radio
    DataError.STORAGE_PERMISSION -> R.string.models_err_storage_permission
    DataError.IO -> R.string.models_err_io
    DataError.INVALID_FILE -> R.string.models_err_invalid_file
    DataError.SCHEMA_TOO_NEW -> R.string.models_err_schema_too_new
    DataError.TOO_LARGE -> R.string.models_err_too_large
    DataError.BUSY -> R.string.models_err_busy
}

/** User-facing message for a model-flow error of the config session. */
@StringRes
internal fun SessionError.messageRes(): Int = when (this) {
    SessionError.RADIO_NOT_READY -> R.string.models_err_radio_not_ready
    SessionError.DEVICE_TIMEOUT -> R.string.models_err_device_timeout
    SessionError.BUSY -> R.string.models_err_busy
    SessionError.PLAN_REJECTED -> R.string.models_err_plan
    SessionError.PLAN_OPERATION_FAILED -> R.string.models_err_plan
    SessionError.DATA -> R.string.models_err_io
    SessionError.MODEL_NOT_FOUND -> R.string.models_err_not_found
    SessionError.UNSAVED_CHANGES -> R.string.models_err_unsaved
    SessionError.DEVICE_WRITE_PENDING -> R.string.models_err_write_pending
}

/** Why an import source was rejected, for the user. */
@StringRes
internal fun ImportProblem.messageRes(): Int = when (this) {
    ImportProblem.NOT_READABLE -> R.string.models_import_problem_not_readable
    ImportProblem.NOT_A_MODEL -> R.string.models_import_problem_not_a_model
    ImportProblem.PATH_TRAVERSAL -> R.string.models_import_problem_path
    ImportProblem.TOO_LARGE -> R.string.models_import_problem_too_large
    ImportProblem.MISSING_MODEL_FILE -> R.string.models_import_problem_missing_model
    ImportProblem.CORRUPT_EXTRAS -> R.string.models_import_problem_corrupt_extras
    ImportProblem.SCHEMA_TOO_NEW -> R.string.models_import_problem_schema
}

/** The error toast of a failed repository call, or null for a success. */
internal fun DataResult<*>.failureMessage(): UiMessage? = (this as? DataResult.Failure)?.let {
    UiMessage(it.error.messageRes(), kind = AxToastKind.Error)
}

/** The toast of a failed session call, or null for a success. */
internal fun SessionResult<*>.failureMessage(): UiMessage? = (this as? SessionResult.Failure)?.let {
    val res = if (it.error == SessionError.DATA && it.dataError != null) it.dataError!!.messageRes() else it.error.messageRes()
    val kind = if (it.error == SessionError.DEVICE_WRITE_PENDING) AxToastKind.Warning else AxToastKind.Error
    UiMessage(res, kind = kind)
}
