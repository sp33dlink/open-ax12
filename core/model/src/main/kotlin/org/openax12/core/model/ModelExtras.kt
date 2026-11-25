package org.openax12.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** One line of the per-model pre-flight checklist. */
@Serializable
data class ChecklistItem(val text: String, val spoken: Boolean = false)

/** What the home centre card shows. */
@Serializable
enum class HomeCenterCard {
    PICTURE,
    WIDGETS,
}

/**
 * Per-model home screen preferences.
 *
 * @property centerCard remembered state of the flip card (picture or pinned widgets)
 * @property inputPage last selected page of the input / switch area (0-based)
 * @property channelPage last selected page of the channel list (0 = CH01–08, 1 = CH09–16)
 */
@Serializable
data class HomePreferences(
    val centerCard: HomeCenterCard = HomeCenterCard.PICTURE,
    val inputPage: Int = 0,
    val channelPage: Int = 0,
)

/**
 * A managed mix line: `(chIdx, srcIdx)` plus the generator that created it.
 *
 * @property fingerprint [managedFingerprint] of the line as it was generated, or
 *   [ManagedItems.FINGERPRINT_UNKNOWN]. A different fingerprint of the line that exists now means
 *   it was edited by hand since (`PlanConflictKind.MANAGED_ITEM_MODIFIED`).
 */
@Serializable
data class ManagedMix(
    val channel: Int,
    val source: Int,
    val generator: String,
    val fingerprint: Int = ManagedItems.FINGERPRINT_UNKNOWN,
) {
    /** Identity of the mix. */
    val key: MixKey get() = MixKey(channel, source)
}

/**
 * A managed curve: curve index plus the generator that allocated it. A curve shared by two
 * generators is recorded once per generator.
 *
 * @property fingerprint [managedFingerprint] of the generated curve, see [ManagedMix.fingerprint]
 */
@Serializable
data class ManagedCurve(
    val index: Int,
    val generator: String,
    val fingerprint: Int = ManagedItems.FINGERPRINT_UNKNOWN,
)

/**
 * A managed dual-rate slot.
 *
 * @property fingerprint [managedFingerprint] of the generated slot, see [ManagedMix.fingerprint]
 */
@Serializable
data class ManagedDualRate(
    val channel: Int,
    val slot: Int,
    val generator: String,
    val fingerprint: Int = ManagedItems.FINGERPRINT_UNKNOWN,
)

/**
 * Everything the compilers / templates generated in the firmware config of this model. Recorded
 * so that recompiling can remove the old items first and so that the UI can badge them.
 *
 * Generator names: [GENERATOR_FLIGHT_MODES], [GENERATOR_WING], or `"template:<id>"`.
 *
 * ## Hand-editing a managed item (binding for the Mixes / Curves / DualRate tabs)
 * - **Editing** a managed mix, curve or dual rate (after the "managed" warning) **keeps it
 *   managed**: the record stays as it is, the next recompile of its generator reports
 *   `MANAGED_ITEM_MODIFIED` (a warning) and overwrites the edit. That is what the warning tells
 *   the user.
 * - **Deleting** a managed mix also removes its record: stage [withoutMix] through
 *   `ConfigSession.updateExtras` in the same user action. The same applies when the user frees a
 *   managed curve (type `NONE`) → [withoutCurve], or disables a managed dual-rate slot →
 *   [withoutDualRate]. The generator recreates the item on its next recompile.
 * - There is no "take over" (turning a managed item into a user item while keeping it): it would
 *   make every later recompile fail with a blocking `MIX_KEY_IN_USE`.
 */
