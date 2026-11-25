package org.openax12.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * One tab of the model-setup screen. `:feature:modelsetup` and `:feature:functions` each return a
 * list of these; the app shell concatenates the lists, sorts by [order] and hands the result to
 * the model-setup chrome.
 *
 * @property id stable id (see [SetupTabIds]); used for [ModelSetup.initialTabId] and to remember
 *   the last selected tab
 * @property titleRes string resource of the tab title, owned by the contributing module
 * @property order sort key; tabs are shown in ascending order
 * @property content the tab body. It is composed inside the chrome's content area (full width,
 *   below the tab row and the column-header row the tab draws itself if it needs one). It gets
 *   its dependencies from `LocalAppGraph` / `LocalNavigator` and marks unsaved changes through
 *   `ConfigSession`; the Save button belongs to the chrome, the unsaved-changes dialog to the
 *   app shell. To send the user to another tab (flight modes → the generated lines in Mixes,
 *   special functions → ChLock) call `LocalNavigator.current.openSetupTab(SetupTabIds.…)`.
 */
@Immutable
class SetupTab(
    val id: String,
    val titleRes: Int,
    val order: Int,
    val content: @Composable () -> Unit,
)

/** Stable ids and sort orders of the model-setup tabs (SPEC section 3). */
object SetupTabIds {
    // :feature:modelsetup
    const val INPUT_CHECK = "inputs"
    const val OUTPUT = "output"
    const val MIXES = "mixes"
    const val DUAL_RATE = "dualrate"
    const val CH_LOCK = "chlock"
    const val CURVES = "curves"
    const val TIMERS = "timers"

    // :feature:functions
    const val FLIGHT_MODES = "flightmodes"
    const val WING = "wing"
    const val LOGICAL_SWITCHES = "logicalswitches"
    const val SPECIAL_FUNCTIONS = "specialfunctions"
    const val SOUNDS = "sounds"

    /** [SetupTab.order] for each id: 10, 20, … in the order of SPEC section 3. */
    fun orderOf(id: String): Int = when (id) {
        INPUT_CHECK -> 10
        OUTPUT -> 20
        MIXES -> 30
        DUAL_RATE -> 40
        CH_LOCK -> 50
        CURVES -> 60
        TIMERS -> 70
        FLIGHT_MODES -> 80
        WING -> 90
        LOGICAL_SWITCHES -> 100
        SPECIAL_FUNCTIONS -> 110
        SOUNDS -> 120
        else -> 1000
    }
}
