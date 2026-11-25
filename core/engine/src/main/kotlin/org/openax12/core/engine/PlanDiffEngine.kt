package org.openax12.core.engine

import org.openax12.core.model.COUNTER_COUNT
import org.openax12.core.model.CURVE_COUNT
import org.openax12.core.model.CompareOp
import org.openax12.core.model.CurveConfig
import org.openax12.core.model.CurveRef
import org.openax12.core.model.DR_SLOTS
import org.openax12.core.model.DualRateSlot
import org.openax12.core.model.ManagedCurve
import org.openax12.core.model.ManagedDualRate
import org.openax12.core.model.ManagedItems
import org.openax12.core.model.ManagedMix
import org.openax12.core.model.MixKey
import org.openax12.core.model.MixLine
import org.openax12.core.model.MixPlan
import org.openax12.core.model.NO_CURVE
import org.openax12.core.model.OutputChannelConfig
import org.openax12.core.model.PERCENT_MAX
import org.openax12.core.model.PERCENT_MIN
import org.openax12.core.model.PlanConflict
import org.openax12.core.model.PlanConflictKind
import org.openax12.core.model.PlanConflictSeverity
import org.openax12.core.model.PlanDiff
import org.openax12.core.model.PlanOp
import org.openax12.core.model.PlannedChannel
import org.openax12.core.model.PlannedCurve
import org.openax12.core.model.PlannedDualRate
import org.openax12.core.model.PlannedMix
import org.openax12.core.model.SourceId
import org.openax12.core.model.SwitchCondition
import org.openax12.core.model.TIMER_COUNT
import org.openax12.core.model.TimerAlertMode
import org.openax12.core.model.TimerConfig
import org.openax12.core.model.WorkingConfig
import org.openax12.core.model.managedFingerprint

/**
 * One run of [PlanDiffer.diff]; the rules are documented there. The steps run in the order of
 * the resulting operations (mix removals, curves, mix adds / updates, dual rates, channels,
 * timers, counters) because later steps need the decisions of earlier ones (which mixes remain,
 * which index a planned curve got).
 */
