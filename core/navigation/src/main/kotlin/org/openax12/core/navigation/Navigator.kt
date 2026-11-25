package org.openax12.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack

/**
 * Vetoes removing protected entries from the back stack (model setup with unsaved changes).
 *
 * [onLeave] is called before a protected entry would be removed. Return `true` to let the
 * navigation happen right away. Return `false` to block it; the guard then typically shows a
 * Save / Discard / Cancel dialog and calls `proceed()` once the user decided to leave —
 * `proceed` performs the blocked navigation without asking again.
 */
fun interface NavigationGuard {
    /** See the interface documentation; `true` = leave now, `false` = blocked until [proceed] is called. */
    fun onLeave(proceed: () -> Unit): Boolean
}

/**
 * The app's single navigation controller, a thin wrapper around the Navigation 3 back stack.
 * Obtain it with [LocalNavigator]. Must be used on the main thread only.
 *
 * Back stack shape: [Home] is always at the bottom. Selecting a top-level destination replaces
 * everything above Home with that destination's root entry; other keys are pushed on top.
 *
 * ## The unsaved-changes guard belongs to the app shell
 * `NavDisplay` keeps only the entries of the current scene composed: as soon as a sub-editor
 * ([CurveEditor], [LogicalSwitchEditor], …) covers [ModelSetup], model setup's composition — and
 * any `DisposableEffect` in it — is gone. A guard registered by the screen would vanish exactly
 * when it is needed. Therefore **feature screens never register guards.** The app shell does,
 * independent of composition:
 *
 * ```kotlin
 * // :app, next to the NavDisplay
 * val dirty by graph.configSession.dirty.collectAsStateWithLifecycle()
 * DisposableEffect(dirty) {
 *     if (dirty) navigator.guardEntries(protects = { it is ModelSetup }) { proceed -> pendingLeave = proceed; false }
 *     onDispose { navigator.clearGuard() }
 * }
 * // pendingLeave != null → the shell shows the Save / Discard / Cancel dialog (design system),
 * // calls ConfigSession.save() / discard() and then proceed().
 * ```
 *
 * The guard protects the [ModelSetup] entry *and everything above it*: leaving a sub-editor back
 * to model setup is free, leaving model setup itself (Back on its root, Home, any bottom-bar
 * destination — also from inside a sub-editor) asks.
 *
 * @param backStack the observable back stack (`rememberNavBackStack`); a plain `MutableList`
 *   works for tests. An empty list gets [Home] added.
 */
@Stable
class Navigator(private val backStack: MutableList<NavKey>) {

    private var guardedKey: NavKey? = null
    private var guardMatcher: ((NavKey) -> Boolean)? = null
    private var guard: NavigationGuard? = null

    init {
        if (backStack.isEmpty()) backStack.add(Home)
    }

    /** The entries from root to top. Observable in composition when backed by a snapshot list. */
    val entries: List<NavKey> get() = backStack

    /** The entry on top. */
    val current: NavKey get() = backStack.last()

    /** False when only the root entry is left. */
    val canGoBack: Boolean get() = backStack.size > 1

    /** The bottom-bar destination the current entry belongs to ([TopLevelDestination.HOME] at the root). */
    val currentTopLevel: TopLevelDestination
        get() = backStack.getOrNull(1)?.let(TopLevelDestination::of) ?: TopLevelDestination.HOME

    /**
     * The model-setup tab somebody asked for with [openSetupTab] and that the model-setup chrome
     * has not shown yet; null when there is none. Snapshot state: the chrome observes it and
     * takes it with [consumeSetupTabRequest].
     */
    var setupTabRequest: String? by mutableStateOf(null)
        private set

    /**
     * Pushes [key] on top. Single-top: if [key] equals the current entry nothing happens. If [key]
     * is the root entry of a top-level destination this behaves like [switchTopLevel]. A
     * [ModelSetup] key with an `initialTabId` while model setup is already on the back stack
     * behaves like [openSetupTab].
     */
    fun navigate(key: NavKey) {
        if (key is ModelSetup && key.initialTabId != null && backStack.any { it is ModelSetup }) {
            openSetupTab(key.initialTabId)
            return
        }
        if (key == current) return
        val topLevel = TopLevelDestination.of(key)
        if (topLevel != null) {
            switchTopLevel(topLevel, key)
            return
        }
        backStack.add(key)
    }

    /**
     * Shows the model-setup tab [tabId] (see [SetupTabIds]) from anywhere — a setup tab linking to
     * another tab, a sub-editor pointing to ChLock or Mixes, the home screen's timer gear.
     *
     * - Model setup is on the back stack: everything above it (sub-editors) is closed and
     *   [setupTabRequest] is set; the existing entry and its state are kept and the
     *   unsaved-changes guard is not consulted, because model setup is not left.
     * - Otherwise: like selecting the Model setup bottom-bar destination with
     *   `ModelSetup(initialTabId = tabId)`.
     */
    fun openSetupTab(tabId: String) {
        val setupIndex = backStack.indexOfFirst { it is ModelSetup }
        if (setupIndex < 0) {
            switchTopLevel(TopLevelDestination.MODEL_SETUP, ModelSetup(tabId))
            return
        }
        guarded(removedFrom = setupIndex + 1) {
            truncate(setupIndex + 1)
            setupTabRequest = tabId
        }
    }

    /**
     * Takes the pending [setupTabRequest] (it becomes null). Called by the model-setup chrome:
     *
     * ```kotlin
     * LaunchedEffect(navigator.setupTabRequest) { navigator.consumeSetupTabRequest()?.let(::selectTab) }
     * ```
     */
    fun consumeSetupTabRequest(): String? {
        val request = setupTabRequest
        setupTabRequest = null
        return request
    }

