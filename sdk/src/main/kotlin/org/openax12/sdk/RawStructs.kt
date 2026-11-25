@file:Suppress("PropertyName")

package org.openax12.sdk

// One-to-one, lossless mirrors of the SDK structs of rmAxSdkTypes.h, filled and read by the JNI
// bridge through cached field ids.
//
// RULES (the bridge binds these classes by name in JNI_OnLoad; JniContractTest checks them
// against openax12_jni.cpp and the SDK header):
//  - the field names are the SDK's field names, typos included (alramVtg, BulidTimeText, …);
//  - every SDK field is present, none is added;
//  - types: integral -> Int, uint32 / 64-bit -> Long, float -> Float, double -> Double,
//    std::string -> String, fixed arrays -> IntArray of exactly the SDK length;
//  - a public no-argument constructor and @JvmField on everything.
// The classes are mutable on purpose: SdkRadio reads a fresh one, overlays the writable fields
// and hands it back ("Read-modify-write" in the Radio KDoc). They never leave the module.

/** Fixed array lengths of the SDK structs (`xVal[12]`, `fsChVal[16]`, `preFlightCheckChannel[36]`). */
internal object RawSizes {
    const val CURVE_POINTS = 12
    const val FAILSAFE_CHANNELS = 16
    const val PREFLIGHT_SOURCES = 36
}

/** `RcSrcCfgUI`. */
internal class RawSrc {
    @JvmField var srcName: String = ""
    @JvmField var srcIdx: Int = 0
    @JvmField var srcType: Int = 0
    @JvmField var isEnable: Int = 0
    @JvmField var rawVal: Int = 0
    @JvmField var rawLogicVal: Int = 0
    @JvmField var outputVal: Int = 0
    @JvmField var weight: Int = 0
}

/** `RcChOutCfgUI`. */
internal class RawChOut {
    @JvmField var chIdx: Int = 0
    @JvmField var middle: Int = 0
    @JvmField var min: Int = 0
    @JvmField var max: Int = 0
    @JvmField var curveIdx: Int = 0
    @JvmField var mixesNum: Int = 0
    @JvmField var weight: Int = 0
    @JvmField var chOut: Int = 0
    @JvmField var logicVal: Float = 0f
    @JvmField var lockChSwIdx: Int = 0
    @JvmField var lockChSwVal: Int = 0
    @JvmField var lockChSwType: Int = 0
    @JvmField var lockChVal: Int = 0
    @JvmField var dr1SwIdx: Int = 0
    @JvmField var dr1SwAct: Int = 0
    @JvmField var dr1Weight: Int = 0
    @JvmField var dr2SwIdx: Int = 0
    @JvmField var dr2SwAct: Int = 0
    @JvmField var dr2Weight: Int = 0
    @JvmField var dr3SwIdx: Int = 0
    @JvmField var dr3SwAct: Int = 0
    @JvmField var dr3Weight: Int = 0
}

/** `RcMixCfgDataUI`. */
internal class RawMix {
    @JvmField var srcName: String = ""
    @JvmField var srcType: Int = 0
    @JvmField var srcIdx: Int = 0
    @JvmField var offset: Int = 0
    @JvmField var curveIdx: Int = 0
    @JvmField var swCmpType: Int = 0
    @JvmField var switchIdx: Int = 0
    @JvmField var swCmpVal: Int = 0
    @JvmField var mixesMode: Int = 0
    @JvmField var chIdx: Int = 0
    @JvmField var mixIdx: Int = 0
    @JvmField var slowMsUp: Int = 0
    @JvmField var slowMsDown: Int = 0
    @JvmField var weightUp: Int = 0
    @JvmField var weightDown: Int = 0
    @JvmField var mixesCount: Int = 0
}

/** `RcChCfgDrUI`. */
internal class RawDr {
    @JvmField var chIdx: Int = 0
    @JvmField var idx: Int = 0
    @JvmField var drCount: Int = 0
    @JvmField var swCmpType: Int = 0
    @JvmField var swSrcIdx: Int = 0
    @JvmField var swCmpVal: Int = 0
    @JvmField var drWeight: Int = 0
}

