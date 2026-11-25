package org.openax12.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import org.openax12.core.model.ModelType

/**
 * The icon set of the app. Everything a screen needs is named here so that the whole app uses
 * one coherent style: Material "Rounded" glyphs plus hand-drawn vectors (same 24 dp grid, 2 dp
 * round strokes) for RC-specific things Material lacks.
 *
 * Draw them with [AxIcon]; all vectors are single-colour and take the tint.
 */
object AxIcons {
    // Bottom bar
    val System: ImageVector get() = Icons.Rounded.Settings
    val ModelSetup: ImageVector get() = modelSetupIcon
    val Models: ImageVector get() = modelsIcon
    val Home: ImageVector get() = Icons.Rounded.Home
    val Telemetry: ImageVector get() = Icons.Rounded.Speed
    val TouchLock: ImageVector get() = Icons.Rounded.Lock
    val TouchUnlock: ImageVector get() = Icons.Rounded.LockOpen
    val Help: ImageVector get() = Icons.AutoMirrored.Rounded.Help
    val HelpOutline: ImageVector get() = Icons.AutoMirrored.Rounded.HelpOutline

    // Status bar
    val Receiver: ImageVector get() = receiverIcon
    val Transmitter: ImageVector get() = transmitterIcon
    val Link: ImageVector get() = Icons.Rounded.Link
    val LinkOff: ImageVector get() = Icons.Rounded.LinkOff
    val Antenna: ImageVector get() = Icons.Rounded.SettingsInputAntenna

    // Timers
    val Timer: ImageVector get() = Icons.Rounded.Timer
    val Counter: ImageVector get() = counterIcon
    val Play: ImageVector get() = Icons.Rounded.PlayArrow
    val Pause: ImageVector get() = Icons.Rounded.Pause
    val Stop: ImageVector get() = Icons.Rounded.Stop
    val Reset: ImageVector get() = Icons.Rounded.RestartAlt

    // Model types
    val Glider: ImageVector get() = gliderIcon
    val Plane: ImageVector get() = Icons.Rounded.Flight
    val FlyingWing: ImageVector get() = flyingWingIcon
    val Multirotor: ImageVector get() = multirotorIcon
    val Helicopter: ImageVector get() = helicopterIcon
    val OtherModel: ImageVector get() = transmitterIcon

    // Inputs and mixing
    val Stick: ImageVector get() = stickIcon
    val Switch: ImageVector get() = Icons.Rounded.ToggleOn
    val Knob: ImageVector get() = knobIcon
    val Trim: ImageVector get() = Icons.Rounded.SwapVert
    val Mix: ImageVector get() = Icons.Rounded.CallSplit
    val Curve: ImageVector get() = Icons.AutoMirrored.Rounded.ShowChart
    val Function: ImageVector get() = Icons.Rounded.Functions
    val Logic: ImageVector get() = Icons.Rounded.Memory
    val FlightMode: ImageVector get() = Icons.Rounded.Layers
    val Tune: ImageVector get() = Icons.Rounded.Tune
    val Checklist: ImageVector get() = Icons.Rounded.Checklist

    // Telemetry
    val Sensor: ImageVector get() = Icons.Rounded.Sensors
    val Battery: ImageVector get() = Icons.Rounded.Bolt
    val Altitude: ImageVector get() = Icons.Rounded.Height
    val Vario: ImageVector get() = Icons.Rounded.Air
    val Gps: ImageVector get() = Icons.Rounded.GpsFixed
    val Compass: ImageVector get() = Icons.Rounded.Explore
    val Temperature: ImageVector get() = Icons.Rounded.Thermostat
    val Widgets: ImageVector get() = Icons.Rounded.Widgets
    val Dashboard: ImageVector get() = Icons.Rounded.Dashboard
    val Pin: ImageVector get() = Icons.Rounded.PushPin
    val Flag: ImageVector get() = Icons.Rounded.Flag

    // Audio
    val Volume: ImageVector get() = Icons.AutoMirrored.Rounded.VolumeUp
    val Voice: ImageVector get() = Icons.Rounded.RecordVoiceOver
    val Sound: ImageVector get() = Icons.Rounded.MusicNote
    val Haptic: ImageVector get() = Icons.Rounded.Vibration
    val Alarm: ImageVector get() = Icons.Rounded.Notifications