internal class PlanDiffEngine(
    private val plan: MixPlan,
    private val current: WorkingConfig,
    private val managed: ManagedItems,
) {
    private val generator = plan.generator
    private val reset = plan.resetConfig
    private val replaceAllMixes = plan.replaceAllMixes || plan.resetConfig

    /** Records of this generator; ignored (and replaced) by a resetting plan. */
    private val mine: ManagedItems = if (reset) ManagedItems.NONE else managed.of(generator)

    /** Records of all other generators; a resetting plan wipes their items too. */
    private val others: ManagedItems = if (reset) ManagedItems.NONE else managed.without(generator)

    private val conflicts = ArrayList<PlanConflict>()
    private val ops = ArrayList<PlanOp>()

    /** Planned mixes that passed validation and ownership, by key, in plan order. */
    private val acceptedMixes = LinkedHashMap<MixKey, PlannedMix>()
    private val removedKeys = HashSet<MixKey>()

    private val plannedChannels: Map<Int, PlannedChannel> = mergeChannels(plan.channels)
    private val plannedCurves: List<PlannedCurve> = plan.curves.distinctBy { it.key }

    /** Planned curve key → allocated curve index. */
    private val curveIndex = LinkedHashMap<String, Int>()
    private val curveAfter = LinkedHashMap<Int, CurveConfig>()

    private val managedMixes = ArrayList<ManagedMix>()
    private val managedDualRates = ArrayList<ManagedDualRate>()

    /** Conflicts are reported once even if several items run into the same one. */
    private val reportedCurveProblems = HashSet<String>()

    fun run(): PlanDiff {
        selectMixes()
        removeMixes()
        allocateCurves()
        writeMixes()
        writeDualRates()
        writeChannels()
        writeTimers(plan.timers, current.timers, TIMER_COUNT, isCounter = false)
        writeTimers(plan.counters, current.counters, COUNTER_COUNT, isCounter = true)

        val created = ManagedItems(
            mixes = managedMixes,
            curves = curveAfter.map { (index, curve) -> ManagedCurve(index, generator, curve.managedFingerprint()) },
            dualRates = managedDualRates,
        )
        val managedAfter = when {
            reset -> created
            // Every mix that is not in the plan is gone, whoever made it; curves and dual rates stay.
            replaceAllMixes -> ManagedItems(curves = others.curves, dualRates = others.dualRates) + created
            else -> others + created
        }
        return PlanDiff(
            generator = generator,
            ops = ops,
            conflicts = conflicts,
            managedAfter = managedAfter,
            baseRevision = current.revision,
        )
    }

    // ---- mixes: ownership and removals -------------------------------------------------------

    private fun selectMixes() {
        for (mix in plan.mixes) {
            val key = mix.key
            val where = "CH${mix.channel + 1} ← ${mix.source.defaultName}"
            if (!isChannel(mix.channel, current)) {
                blocking(PlanConflictKind.INVALID_CHANNEL, channel = mix.channel, source = mix.source, detail = "$where: the channel does not exist")
                continue
            }
            if (!isUsableSource(mix.source, current)) {
                blocking(PlanConflictKind.INVALID_SOURCE, channel = mix.channel, source = mix.source, detail = "$where: the source does not exist on this radio")
                continue
            }
            if (mix.switch.isEnabled && !isUsableSource(mix.switch.source, current)) {
                blocking(
                    PlanConflictKind.INVALID_SOURCE,
                    channel = mix.channel,
                    source = mix.switch.source,
                    detail = "$where: the switch ${mix.switch.source.defaultName} does not exist on this radio",
                )
                continue
            }
            if (key in acceptedMixes) {
                blocking(PlanConflictKind.MIX_KEY_IN_USE, channel = mix.channel, source = mix.source, detail = "$where is planned twice")
                continue
            }
            if (!replaceAllMixes && current.mix(key) != null) {
                val owner = managed.generatorOfMix(key.channel, key.source)
                if (owner != generator) {
                    val who = if (owner == null) "a user-made mix" else "a mix generated by \"$owner\""
                    blocking(PlanConflictKind.MIX_KEY_IN_USE, channel = mix.channel, source = mix.source, detail = "$where is already used by $who")
                    continue
                }
            }
            acceptedMixes[key] = mix
        }
    }

    private fun removeMixes() {
        val doomed: List<MixLine> = if (replaceAllMixes) {
            current.mixes.filter { it.key !in acceptedMixes }
        } else {
            mine.mixes.filter { it.key !in acceptedMixes }.mapNotNull { current.mix(it.key) }
        }
        for (line in doomed.sortedWith(compareBy({ it.channel }, { it.mixIndex }))) {
            if (removedKeys.add(line.key)) ops += PlanOp.RemoveMix(line)
        }
    }

    // ---- curves --------------------------------------------------------------------------------

    /** Curve indices the plan refers to as existing curves: they must survive as they are. */
    private val existingRefs: Set<Int> by lazy {
        (plan.mixes.map { it.curve } + plan.channels.mapNotNull { it.curve })
            .mapNotNull { (it as? CurveRef.Existing)?.index }
            .toSet()
    }

    /** True if something other than this plan's own mixes will still use curve [index] afterwards. */
    private fun referencedByOthers(index: Int): Boolean {
        if (index in existingRefs) return true
        if (current.mixes.any { it.curve == index && it.key !in removedKeys && it.key !in acceptedMixes }) return true
        return current.channels.any { channel ->
            val planned = plannedChannels[channel.channel]
            when {
                planned?.curve != null -> false // an existing reference is covered by existingRefs
                reset -> false
                else -> channel.curve == index
            }
        }
    }

    private fun allocateCurves() {
        val usable = plannedCurves.filter { planned ->
            val problems = CurveMath.validate(planned.toConfig(0))
            if (problems.isNotEmpty()) {
                blocking(PlanConflictKind.INVALID_CURVE, curveKey = planned.key, detail = "Planned curve \"${planned.key}\" is invalid: $problems")
            }
            problems.isEmpty()
        }
        val readable = current.curves.filter { it.index in 0 until CURVE_COUNT }.associateBy { it.index }
        val taken = HashSet<Int>()
        val pending = ArrayList(usable)

        fun assign(planned: PlannedCurve, index: Int) {
            curveIndex[planned.key] = index
            taken += index
            pending -= planned
        }

        if (reset) {
            // Everything is wiped: allocate from the top, in plan order.
            val free = readable.keys.filter { it !in existingRefs }.sortedDescending().toMutableList()
            for (planned in pending.toList()) {
                val index = free.removeFirstOrNull() ?: break
                assign(planned, index)
            }
        } else {
            val myIndices = mine.curves.map { it.index }.distinct().filter { it in readable }.sorted()
            val otherIndices = others.curves.map { it.index }.toSet()

            // 1. A managed curve of this generator that already has the planned shape.
            for (planned in pending.toList()) {
                val index = myIndices.firstOrNull { it !in taken && readable.getValue(it).sameShapeAs(planned.toConfig(it)) } ?: continue
                assign(planned, index)
            }
            // 2. Any other managed curve of this generator, unless someone else relies on it.
            for (planned in pending.toList()) {
                val index = myIndices.firstOrNull { it !in taken && it !in otherIndices && !referencedByOthers(it) } ?: break
                val record = mine.curves.first { it.index == index }
                if (readable.getValue(index).isUnused) {
                    warning(PlanConflictKind.MANAGED_ITEM_MISSING, curveIndex = index, curveKey = planned.key, detail = "Managed curve ${index + 1} was freed; it is recreated")
                } else if (managed.isModified(record, current)) {
                    warning(PlanConflictKind.MANAGED_ITEM_MODIFIED, curveIndex = index, curveKey = planned.key, detail = "Managed curve ${index + 1} was edited by hand; the edit is overwritten")
                }
                assign(planned, index)
            }
            // 3. A managed curve of another generator with the identical shape is shared.
            for (planned in pending.toList()) {
                val index = otherIndices.sorted().firstOrNull {
                    it !in taken && readable[it]?.let { curve -> !curve.isUnused && curve.sameShapeAs(planned.toConfig(it)) } == true
                } ?: continue
                assign(planned, index)
            }
            // 4. The highest free curve.
            for (planned in pending.toList()) {
                val index = readable.keys.sortedDescending().firstOrNull {
                    it !in taken && it !in otherIndices && it !in existingRefs &&
                        readable.getValue(it).isUnused && !current.isCurveReferenced(it)
                } ?: break
                assign(planned, index)
            }
        }
        for (planned in pending) {
            blocking(PlanConflictKind.NO_FREE_CURVE, curveKey = planned.key, detail = "No free curve for \"${planned.key}\" (${planned.comment})")
            reportedCurveProblems += planned.key
        }

        for ((key, index) in curveIndex) {
            val before = readable.getValue(index)
            val after = plannedCurves.first { it.key == key }.toConfig(index)
            curveAfter[index] = after
            if (!before.sameShapeAs(after)) ops += PlanOp.SetCurve(before, after)
        }

        // Curves that are no longer needed.
        val stale: List<Int> = if (reset) {
            readable.keys.filter { it !in taken && it !in existingRefs }
        } else {
            mine.curves.map { it.index }.distinct().filter { it in readable && it !in taken }
        }
        for (index in stale.sorted()) {
            val before = readable.getValue(index)
            if (before.isUnused) continue
            if (!reset) {
                if (others.curves.any { it.index == index }) continue // still owned by another generator
                if (referencedByOthers(index)) {
                    warning(
                        PlanConflictKind.CURVE_SHARED_WITH_USER,
                        curveIndex = index,
                        detail = "Curve ${index + 1} is no longer needed by \"$generator\" but still used elsewhere; it is kept",
                    )
                    continue
                }
            }
            ops += PlanOp.SetCurve(before, CurveConfig.empty(index))
        }
    }

    /** Curve index for a reference of a planned item, or null (with a blocking conflict) if it cannot be resolved. */
    private fun resolveCurve(ref: CurveRef, where: String): Int? = when (ref) {
        CurveRef.None -> NO_CURVE
        is CurveRef.Existing -> {
            if (ref.index in 0 until CURVE_COUNT && current.curve(ref.index) != null) {
                ref.index
            } else {
                blocking(PlanConflictKind.INVALID_CURVE, curveIndex = ref.index, detail = "$where refers to curve ${ref.index + 1}, which does not exist")
                null
            }
        }
        is CurveRef.Planned -> curveIndex[ref.key] ?: run {
            // An allocation failure was reported already; an undefined key is a bug in the plan.
            if (plannedCurves.none { it.key == ref.key } && reportedCurveProblems.add(ref.key)) {
                blocking(PlanConflictKind.INVALID_CURVE, curveKey = ref.key, detail = "$where refers to the undefined planned curve \"${ref.key}\"")
            }
            null
        }
    }

    // ---- mixes: adds and updates -----------------------------------------------------------

    private fun writeMixes() {
        for ((key, planned) in acceptedMixes) {
            val where = "CH${planned.channel + 1} ← ${planned.source.defaultName}"
            val curve = resolveCurve(planned.curve, where) ?: continue
            val existing = current.mix(key)
            val record = mine.mix(key.channel, key.source)
            val after: MixLine
            if (existing == null) {
                if (record != null) {
                    warning(PlanConflictKind.MANAGED_ITEM_MISSING, channel = planned.channel, source = planned.source, detail = "$where was deleted; it is recreated")
                }
                after = MixLine(
                    channel = planned.channel,
                    source = planned.source,
                    offset = planned.offset,
                    curve = curve,
                    switch = normalized(planned.switch),
                    mode = planned.mode,
                    slowUpMs = planned.slowUpMs,
                    slowDownMs = planned.slowDownMs,
                    weightUp = planned.weightUp,
                    weightDown = planned.weightDown,
                )
                ops += PlanOp.AddMix(after)
            } else {
                after = existing.copy(
                    offset = planned.offset,
                    curve = curve,
                    switch = mergeCondition(existing.switch, planned.switch),
                    mode = planned.mode,
                    slowUpMs = planned.slowUpMs,
                    slowDownMs = planned.slowDownMs,
                    weightUp = planned.weightUp,
                    weightDown = planned.weightDown,
                )
                if (!sameMix(existing, after)) {
                    if (record != null && managed.isModified(record, current)) {
                        warning(PlanConflictKind.MANAGED_ITEM_MODIFIED, channel = planned.channel, source = planned.source, detail = "$where was edited by hand; the edit is overwritten")
                    }
                    ops += PlanOp.UpdateMix(existing, after)
                }
            }
            managedMixes += ManagedMix(key.channel, key.source, generator, after.managedFingerprint())
        }
    }

    private fun sameMix(a: MixLine, b: MixLine): Boolean =
        a.offset == b.offset && a.curve == b.curve && sameCondition(a.switch, b.switch) && a.mode == b.mode &&
            a.slowUpMs == b.slowUpMs && a.slowDownMs == b.slowDownMs && a.weightUp == b.weightUp && a.weightDown == b.weightDown

    // ---- dual rates ----------------------------------------------------------------------------

    private fun writeDualRates() {
        val plannedByChannel = LinkedHashMap<Int, MutableList<PlannedDualRate>>()
        for (planned in plan.dualRates) {
            val where = "Dual rate on CH${planned.channel + 1}"
            if (!isChannel(planned.channel, current)) {
                blocking(PlanConflictKind.INVALID_CHANNEL, channel = planned.channel, detail = "$where: the channel does not exist")
            } else if (!planned.switch.isEnabled) {
                blocking(PlanConflictKind.INVALID_SETTINGS, channel = planned.channel, detail = "$where has no switch condition")
            } else if (!isUsableSource(planned.switch.source, current)) {
                blocking(
                    PlanConflictKind.INVALID_SOURCE,
                    channel = planned.channel,
                    source = planned.switch.source,
                    detail = "$where: the switch ${planned.switch.source.defaultName} does not exist on this radio",
                )
            } else {
                plannedByChannel.getOrPut(planned.channel) { ArrayList() } += planned
            }
        }

        val channels = (current.dualRates.map { it.channel } + plannedByChannel.keys).distinct().sorted()
        for (channel in channels) {
            val slots = current.dualRatesOf(channel).filter { it.slot in 0 until DR_SLOTS }.associateBy { it.slot }
            val pending = ArrayList(plannedByChannel[channel].orEmpty())
            val assigned = LinkedHashMap<Int, PlannedDualRate>()
            val mySlots = mine.dualRates.filter { it.channel == channel && it.slot in slots }.map { it.slot }.distinct().sorted()
            val otherSlots = others.dualRates.filter { it.channel == channel }.map { it.slot }.toSet()

            fun assign(planned: PlannedDualRate, slot: Int) {
                assigned[slot] = planned
                pending -= planned
            }

            // 1. A managed slot of this generator that already holds the planned rate.
            for (planned in pending.toList()) {
                val slot = mySlots.firstOrNull {
                    it !in assigned && slots.getValue(it).let { now -> now.isEnabled && now.weight == planned.weight && sameCondition(now.switch, planned.switch) }
                } ?: continue
                assign(planned, slot)
            }
            // 2. The other managed slots of this generator.
            for (planned in pending.toList()) {
                val slot = mySlots.firstOrNull { it !in assigned } ?: break
                val record = mine.dualRate(channel, slot)
                if (!slots.getValue(slot).isEnabled) {
                    warning(PlanConflictKind.MANAGED_ITEM_MISSING, channel = channel, slot = slot, detail = "Managed dual rate ${slot + 1} of CH${channel + 1} was disabled; it is recreated")
                } else if (record != null && managed.isModified(record, current)) {
                    warning(PlanConflictKind.MANAGED_ITEM_MODIFIED, channel = channel, slot = slot, detail = "Managed dual rate ${slot + 1} of CH${channel + 1} was edited by hand; the edit is overwritten")
                }
                assign(planned, slot)
            }
            // 3. Free slots: disabled ones, lowest first (a resetting plan may take any slot).
            for (planned in pending.toList()) {
                val slot = slots.keys.sorted().firstOrNull {
                    it !in assigned && (reset || (!slots.getValue(it).isEnabled && it !in otherSlots))
                } ?: break
                assign(planned, slot)
            }
            for (planned in pending) {
                blocking(
                    PlanConflictKind.NO_FREE_DUAL_RATE_SLOT,
                    channel = channel,
                    detail = "No free dual-rate slot on CH${channel + 1} for ${planned.comment.ifBlank { "the planned rate" }}",
                )
            }

            for ((slot, planned) in assigned.toSortedMap()) {
                val before = slots.getValue(slot)
                val after = before.copy(switch = normalized(planned.switch), weight = planned.weight)
                if (!sameDualRate(before, after)) ops += PlanOp.SetDualRate(before, after)
                managedDualRates += ManagedDualRate(channel, slot, generator, after.managedFingerprint())
            }
            val stale = if (reset) slots.keys.filter { it !in assigned } else mySlots.filter { it !in assigned }
            for (slot in stale.sorted()) {
                val before = slots.getValue(slot)
                if (before.isEnabled) ops += PlanOp.SetDualRate(before, before.copy(switch = SwitchCondition.NONE, weight = PERCENT_MAX))
            }
        }
    }

    private fun sameDualRate(a: DualRateSlot, b: DualRateSlot): Boolean =
        sameCondition(a.switch, b.switch) && (a.weight == b.weight || (!a.isEnabled && !b.isEnabled))

    // ---- output channels -----------------------------------------------------------------------

    private fun writeChannels() {
        for (planned in plannedChannels.values) {
            if (!isChannel(planned.channel, current)) {
                blocking(PlanConflictKind.INVALID_CHANNEL, channel = planned.channel, detail = "Planned settings for CH${planned.channel + 1}: the channel does not exist")
            }
        }
        for (before in current.channels.sortedBy { it.channel }) {
            val planned = plannedChannels[before.channel]
            if (planned == null && !reset) continue
            val where = "CH${before.channel + 1}"

            val curve = when (val ref = planned?.curve) {
                null -> if (reset) NO_CURVE else before.curve
                else -> resolveCurve(ref, where) ?: before.curve
            }
            var lock = planned?.lock ?: if (reset) SwitchCondition.NONE else before.lock
            if (lock.isEnabled && !isUsableSource(lock.source, current)) {
                blocking(
                    PlanConflictKind.INVALID_SOURCE,
                    channel = before.channel,
                    source = lock.source,
                    detail = "$where: the lock switch ${lock.source.defaultName} does not exist on this radio",
                )
                lock = before.lock
            }
            val lockAfter = mergeCondition(before.lock, lock)
            val after = before.copy(
                min = planned?.min ?: if (reset) PERCENT_MIN else before.min,
                max = planned?.max ?: if (reset) PERCENT_MAX else before.max,
                middle = planned?.middle ?: if (reset) 0 else before.middle,
                weight = planned?.weight ?: if (reset) PERCENT_MAX else before.weight,
                curve = curve,
                lock = lockAfter,
                // The lock value means nothing without a lock; leaving it alone avoids a pointless write.
                lockValue = if (!lockAfter.isEnabled) before.lockValue else planned?.lockValue ?: if (reset) 0 else before.lockValue,
            )
            if (sameChannel(before, after)) continue
            if (!reset && overridesUserSetting(before, after)) {
                warning(
                    PlanConflictKind.CHANNEL_SETTING_CHANGED,
                    channel = before.channel,
                    detail = "$where: limits, weight, curve or lock differ from the current settings and are replaced",
                )
            }
            ops += PlanOp.SetChannel(before, after)
        }
    }

    private fun sameChannel(a: OutputChannelConfig, b: OutputChannelConfig): Boolean =
        a.min == b.min && a.max == b.max && a.middle == b.middle && a.weight == b.weight && a.curve == b.curve &&
            sameCondition(a.lock, b.lock) && a.lockValue == b.lockValue

    /** True if a setting that is being changed had been moved away from its neutral value. */
    private fun overridesUserSetting(before: OutputChannelConfig, after: OutputChannelConfig): Boolean =
        (before.min != after.min && before.min != PERCENT_MIN) ||
            (before.max != after.max && before.max != PERCENT_MAX) ||
            (before.middle != after.middle && before.middle != 0) ||
            (before.weight != after.weight && before.weight != PERCENT_MAX) ||
            (before.curve != after.curve && before.curve != NO_CURVE) ||
            (before.lock.isEnabled && (!sameCondition(before.lock, after.lock) || before.lockValue != after.lockValue))

    private fun mergeChannels(planned: List<PlannedChannel>): Map<Int, PlannedChannel> {
        val merged = LinkedHashMap<Int, PlannedChannel>()
        for (item in planned) {
            val earlier = merged[item.channel]
            merged[item.channel] = if (earlier == null) {
                item
            } else {
                earlier.copy(
                    min = item.min ?: earlier.min,
                    max = item.max ?: earlier.max,
                    middle = item.middle ?: earlier.middle,
                    weight = item.weight ?: earlier.weight,
                    curve = item.curve ?: earlier.curve,
                    lock = item.lock ?: earlier.lock,
                    lockValue = item.lockValue ?: earlier.lockValue,
                )
            }
        }
        return merged
    }

    // ---- timers and counters -------------------------------------------------------------------

    private fun writeTimers(planned: List<TimerConfig>, existing: List<TimerConfig>, count: Int, isCounter: Boolean) {
        val what = if (isCounter) "counter" else "timer"
        val wanted = LinkedHashMap<Int, TimerConfig>()
        for (timer in planned) {
            if (timer.index !in 0 until count) {
                blocking(PlanConflictKind.INVALID_SETTINGS, detail = "The plan configures $what ${timer.index + 1}, which does not exist")
                continue
            }
            val sources = listOf(timer.switch, timer.resetSwitch).filter { it.isEnabled }.map { it.source }
            val bad = sources.firstOrNull { !isUsableSource(it, current) }
            if (bad != null) {
                blocking(PlanConflictKind.INVALID_SOURCE, source = bad, detail = "The $what ${timer.index + 1} uses ${bad.defaultName}, which does not exist on this radio")
                continue
            }
            wanted[timer.index] = timer
        }
        val indices = (wanted.keys + if (reset) existing.map { it.index }.filter { it in 0 until count } else emptyList()).distinct().sorted()
        for (index in indices) {
            val before = existing.firstOrNull { it.index == index }
            val target = wanted[index]
            val after = when {
                target != null && before != null -> target.copy(
                    switch = mergeCondition(before.switch, target.switch),
                    resetSwitch = mergeCondition(before.resetSwitch, target.resetSwitch),
                    // Read-back fields are never written; keep what was read.
                    runningDirection = before.runningDirection,
                    runningStartTime = before.runningStartTime,
                    count = before.count,
                    notifyCode = before.notifyCode,
                )
                target != null -> target
                before != null -> before.copy(
                    switch = disabled(before.switch),
                    resetSwitch = disabled(before.resetSwitch),
                    alertMode = TimerAlertMode.SILENT,
                )
                else -> continue
            }
            if (before == null || !sameTimer(before, after)) ops += PlanOp.SetTimer(before, after, isCounter)
        }
    }

    private fun sameTimer(a: TimerConfig, b: TimerConfig): Boolean =
        a.mode == b.mode && sameCondition(a.switch, b.switch) && a.startTime == b.startTime && a.countUp == b.countUp &&
            a.alertMode == b.alertMode && a.alertIntervalSec == b.alertIntervalSec && sameCondition(a.resetSwitch, b.resetSwitch) &&
            a.compareValue == b.compareValue && a.compareOp == b.compareOp && a.loopMode == b.loopMode &&
            a.outputMode == b.outputMode && a.outputMin == b.outputMin && a.outputMax == b.outputMax && a.threshold == b.threshold

    // ---- helpers -------------------------------------------------------------------------------

    /** Two conditions gate the same way: both disabled, or identical. */
    private fun sameCondition(a: SwitchCondition, b: SwitchCondition): Boolean =
        if (!a.isEnabled || !b.isEnabled) a.isEnabled == b.isEnabled else a == b

    /** A disabled condition carries no source / value. */
    private fun normalized(condition: SwitchCondition): SwitchCondition = if (condition.isEnabled) condition else SwitchCondition.NONE

    /** The condition to write: an already disabled one is left exactly as it was read. */
    private fun mergeCondition(before: SwitchCondition, wanted: SwitchCondition): SwitchCondition = when {
        wanted.isEnabled -> wanted
        before.isEnabled -> SwitchCondition.NONE
        else -> before
    }

    private fun disabled(condition: SwitchCondition): SwitchCondition =
        if (condition.isEnabled) condition.copy(op = CompareOp.DISABLED) else condition

    private fun blocking(
        kind: PlanConflictKind,
        channel: Int? = null,
        source: SourceId? = null,
        slot: Int? = null,
        curveIndex: Int? = null,
        curveKey: String? = null,
        detail: String,
    ) {
        conflicts += PlanConflict(kind, PlanConflictSeverity.BLOCKING, channel, source, slot, curveIndex, curveKey, detail)
    }

    private fun warning(
        kind: PlanConflictKind,
        channel: Int? = null,
        source: SourceId? = null,
        slot: Int? = null,
        curveIndex: Int? = null,
        curveKey: String? = null,
        detail: String,
    ) {
        conflicts += PlanConflict(kind, PlanConflictSeverity.WARNING, channel, source, slot, curveIndex, curveKey, detail)
    }
}