/** `RcCurveCfgDataUI`; [xVal] / [yVal] always hold exactly 12 elements. */
internal class RawCurve {
    @JvmField var idx: Int = 0
    @JvmField var cType: Int = 0
    @JvmField var ptNum: Int = 0
    @JvmField var xVal: IntArray = IntArray(RawSizes.CURVE_POINTS)
    @JvmField var yVal: IntArray = IntArray(RawSizes.CURVE_POINTS)
}

/** `RcTimeCfgDataUI`. */
internal class RawTimer {
    @JvmField var mode: Int = 0
    @JvmField var swCmpType: Int = 0
    @JvmField var switchIdx: Int = 0
    @JvmField var switchAct: Int = 0
    @JvmField var startTime: Int = 0
    @JvmField var direction: Int = 0
    @JvmField var countdown: Int = 0
    @JvmField var beepTimeSec: Int = 0
    @JvmField var rstSwCmpType: Int = 0
    @JvmField var resetSwIdx: Int = 0
    @JvmField var resetSwAct: Int = 0
    @JvmField var cmpValue: Int = 0
    @JvmField var cmpType: Int = 0
    @JvmField var loopMode: Int = 0
    @JvmField var outputMode: Int = 0
    @JvmField var outputMin: Int = 0
    @JvmField var outputMax: Int = 0
    @JvmField var threshold: Int = 0
    @JvmField var timeIdx: Int = 0
    @JvmField var trDirection: Int = 0
    @JvmField var trStartTime: Int = 0
    @JvmField var timeCount: Int = 0
    @JvmField var notifyCode: Int = 0
}

/**
 * `RcSysCfgDataUI`. The `uint8_t` members are 0..255 here, the `int8_t` arrays −128..127; the
 * bridge narrows them, so values must be validated before writing.
 */
internal class RawSysCfg {
    @JvmField var fsMode: Int = 0
    @JvmField var fsChVal: IntArray = IntArray(RawSizes.FAILSAFE_CHANNELS)
    @JvmField var stickMode: Int = 0
    @JvmField var preFlightCheckChannel: IntArray = IntArray(RawSizes.PREFLIGHT_SOURCES)
    @JvmField var rfModuleMode: Int = 0
    @JvmField var alramVtg: Int = 0
    @JvmField var joytickBeep: Int = 0
    @JvmField var modelMatchId: Int = 0
    @JvmField var crsfBaudRate: Int = 0
}

/** `RcModelDataUI`; [modelMatch] is the unsigned 32-bit value. */
internal class RawModel {
    @JvmField var modelName: String = ""
    @JvmField var modelImg: String = ""
    @JvmField var filePath: String = ""
    @JvmField var modelIdx: Long = 0
    @JvmField var modelMatch: Long = 0
}

/** `FirmwareInfUI` (read-only); the numeric members are unsigned 32-bit values. */
internal class RawFwInfo {
    @JvmField var SysId: Long = 0
    @JvmField var DevId: Long = 0
    @JvmField var FlyTime: Long = 0
    @JvmField var BuildTime: Long = 0
    @JvmField var VER_FW: Long = 0
    @JvmField var VER_HW: Long = 0
    @JvmField var DevText: String = ""
    @JvmField var FwVerText: String = ""
    @JvmField var BulidTimeText: String = ""
    @JvmField var cacheFwFileVerTxt: String = ""
}

/** `TelemetryNotifyUI`; [hashValue] carries the 64-bit hash bit for bit. */
internal class RawTelemetry {
    @JvmField var hashValue: Long = 0
    @JvmField var popupWindow: Int = 0
    @JvmField var triggerType: Int = 0
    @JvmField var intervalSec: Int = 0
    @JvmField var triggerValue: Double = 0.0
    @JvmField var valueUnit: Int = 0
    @JvmField var notifyName: String = ""
    @JvmField var notifyText: String = ""
    @JvmField var audioFile: String = ""
    @JvmField var valTxt: String = ""
    @JvmField var nameTxt: String = ""
    @JvmField var currValue: Double = 0.0
    @JvmField var idx: Int = 0
    @JvmField var showUI: Int = 0
}