    // Actions
    val Back: ImageVector get() = Icons.AutoMirrored.Rounded.ArrowBack
    val Close: ImageVector get() = Icons.Rounded.Close
    val Check: ImageVector get() = Icons.Rounded.Check
    val Add: ImageVector get() = Icons.Rounded.Add
    val Remove: ImageVector get() = Icons.Rounded.Remove
    val Delete: ImageVector get() = Icons.Rounded.Delete
    val Edit: ImageVector get() = Icons.Rounded.Edit
    val Copy: ImageVector get() = Icons.Rounded.ContentCopy
    val Save: ImageVector get() = Icons.Rounded.Save
    val Share: ImageVector get() = Icons.Rounded.Share
    val Import: ImageVector get() = Icons.Rounded.FileDownload
    val Export: ImageVector get() = Icons.Rounded.FileUpload
    val Refresh: ImageVector get() = Icons.Rounded.Refresh
    val Undo: ImageVector get() = Icons.AutoMirrored.Rounded.Undo
    val Restore: ImageVector get() = Icons.Rounded.Restore
    val History: ImageVector get() = Icons.Rounded.History
    val Search: ImageVector get() = Icons.Rounded.Search
    val SearchOff: ImageVector get() = Icons.Rounded.SearchOff
    val Backspace: ImageVector get() = Icons.AutoMirrored.Rounded.Backspace
    val More: ImageVector get() = Icons.Rounded.MoreVert
    val Drag: ImageVector get() = Icons.Rounded.DragIndicator
    val ExpandMore: ImageVector get() = Icons.Rounded.ExpandMore
    val ExpandLess: ImageVector get() = Icons.Rounded.ExpandLess
    val ChevronLeft: ImageVector get() = Icons.Rounded.ChevronLeft
    val ChevronRight: ImageVector get() = Icons.Rounded.ChevronRight
    val Image: ImageVector get() = Icons.Rounded.Image
    val Camera: ImageVector get() = Icons.Rounded.PhotoCamera
    val Crop: ImageVector get() = Icons.Rounded.Crop
    val Folder: ImageVector get() = Icons.Rounded.Folder
    val Star: ImageVector get() = Icons.Rounded.Star
    val Visibility: ImageVector get() = Icons.Rounded.Visibility
    val Touch: ImageVector get() = Icons.Rounded.TouchApp

    // States and system
    val Info: ImageVector get() = Icons.Rounded.Info
    val Warning: ImageVector get() = Icons.Rounded.Warning
    val Error: ImageVector get() = Icons.Rounded.ErrorOutline
    val Success: ImageVector get() = Icons.Rounded.CheckCircle
    val Empty: ImageVector get() = Icons.Rounded.Inbox
    val Usb: ImageVector get() = Icons.Rounded.Usb
    val Storage: ImageVector get() = Icons.Rounded.Storage
    val Theme: ImageVector get() = Icons.Rounded.Palette
    val Diagnostics: ImageVector get() = Icons.Rounded.BugReport

    /** Icon of a model type (bottom sheets, model cards, pickers). */
    fun modelType(type: ModelType): ImageVector = when (type) {
        ModelType.GLIDER -> Glider
        ModelType.PLANE -> Plane
        ModelType.WING -> FlyingWing
        ModelType.MULTIROTOR -> Multirotor
        ModelType.HELICOPTER -> Helicopter
        ModelType.OTHER -> OtherModel
    }
}

private fun axIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

private val Ink = SolidColor(Color.Black)

private fun ImageVector.Builder.fill(pathData: String) {
    addPath(pathData = addPathNodes(pathData), fill = Ink)
}