/** Applies the operations of a diff to a copy of the configuration ([PlanDiffer.preview]). */
internal object PlanPreview {

    fun apply(diff: PlanDiff, current: WorkingConfig): WorkingConfig {
        val mixes = current.mixes.toMutableList()
        val curves = current.curves.toMutableList()
        val dualRates = current.dualRates.toMutableList()
        val channels = current.channels.toMutableList()
        val timers = current.timers.toMutableList()
        val counters = current.counters.toMutableList()

        for (op in diff.ops) {
            when (op) {
                is PlanOp.RemoveMix -> mixes.removeAll { it.key == op.existing.key }
                is PlanOp.AddMix -> {
                    mixes.removeAll { it.key == op.mix.key }
                    mixes += op.mix.copy(mixIndex = Int.MAX_VALUE) // appended; renumbered below
                }
                is PlanOp.UpdateMix -> replaceOrAdd(mixes, op.after) { it.key == op.after.key }
                is PlanOp.SetCurve -> replaceOrAdd(curves, op.after) { it.index == op.after.index }
                is PlanOp.SetDualRate -> replaceOrAdd(dualRates, op.after) { it.channel == op.after.channel && it.slot == op.after.slot }
                is PlanOp.SetChannel -> replaceOrAdd(channels, op.after) { it.channel == op.after.channel }
                is PlanOp.SetTimer -> replaceOrAdd(if (op.isCounter) counters else timers, op.after) { it.index == op.after.index }
            }
        }

        val renumbered = mixes.groupBy { it.channel }.toSortedMap().flatMap { (_, lines) ->
            lines.sortedBy { it.mixIndex }.mapIndexed { i, line -> line.copy(mixIndex = i, mixCount = lines.size) }
        }
        val counts = renumbered.groupingBy { it.channel }.eachCount()
        return current.copy(
            mixes = renumbered,
            curves = curves.sortedBy { it.index },
            dualRates = dualRates.sortedWith(compareBy({ it.channel }, { it.slot })),
            channels = channels.map { it.copy(mixCount = counts[it.channel] ?: 0) },
            timers = timers.sortedBy { it.index },
            counters = counters.sortedBy { it.index },
        )
    }

    private inline fun <T> replaceOrAdd(list: MutableList<T>, item: T, match: (T) -> Boolean) {
        val index = list.indexOfFirst(match)
        if (index >= 0) list[index] = item else list += item
    }
}