@Serializable
data class ManagedItems(
    val mixes: List<ManagedMix> = emptyList(),
    val curves: List<ManagedCurve> = emptyList(),
    val dualRates: List<ManagedDualRate> = emptyList(),
) {
    /** True if nothing is recorded. */
    val isEmpty: Boolean get() = mixes.isEmpty() && curves.isEmpty() && dualRates.isEmpty()

    /** Generator of the managed mix `(channel, source)`, or null if the line is user-made. */
    fun generatorOfMix(channel: Int, source: Int): String? = mix(channel, source)?.generator

    /** Generator of managed curve [index] (the first one if it is shared), or null. */
    fun generatorOfCurve(index: Int): String? = curves.firstOrNull { it.index == index }?.generator

    /** Generator of the managed dual-rate slot, or null. */
    fun generatorOfDualRate(channel: Int, slot: Int): String? = dualRate(channel, slot)?.generator

    /** The record of the managed mix `(channel, source)`, or null. */
    fun mix(channel: Int, source: Int): ManagedMix? =
        mixes.firstOrNull { it.channel == channel && it.source == source }

    /** The record of the managed dual-rate slot, or null. */
    fun dualRate(channel: Int, slot: Int): ManagedDualRate? =
        dualRates.firstOrNull { it.channel == channel && it.slot == slot }

    /** Only the items of [generator]. */
    fun of(generator: String): ManagedItems = ManagedItems(
        mixes = mixes.filter { it.generator == generator },
        curves = curves.filter { it.generator == generator },
        dualRates = dualRates.filter { it.generator == generator },
    )

    /** Everything except the items of [generator]. */
    fun without(generator: String): ManagedItems = ManagedItems(
        mixes = mixes.filter { it.generator != generator },
        curves = curves.filter { it.generator != generator },
        dualRates = dualRates.filter { it.generator != generator },
    )

    /**
     * Union of both. Identity is `(channel, source)` for mixes, `(index, generator)` for curves
     * and `(channel, slot)` for dual rates; where both sides hold the same item the record of
     * [other] wins (it carries the newer fingerprint).
     */
    operator fun plus(other: ManagedItems): ManagedItems = ManagedItems(
        mixes = mixes.filterNot { mine -> other.mixes.any { it.key == mine.key } } + other.mixes.distinctBy { it.key },
        curves = curves.filterNot { mine -> other.curves.any { it.index == mine.index && it.generator == mine.generator } } +
            other.curves.distinctBy { it.index to it.generator },
        dualRates = dualRates.filterNot { mine -> other.dualRates.any { it.channel == mine.channel && it.slot == mine.slot } } +
            other.dualRates.distinctBy { it.channel to it.slot },
    )

    /** Copy without the record of mix `(channel, source)` (the user deleted the line). */
    fun withoutMix(channel: Int, source: Int): ManagedItems =
        copy(mixes = mixes.filterNot { it.channel == channel && it.source == source })

    /** Copy without the records of curve [index] of every generator (the user freed the curve). */
    fun withoutCurve(index: Int): ManagedItems = copy(curves = curves.filterNot { it.index == index })

    /** Copy without the record of dual-rate slot `(channel, slot)` (the user disabled the slot). */
    fun withoutDualRate(channel: Int, slot: Int): ManagedItems =
        copy(dualRates = dualRates.filterNot { it.channel == channel && it.slot == slot })

    /**
     * Copy whose fingerprints are taken from the items as they exist in [config] (records whose
     * item does not exist there get [FINGERPRINT_UNKNOWN]). `ConfigSession.applyPlan` calls this
     * with the configuration read back after the plan's operations, so that values the firmware
     * normalised on writing do not show up as hand edits later.
     */
    fun withFingerprintsFrom(config: WorkingConfig): ManagedItems = ManagedItems(
        mixes = mixes.map { record ->
            record.copy(fingerprint = config.mix(record.key)?.managedFingerprint() ?: FINGERPRINT_UNKNOWN)
        },
        curves = curves.map { record ->
            record.copy(fingerprint = config.curve(record.index)?.managedFingerprint() ?: FINGERPRINT_UNKNOWN)
        },
        dualRates = dualRates.map { record ->
            record.copy(
                fingerprint = config.dualRate(record.channel, record.slot)?.managedFingerprint() ?: FINGERPRINT_UNKNOWN,
            )
        },
    )

    /**
     * True if the recorded mix was edited by hand: it still exists in [config] and its
     * fingerprint differs from the recorded one. Always false for [FINGERPRINT_UNKNOWN].
     */
    fun isModified(record: ManagedMix, config: WorkingConfig): Boolean =
        differs(record.fingerprint, config.mix(record.key)?.managedFingerprint())

    /** Same as the mix overload for a managed curve. */
    fun isModified(record: ManagedCurve, config: WorkingConfig): Boolean =
        differs(record.fingerprint, config.curve(record.index)?.managedFingerprint())

    /** Same as the mix overload for a managed dual-rate slot. */
    fun isModified(record: ManagedDualRate, config: WorkingConfig): Boolean =
        differs(record.fingerprint, config.dualRate(record.channel, record.slot)?.managedFingerprint())

    private fun differs(recorded: Int, current: Int?): Boolean =
        recorded != FINGERPRINT_UNKNOWN && current != null && current != recorded

    companion object {
        /** Generator name of `FlightModeCompiler`. */
        const val GENERATOR_FLIGHT_MODES = "flightModes"
        /** Generator name of `WingCompiler`. */
        const val GENERATOR_WING = "wing"
        /** Prefix of a model template's generator name (`"template:glider"`). */
        const val GENERATOR_TEMPLATE_PREFIX = "template:"

        /** Generator of the plan that creates a blank model (`ModelBlueprint.blank`). */
        const val GENERATOR_BLANK = GENERATOR_TEMPLATE_PREFIX + "blank"

        /** [ManagedMix.fingerprint] value meaning "not recorded": such an item never counts as modified. */
        const val FINGERPRINT_UNKNOWN: Int = 0

        /** Nothing recorded. */
        val NONE = ManagedItems()
    }
}