    /**
     * Removes the top entry.
     *
     * @return false if only the root entry is left (the caller may finish the activity) or a
     *   [NavigationGuard] blocked the navigation
     */
    fun back(): Boolean {
        if (!canGoBack) return false
        return guarded(removedFrom = backStack.size - 1) { backStack.removeAt(backStack.size - 1) }
    }

    /**
     * Selects a bottom-bar destination with single-top semantics: everything above [Home] is
     * replaced by the destination's root entry. Re-selecting the current destination pops back to
     * its root entry. [TopLevelDestination.HOME] pops to the root.
     */
    fun switchTopLevel(destination: TopLevelDestination) = switchTopLevel(destination, destination.key)

    /**
     * Replaces the top entry with [key] (e.g. picker → crop screen). At the root this pushes
     * instead, so [Home] stays at the bottom.
     */
    fun replace(key: NavKey) {
        if (!canGoBack) {
            navigate(key)
            return
        }
        guarded(removedFrom = backStack.size - 1) {
            backStack.removeAt(backStack.size - 1)
            backStack.add(key)
        }
    }

    /**
     * Pops entries until [key] is on top ([inclusive] also removes [key] itself; the root entry is
     * never removed).
     *
     * @return false if [key] is not on the back stack or a guard blocked the navigation
     */
    fun popTo(key: NavKey, inclusive: Boolean = false): Boolean {
        val index = backStack.lastIndexOf(key)
        if (index < 0) return false
        val keep = (if (inclusive) index else index + 1).coerceAtLeast(1)
        if (keep >= backStack.size) return true
        return guarded(removedFrom = keep) { truncate(keep) }
    }

    /**
     * Registers [guard] for every back-stack entry that [protects] matches; it is consulted
     * before any navigation that would remove such an entry. Only one guard exists at a time — a
     * new registration replaces the previous one.
     *
     * The guard lives until [clearGuard], independent of what is composed and of whether a
     * matching entry is on the back stack right now; after the user proceeded once it keeps
     * protecting later entries. This is the form the **app shell** uses for the unsaved-changes
     * dialog (see the class documentation); feature screens do not call it.
     */
    fun guardEntries(protects: (NavKey) -> Boolean, guard: NavigationGuard) {
        guardedKey = null
        guardMatcher = protects
        this.guard = guard
    }

    /** Removes the guard, whichever way it was registered. */
    fun clearGuard() {
        guardedKey = null
        guardMatcher = null
        guard = null
    }

    /**
     * Registers [guard] for the single back-stack entry [key] (`null` removes the guard if it
     * belongs to [key]). Unlike [guardEntries] this guard is one-shot: it is dropped when the
     * user proceeded, when it allowed a navigation, or when [key] is no longer on the back stack.
     *
     * Do **not** register it from a `DisposableEffect` of the guarded screen and clear it in
     * `onDispose` — the screen leaves the composition as soon as another entry covers it (see
     * the class documentation). For unsaved model-setup changes the shell uses [guardEntries].
     */
    fun setGuard(key: NavKey, guard: NavigationGuard?) {
        if (guard == null) {
            if (guardedKey == key) clearGuard()
        } else {
            guardedKey = key
            guardMatcher = { it == key }
            this.guard = guard
        }
    }

    private fun switchTopLevel(destination: TopLevelDestination, rootKey: NavKey) {
        val alreadyThere = backStack.size == (if (destination == TopLevelDestination.HOME) 1 else 2) &&
            destination.matches(current)
        if (alreadyThere) return
        if (destination == TopLevelDestination.HOME) {
            guarded(removedFrom = 1) { truncate(1) }
            return
        }
        val existing = backStack.getOrNull(1)
        if (existing != null && destination.matches(existing)) {
            // Re-selected: pop to the destination's root entry, keeping its state.
            guarded(removedFrom = 2) { truncate(2) }
        } else {
            guarded(removedFrom = 1) {
                truncate(1)
                backStack.add(rootKey)
            }
        }
    }

    private fun truncate(size: Int) {
        while (backStack.size > size) backStack.removeAt(backStack.size - 1)
    }

    /** Runs [action] unless the guard protects an entry at index ≥ [removedFrom] and blocks. */
    private fun guarded(removedFrom: Int, action: () -> Unit): Boolean {
        val matcher = guardMatcher
        val activeGuard = guard
        if (matcher != null && activeGuard != null) {
            val keyBound = guardedKey
            val index = backStack.indexOfLast(matcher)
            if (index >= removedFrom) {
                val allowed = activeGuard.onLeave {
                    // The user decided to leave: a key-bound guard is spent; then navigate.
                    if (keyBound != null) setGuard(keyBound, null)
                    action()
                }
                if (!allowed) return false
                if (keyBound != null) setGuard(keyBound, null)
            } else if (index < 0 && keyBound != null) {
                // Stale key-bound guard of an entry that is gone.
                setGuard(keyBound, null)
            }
        }
        action()
        return true
    }
}

/** The [Navigator] of the app shell. Provided by `:app` around the `NavDisplay`. */
val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided. Wrap the content in CompositionLocalProvider(LocalNavigator provides …).")
}

/**
 * Creates and remembers the app's [Navigator] with a saveable Navigation 3 back stack starting at
 * [start]. The returned pair's back stack is what `NavDisplay(backStack = …)` must be given.
 */
@Composable
fun rememberNavigator(start: Ax12Key = Home): NavigatorState {
    val backStack = rememberNavBackStack(start)
    return remember(backStack) { NavigatorState(Navigator(backStack), backStack) }
}

/** A [Navigator] together with the observable back stack it drives (for `NavDisplay`). */
@Stable
class NavigatorState(val navigator: Navigator, val backStack: MutableList<NavKey>)