private fun ImageVector.Builder.stroke(pathData: String, width: Float = 2f) {
    addPath(
        pathData = addPathNodes(pathData),
        stroke = Ink,
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

/** Material "flight" glyph (nose up), reused inside composed icons. */
private const val PLANE_PATH =
    "M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5L21,16z"

/** Aircraft seen from the side-front, heading up-right: the "RX voltage" icon of the status bar. */
private val receiverIcon: ImageVector by lazy {
    axIcon("Receiver") {
        group(rotate = 45f, pivotX = 12f, pivotY = 12f) { fill(PLANE_PATH) }
    }
}

/** Plane with a setup loop, as in the stock bottom bar. */
private val modelSetupIcon: ImageVector by lazy {
    axIcon("ModelSetup") {
        group(rotate = 90f, pivotX = 12f, pivotY = 12f, scaleX = 0.72f, scaleY = 0.72f, translationX = -4.2f) {
            fill(PLANE_PATH)
        }
        // Open loop around the nose with an arrow head.
        stroke("M17.6,7.6a4.6,4.6 0 1 1 -3.9,6.9", width = 1.8f)
        fill("M18.9,5.2l-3.6,1.5 3.1,2.3z")
    }
}

/** Stack of model cards: the model library. */
private val modelsIcon: ImageVector by lazy {
    axIcon("Models") {
        stroke("M7,4.5h10M5.5,7.5h13", width = 1.8f)
        stroke("M5.5,10.5h13a1.5,1.5 0 0 1 1.5,1.5v6.5a1.5,1.5 0 0 1 -1.5,1.5h-13a1.5,1.5 0 0 1 -1.5,-1.5v-6.5a1.5,1.5 0 0 1 1.5,-1.5z", width = 1.8f)
        // Small glider on the front card.
        stroke("M12,12.8v4.6M8,14.6h8M10.6,17.4h2.8", width = 1.5f)
    }
}

/** Timer face with a plus: a counter. */
private val counterIcon: ImageVector by lazy {
    axIcon("Counter") {
        stroke("M12,4a8,8 0 1 0 0.01,0z")
        stroke("M12,8.5v7M8.5,12h7")
    }
}

/** Glider from above: long slender wing, T-tail. */
private val gliderIcon: ImageVector by lazy {
    axIcon("Glider") {
        fill(
            "M12,2.2c0.75,0 1.15,0.9 1.15,2.1v4.2l8.9,1c0.6,0.07 0.95,0.5 0.95,1v0.2c0,0.5 -0.37,0.9 -0.95,0.95l-8.9,0.85" +
                "l-0.2,6.2 2.9,0.55c0.45,0.09 0.7,0.4 0.7,0.8 0,0.45 -0.35,0.75 -0.85,0.75H8.3c-0.5,0 -0.85,-0.3 -0.85,-0.75" +
                " 0,-0.4 0.25,-0.71 0.7,-0.8l2.9,-0.55 -0.2,-6.2 -8.9,-0.85C1.37,11.6 1,11.2 1,10.7v-0.2c0,-0.5 0.35,-0.93 0.95,-1" +
                "l8.9,-1V4.3c0,-1.2 0.4,-2.1 1.15,-2.1z",
        )
    }
}

/** Delta / flying wing from above. */
private val flyingWingIcon: ImageVector by lazy {
    axIcon("FlyingWing") {
        fill(
            "M12,3.2c0.5,0 0.9,0.25 1.2,0.7l9.3,14.3c0.5,0.8 -0.15,1.8 -1.1,1.6L12,17.9 2.6,19.8c-0.95,0.2 -1.6,-0.8 -1.1,-1.6" +
                "L10.8,3.9c0.3,-0.45 0.7,-0.7 1.2,-0.7z",
        )
    }
}

/** Quadcopter from above. */
private val multirotorIcon: ImageVector by lazy {
    axIcon("Multirotor") {
        stroke("M8,8l8,8M16,8l-8,8", width = 2.2f)
        stroke("M5.6,2.6a3,3 0 1 0 0.01,0zM18.4,2.6a3,3 0 1 0 0.01,0zM5.6,15.4a3,3 0 1 0 0.01,0zM18.4,15.4a3,3 0 1 0 0.01,0z", width = 1.7f)
        fill("M10.2,9h3.6c0.66,0 1.2,0.54 1.2,1.2v3.6c0,0.66 -0.54,1.2 -1.2,1.2h-3.6C9.54,15 9,14.46 9,13.8v-3.6C9,9.54 9.54,9 10.2,9z")
    }
}

/** Helicopter from the side. */
private val helicopterIcon: ImageVector by lazy {
    axIcon("Helicopter") {
        stroke("M2.5,5h16M10.5,5v3", width = 1.8f)
        fill(
            "M4,12.2C4,9.7 6.3,8 9.6,8h1.6c2.1,0 3.6,1.2 4.7,2.9l5.3,0.9c0.5,0.08 0.8,0.45 0.8,0.9 0,0.5 -0.35,0.87 -0.85,0.92" +
                "L15.6,14.2C14.6,15.5 13.1,16.4 11.2,16.4H8.8C6.1,16.4 4,14.6 4,12.2z",
        )
        stroke("M5.5,19.8h10.5M7.8,16.6v3M13,16.6v3", width = 1.7f)
        stroke("M21.2,10.3v4.6", width = 1.6f)
    }
}

/** Gimbal: ring with the stick in the middle. */
private val stickIcon: ImageVector by lazy {
    axIcon("Stick") {
        stroke("M12,3.5a8.5,8.5 0 1 0 0.01,0z")
        fill("M12,8.2a3.8,3.8 0 1 0 0.01,0z")
    }
}

/** Rotary knob with a pointer. */
private val knobIcon: ImageVector by lazy {
    axIcon("Knob") {
        stroke("M12,5a7,7 0 1 0 0.01,0z")
        stroke("M12,12l3,-3.6")
        stroke("M4.2,20.5h0.01M19.8,20.5h0.01", width = 2.2f)
    }
}

/** Handheld transmitter: body, two sticks, antenna. */
private val transmitterIcon: ImageVector by lazy {
    axIcon("Transmitter") {
        stroke("M6,9.5h12a2.5,2.5 0 0 1 2.5,2.5v5.5a2.5,2.5 0 0 1 -2.5,2.5H6a2.5,2.5 0 0 1 -2.5,-2.5V12a2.5,2.5 0 0 1 2.5,-2.5z", width = 1.8f)
        stroke("M12,9.5V3.5", width = 1.8f)
        fill("M8.3,12.9a1.9,1.9 0 1 0 0.01,0zM15.7,12.9a1.9,1.9 0 1 0 0.01,0z")
    }
}