// Fingerprints of generated items. They are stored in extras.json, so they must be stable across
// processes and app versions: only Int fields and SDK codes go in, in a fixed order (never
// hashCode() of an enum or a data class). Changing a formula turns every recorded fingerprint
// into a false MANAGED_ITEM_MODIFIED warning — add fields only together with a schema migration
// that resets the fingerprints to FINGERPRINT_UNKNOWN.

private fun fingerprintOf(vararg values: Int): Int {
    var hash = 17
    for (value in values) hash = 31 * hash + value
    return if (hash == ManagedItems.FINGERPRINT_UNKNOWN) 1 else hash
}

/** The condition's fields as they matter to the firmware: a disabled condition ignores source and value. */
private fun SwitchCondition.fingerprintFields(): IntArray =
    if (isEnabled) intArrayOf(op.sdk, source.index, value) else intArrayOf(CompareOp.DISABLED.sdk, 0, 0)

/**
 * Stable fingerprint of the fields of a mix line a generator sets: source, offset, curve, switch
 * condition, mode, slow times and weights. Never [ManagedItems.FINGERPRINT_UNKNOWN].
 */
fun MixLine.managedFingerprint(): Int = fingerprintOf(
    source.index, offset, curve, *switch.fingerprintFields(), mode.sdk, slowUpMs, slowDownMs, weightUp, weightDown,
)

/**
 * Stable fingerprint of a curve's shape: the type and — for [CurveType.CUSTOM] — its points, for
 * DIFF / EXPO / FUNC `x[0]` and `y[0]` (the fields [CurveConfig.sameShapeAs] compares). Never
 * [ManagedItems.FINGERPRINT_UNKNOWN].
 */
fun CurveConfig.managedFingerprint(): Int = when (type) {
    CurveType.NONE -> fingerprintOf(type.sdk)
    CurveType.CUSTOM -> fingerprintOf(type.sdk, *points.flatMap { listOf(it.x, it.y) }.toIntArray())
    else -> fingerprintOf(type.sdk, x.getOrElse(0) { 0 }, parameter)
}

/** Stable fingerprint of a dual-rate slot: switch condition and weight. Never [ManagedItems.FINGERPRINT_UNKNOWN]. */
fun DualRateSlot.managedFingerprint(): Int = fingerprintOf(*switch.fingerprintFields(), weight)

/**
 * Aspect ratio (width / height) of a model picture. The crop screen (`PictureCrop`), the stored
 * `picture.jpg`, the default pictures per [ModelType] and the picture areas of the home centre
 * card and the model cards all use it.
 */
const val MODEL_PICTURE_ASPECT: Float = 4f / 3f

/** Width in pixels of a stored model picture (`ModelRepository.setPicture` scales to this; height = width / [MODEL_PICTURE_ASPECT]). */
const val MODEL_PICTURE_WIDTH_PX: Int = 800

/** Height in pixels of a stored model picture. */
const val MODEL_PICTURE_HEIGHT_PX: Int = 600

/** File name of the custom picture inside a model folder ([ModelExtras.pictureFile]). */
const val MODEL_PICTURE_FILE_NAME: String = "picture.jpg"

/**
 * Everything open-ax12 stores per model on top of the SDK model file (`extras.json` in the model
 * folder). Versioned with [schemaVersion]; read and written through [ModelExtrasCodec] only.
 *
 * Every property has a default so that old files and files with missing sections load.
 *
 * @property pictureFile file name of the custom picture inside the model folder
 *   ([MODEL_PICTURE_FILE_NAME], [MODEL_PICTURE_WIDTH_PX] wide, aspect [MODEL_PICTURE_ASPECT]);
 *   null = default picture of [modelType]
 * @property logicalSwitches configured logical switches only (sparse), each carrying its index
 * @property specialFunctions configured special functions only (sparse), each carrying its index
 * @property sensorOverrides key = [SensorId.key]
 * @property alarms app-side telemetry alarms
 * @property timerVoice app-side voice options per timer (sparse)
 * @property managed bookkeeping of generated firmware items (see [ManagedItems] for the editing rules)
 * @property templateId id of the built-in template the model was created from, if any
 */
@Serializable
data class ModelExtras(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val modelType: ModelType = ModelType.GLIDER,
    val pictureFile: String? = null,
    val notes: String = "",
    val checklist: List<ChecklistItem> = emptyList(),
    val flightModes: FlightModesConfig = FlightModesConfig(),
    val wing: WingConfig = WingConfig(),
    val logicalSwitches: List<LogicalSwitch> = emptyList(),
    val specialFunctions: List<SpecialFunction> = emptyList(),
    val voiceAlerts: List<VoiceAlert> = emptyList(),
    val vario: VarioConfig = VarioConfig(),
    val telemetry: TelemetryLayout = TelemetryLayout(),
    val sensorOverrides: Map<String, SensorOverride> = emptyMap(),
    val alarms: List<SensorAlarm> = emptyList(),
    val timerVoice: List<TimerVoiceOptions> = emptyList(),
    val announcements: Announcements = Announcements(),
    val home: HomePreferences = HomePreferences(),
    val managed: ManagedItems = ManagedItems(),
    val templateId: String? = null,
) {
    /** Logical switch at 0-based [index], or an unused one. */
    fun logicalSwitch(index: Int): LogicalSwitch =
        logicalSwitches.firstOrNull { it.index == index } ?: LogicalSwitch(index)

    /** Copy with [switch] stored at its index (unused switches are removed from the list). */
    fun withLogicalSwitch(switch: LogicalSwitch): ModelExtras {
        val others = logicalSwitches.filter { it.index != switch.index }
        return copy(logicalSwitches = (if (switch.isUsed) others + switch else others).sortedBy { it.index })
    }

    /** Special function at 0-based [index], or null if the slot is empty. */
    fun specialFunction(index: Int): SpecialFunction? = specialFunctions.firstOrNull { it.index == index }

    /** Copy with [function] stored at its index. */
    fun withSpecialFunction(function: SpecialFunction): ModelExtras =
        copy(specialFunctions = (specialFunctions.filter { it.index != function.index } + function).sortedBy { it.index })

    /** Copy with the special function at [index] removed. */
    fun withoutSpecialFunction(index: Int): ModelExtras =
        copy(specialFunctions = specialFunctions.filter { it.index != index })

    /** Voice options of timer [timer] (0-based), or the defaults. */
    fun timerVoiceOptions(timer: Int): TimerVoiceOptions =
        timerVoice.firstOrNull { it.timer == timer } ?: TimerVoiceOptions(timer)

    /** Copy with [options] stored for its timer. */
    fun withTimerVoiceOptions(options: TimerVoiceOptions): ModelExtras =
        copy(timerVoice = (timerVoice.filter { it.timer != options.timer } + options).sortedBy { it.timer })

    /** Override of [sensor], or null. */
    fun sensorOverride(sensor: SensorId): SensorOverride? = sensorOverrides[sensor.key]

    /** Copy with the override of [sensor] set (null removes it). */
    fun withSensorOverride(sensor: SensorId, override: SensorOverride?): ModelExtras =
        copy(sensorOverrides = if (override == null) sensorOverrides - sensor.key else sensorOverrides + (sensor.key to override))

    /** Name of flight mode [index], or an empty string. */
    fun flightModeName(index: Int): String = flightModes.modes.getOrNull(index)?.name.orEmpty()

    companion object {
        /** Schema version written by this app version. Bump together with a migration in [ModelExtrasCodec]. */
        const val CURRENT_SCHEMA_VERSION: Int = 1

        /** File name inside a model folder. */
        const val FILE_NAME: String = "extras.json"
    }
}

/**
 * The one JSON configuration used for everything open-ax12 persists (`extras.json`, settings,
 * snapshots, export manifests): unknown keys are ignored, defaults are written, unknown enum
 * values fall back to the property default.
 */
val Ax12Json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    prettyPrint = true
    isLenient = true
}

/** Reads and writes [ModelExtras], including schema migration. */
object ModelExtrasCodec {

    /**
     * Result of decoding an `extras.json`.
     *
     * @property extras the decoded value, already migrated to [ModelExtras.CURRENT_SCHEMA_VERSION]
     * @property fileVersion the `schemaVersion` found in the file (0 if absent)
     * @property migrated true if [fileVersion] was older and migrations ran
     * @property newerThanApp true if the file was written by a newer app version; unknown parts
     *   were dropped, so writing it back loses data — importers must warn or refuse
     */
    data class Decoded(
        val extras: ModelExtras,
        val fileVersion: Int,
        val migrated: Boolean,
        val newerThanApp: Boolean,
    )

    /** Serializes [extras] with the current schema version. */
    fun encode(extras: ModelExtras): String =
        Ax12Json.encodeToString(ModelExtras.serializer(), extras.copy(schemaVersion = ModelExtras.CURRENT_SCHEMA_VERSION))

    /**
     * Parses and migrates an `extras.json`.
     *
     * @throws kotlinx.serialization.SerializationException if [text] is not valid JSON or a known
     *   key has an incompatible shape
     */
    fun decode(text: String): Decoded {
        val root = Ax12Json.parseToJsonElement(text).jsonObject
        val fileVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0
        val migratedRoot = migrate(root, fileVersion)
        val extras = Ax12Json.decodeFromJsonElement<ModelExtras>(migratedRoot)
            .copy(schemaVersion = ModelExtras.CURRENT_SCHEMA_VERSION)
        return Decoded(
            extras = extras,
            fileVersion = fileVersion,
            migrated = fileVersion < ModelExtras.CURRENT_SCHEMA_VERSION,
            newerThanApp = fileVersion > ModelExtras.CURRENT_SCHEMA_VERSION,
        )
    }

    /** [decode] that returns null instead of throwing. */
    fun decodeOrNull(text: String): Decoded? = try {
        decode(text)
    } catch (_: Exception) {
        null
    }

    /**
     * Migration hook: upgrades the raw JSON tree from schema [fromVersion] to
     * [ModelExtras.CURRENT_SCHEMA_VERSION], one version step at a time. Files without a
     * `schemaVersion` (version 0) and files of the current or a newer version pass through
     * unchanged — every property of [ModelExtras] has a default.
     *
     * To change the schema: bump [ModelExtras.CURRENT_SCHEMA_VERSION] and add a
     * `if (version == N) { tree = …; version = N + 1 }` step here that rewrites the tree.
     */
    fun migrate(root: JsonObject, fromVersion: Int): JsonObject {
        var tree = root
        var version = fromVersion.coerceAtLeast(0)
        if (version == 0) {
            // Version 0 = a file without schemaVersion; structurally identical to version 1.
            version = 1
        }
        // Future steps go here (see KDoc).
        if (version <= ModelExtras.CURRENT_SCHEMA_VERSION &&
            (tree["schemaVersion"] as? JsonPrimitive)?.intOrNull != ModelExtras.CURRENT_SCHEMA_VERSION
        ) {
            tree = JsonObject(tree + ("schemaVersion" to JsonPrimitive(ModelExtras.CURRENT_SCHEMA_VERSION)))
        }
        return tree
    }
}
