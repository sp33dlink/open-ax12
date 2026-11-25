package org.openax12.sdk

/**
 * Receives the SDK output messages. Called on the **SDK output thread** (attached to the VM once):
 * implementations must only enqueue and return.
 *
 * The payload arrives split into primitives, the meaning is fixed by [msgId] (see [SdkMsg] and
 * [SdkMessageDecoder]). All arrays are fresh copies owned by the receiver.
 */
internal fun interface NativeMessageSink {
    fun onNativeMessage(msgId: Int, ints: IntArray?, doubles: DoubleArray?, bytes: ByteArray?, strings: Array<String>?)
}

/**
 * The complete native surface of `:sdk`: one function per bound `rmAxSdkApi.h` function plus two
 * batched readers. Implemented by [SdkNative] (JNI) and by a fake in the unit tests.
 *
 * Contract for callers ([SdkRadio] is the only one):
 * - every call comes from the single SDK thread, after [initialize] succeeded (except [version]);
 * - **every index and array length is validated before the call** — the SDK indexes its arrays
 *   directly, out-of-range values are undefined behaviour there;
 * - structs are the `Raw…` mirrors of `RawStructs.kt`, passed by value (the native side copies).
 *
 * The firmware-update and app-update functions of the SDK and its input message queue are not part
 * of this interface and must never be added (design rule 4).
 */
internal interface NativeApi {

    /** `version()`: usable before [initialize]. */
    fun version(): String

    /** Where SDK output messages go; `null` drops them. */
    fun setMessageSink(sink: NativeMessageSink?)

    /**
     * Redirects `TMPDIR` into [appDataPath], calls `initialize(appDataPath)` and registers the
     * message callback. Returns an [SdkInitCode].
     */
    fun initialize(appDataPath: String): Int

    /** `setMessageCallback(nullptr, nullptr)`. */
    fun clearCallback()

    /** Clears the callback and calls `shutdown()`. Safe to repeat. */
    fun shutdown()

    /** `taskReadConfigFromDevice()`; completes with [SdkMsg.GETDEVCFG_END]. */
    fun readConfigFromDevice()

    /** `taskSaveConfigToDevice()`; completes with [SdkMsg.CFGTODEV_END]. */
    fun saveConfigToDevice()

    /** `rcResetDefault()`. */
    fun resetDefault()

    /**
     * `rcGetSrcCfgList` + `rcGetChOutList` + `rcGetTrimming` + `rcGetRadioStatus` in one call,
     * written into preallocated buffers laid out as described by [NativeLayout]. [ints] must hold
     * at least [NativeLayout.LIVE_INT_SIZE] and [floats] at least [NativeLayout.LIVE_FLOAT_SIZE]
     * elements. `false` if nothing was written.
     */
    fun readLive(ints: IntArray, floats: FloatArray): Boolean

    /**
     * Sizes of the SDK lists that indices are validated against (sources, channels, curves, mixes
     * per channel), see [NativeLayout]. [out] must hold at least [NativeLayout.COUNTS_SIZE] elements.
     */
    fun readCounts(out: IntArray): Boolean

    /** `rcGetSrcCfgList()`. */
    fun getSources(): Array<RawSrc>?

    /** `rcGetChOutList()`. */
    fun getChannels(): Array<RawChOut>?

    /** `rcSetChOutCfgData()`. */
    fun setChannel(raw: RawChOut): Boolean

    /** `rcGetMixCfgData(chIdx, mixIdx)`. */
    fun getMix(chIdx: Int, mixIdx: Int): RawMix?

    /** `rcSetMixCfgData()`. */
    fun setMix(raw: RawMix): Boolean

    /** `rcAddMixCfgData(chIdx, srcIdx)`. */
    fun addMix(chIdx: Int, srcIdx: Int): RawMix?

    /** `rcDelMixCfgData(chIdx, srcIdx)`: remaining mixes or -1. */
    fun delMix(chIdx: Int, srcIdx: Int): Int

    /** `rcGetDrData(chIdx, idx)`; `idx == -1` asks for a free slot. */
    fun getDr(chIdx: Int, idx: Int): RawDr?

    /** `rcSetDrData()`: dual rates in use or -1. */
    fun setDr(raw: RawDr): Int

    /** `rcDelDrData()` (a no-op in the SDK). */
    fun delDr(raw: RawDr): Int

    /** `rcGetCurveList(idx)`; -1 = all. */
    fun getCurves(idx: Int): Array<RawCurve>?

    /** `rcSetCurveCfg()`: 0 or -1. */
    fun setCurve(raw: RawCurve): Int

    /** `rcCurveApply(curveIdx, rawVal)`. */
    fun curveApply(curveIdx: Int, rawVal: Double): Double

    /** `rcGetTimerCfgData(timerIdx, isCounter)`. */
    fun getTimer(timerIdx: Int, isCounter: Int): RawTimer?

    /** `rcSetTimerCfgData(timerCfg, isCounter)`. */
    fun setTimer(raw: RawTimer, isCounter: Int): Boolean

    /** `rcResetTimer(timerIdx)` with the device timer number. */
    fun resetTimer(timerIdx: Int)

    /** `rcGetRcSysConfig()`. */
    fun getSysConfig(): RawSysCfg?

    /** `rcSetRcSysConfig()`. */
    fun setSysConfig(raw: RawSysCfg): Boolean

    /** `rcSkipPreFlightCheck()`. */
    fun skipPreFlightCheck()

    /** `rcSetAdcCalibration(isStart)`. */
    fun setAdcCalibration(isStart: Int)

    /** `rcSetUsbToVcp(mode)`; [mode] 0..255. */
    fun setUsbToVcp(mode: Int)

    /** `rcRebootRFModule()`. */
    fun rebootRfModule()

    /** `rcGetFwInf()` (read-only information). */
    fun getFwInfo(): RawFwInfo?

    /** `rcTaskRequestDeviceInf()`; answered by [SdkMsg.FWINF_UPDATED]. */
    fun requestDeviceInfo()

    /** `rcGetRcModelList()`. */
    fun getModels(): Array<RawModel>?

    /** `rcGetTemplateList()`. */
    fun getTemplates(): Array<RawModel>?

    /** `rcGetCurrentModelCfgIndex()`: raw value, -1 on failure. */
    fun getCurrentModelIndex(): Int

    /** `rcCreateModelCfg()`. */
    fun createModel(): RawModel?

    /** `rcSetModelCfg()`. */
    fun setModel(raw: RawModel): RawModel?

    /** `rcSaveCurrentCfgToModel()`. */
    fun saveCurrentCfgToModel(raw: RawModel): Boolean

    /** `rcLoadModelFile()`. */
    fun loadModelFile(modelFile: String): Boolean

    /** `rcExportModelCfg(source, destination)`. */
    fun exportModel(sourceFile: String, destinationFile: String): Boolean

    /** `rcImportModelCfg(importFile, modelFile)`: 0 on success. */
    fun importModel(importFile: String, modelFile: String): Int

    /** `rcDeleteModel(filePath)`. */
    fun deleteModel(filePath: String): Boolean

    /** `rcModelToTemplate(modelFile, tempName)`. */
    fun modelToTemplate(modelFile: String, templateName: String): Boolean

    /** `rcGetTelemetryList(uiOnly)`. */
    fun getTelemetryList(uiOnly: Int): Array<RawTelemetry>?

    /** `rcGetNotifyCfgList()`. */
    fun getNotifyCfgList(): Array<RawTelemetry>?

    /** Size of `rcGetNotifyCfgList()`. */
    fun getNotifyCfgCount(): Int

    /** `rcGetNotifyCfgItem(hashValue, index)`. */
    fun getNotifyCfgItem(hashValue: Long, index: Int): RawTelemetry?

    /** `rcSetNotifyCfgItem(hashValue, notifyCfg, index)`. */
    fun setNotifyCfgItem(hashValue: Long, raw: RawTelemetry, index: Int): Boolean

    /** `rcDelNotifyItem(index)`. */
    fun delNotifyItem(index: Int)

    /** `RCgetTelemetryItemNotify()`: one queued notification, or null when the queue is empty. */
    fun pollTelemetryNotify(): RawTelemetry?

    /** `rcTaskRequestElrsMenuList(reqSetDevID)`. */
    fun requestElrsMenu(deviceId: Int)

    /** `rcElrsSendMenuCmd(idx, val)`. */
    fun elrsSendMenuCmd(idx: Int, value: Int)

    /** `rcRequestElrsStatus()`: 0 = sent, -1 = busy / failed. */
    fun requestElrsStatus(): Int

    /** `rcSendCrsfRawData()`; 1..[SdkAssumptions.CRSF_MAX_FRAME_BYTES] bytes. */
    fun sendCrsf(data: ByteArray): Boolean
}

/** Result codes of [NativeApi.initialize]; 0..3 mirror `rmaxSdk::InitResult`. */
internal object SdkInitCode {
    const val SUCCESS = 0
    const val ALREADY_INITIALIZED = 1
    const val THREAD_START_FAILED = 2
    const val COMMUNICATION_INIT_FAILED = 3

    /** `setMessageCallback` returned false. */
    const val CALLBACK_REJECTED = -1

    /** Empty or unreadable data path. */
    const val BAD_ARGUMENT = -2

    /** A C++ exception was caught in the bridge. */
    const val NATIVE_EXCEPTION = -3

    /** An `InitResult` value this bridge does not know. */
    const val UNKNOWN_RESULT = -4

    fun isSuccess(code: Int): Boolean = code == SUCCESS || code == ALREADY_INITIALIZED
}

/**
 * Layout of the primitive buffers of [NativeApi.readLive] and [NativeApi.readCounts]. Mirrors the
 * `k…` constants at the top of `openax12_jni.cpp` (checked by `JniContractTest`).
 */
internal object NativeLayout {
    /** At most this many sources / channels are transferred per call. */
    const val LIVE_MAX_SOURCES = 64
    const val LIVE_MAX_CHANNELS = 32

    /** Per source: `srcIdx`, `rawVal`, `rawLogicVal`, `outputVal`. */
    const val LIVE_SOURCE_STRIDE = 4

    /** Per channel: `chIdx`, `chOut`, `mixesNum`. */
    const val LIVE_CHANNEL_STRIDE = 3

    /** Number of sources / channels written (capped). */
    const val LIVE_INT_SOURCE_COUNT = 0
    const val LIVE_INT_CHANNEL_COUNT = 1

    /** Real sizes of the SDK lists (may exceed the caps). */
    const val LIVE_INT_SOURCE_TOTAL = 2
    const val LIVE_INT_CHANNEL_TOTAL = 3
    const val LIVE_INT_TRIM_LV = 4
    const val LIVE_INT_TRIM_LH = 5
    const val LIVE_INT_TRIM_RV = 6
    const val LIVE_INT_TRIM_RH = 7
    const val LIVE_INT_RF_LQ = 8
    const val LIVE_INT_RF_DBM = 9
    const val LIVE_INT_RF_LOST_COUNT = 10
    const val LIVE_INT_TOP_KEY_STATE = 11
    const val LIVE_INT_ADC_SET_STATE = 12
    const val LIVE_INT_SOURCES_OFFSET = 16
    const val LIVE_INT_CHANNELS_OFFSET = LIVE_INT_SOURCES_OFFSET + LIVE_MAX_SOURCES * LIVE_SOURCE_STRIDE
    const val LIVE_INT_SIZE = LIVE_INT_CHANNELS_OFFSET + LIVE_MAX_CHANNELS * LIVE_CHANNEL_STRIDE

    const val LIVE_FLOAT_TX_VOLTAGE = 0
    const val LIVE_FLOAT_RX_VOLTAGE = 1

    /** Per channel: `logicVal`. */
    const val LIVE_FLOAT_CHANNELS_OFFSET = 2
    const val LIVE_FLOAT_SIZE = LIVE_FLOAT_CHANNELS_OFFSET + LIVE_MAX_CHANNELS

    const val COUNTS_SOURCE_COUNT = 0
    const val COUNTS_CHANNEL_COUNT = 1
    const val COUNTS_CURVE_COUNT = 2
    const val COUNTS_CHANNELS_OFFSET = 4

    /** Per channel: `chIdx`, `mixesNum`. */
    const val COUNTS_CHANNEL_STRIDE = 2
    const val COUNTS_SIZE = COUNTS_CHANNELS_OFFSET + LIVE_MAX_CHANNELS * COUNTS_CHANNEL_STRIDE
}

/**
 * SDK output message ids (`enum RcMessageId` in `rmAxSdkTypes.h`, consecutive from `0x08000001`;
 * checked against the header by `JniContractTest`).
 */
internal object SdkMsg {
    const val LVTG_NOTIFY = 0x08000001
    const val PFC_NOTIFY = 0x08000002
    const val TIM_REPORT = 0x08000003
    const val CNT_REPORT = 0x08000004
    const val TIM_NOTIFY = 0x08000005
    const val TRIM_NOTIFY = 0x08000006

    /** Reserved, payload undefined: dropped in the bridge. */
    const val SRCINPUT_UPDATE = 0x08000007
    const val MIXOUT_UPDATE = 0x08000008
    const val CFGTODEV_END = 0x08000009
    const val GETDEVCFG_END = 0x0800000A
    const val RCTELEMETER_NOTIFY = 0x0800000B

    /** App-update messages: dropped in the bridge, the feature is never used. */
    const val APPINFO_UPDATED = 0x0800000C
    const val APPDOWNLOAD_PROGRESS = 0x0800000D
    const val APPDOWNLOAD_END = 0x0800000E
    const val FWVERSION_NOT_MATCH = 0x0800000F
    const val FWINF_UPDATED = 0x08000010

    /** Firmware-update messages: dropped in the bridge, the feature is never used. */
    const val FWUPDATE_PROGRESS = 0x08000011
    const val FWFILE_DOWNLOAD_END = 0x08000012
    const val ELRS_SET_ACK = 0x08000013
    const val ELRS_STATUS = 0x08000014
    const val ELRS_TELEMETRY = 0x08000015
    const val ELRS_DEVINF_UPDATE = 0x08000016
    const val ELRS_MENUITEM_UPDATE = 0x08000017
    const val CRSF_DATA_RECEIVED = 0x08000018
    const val TELEMETRY_UPDATED = 0x08000019
    const val TELEMETRY_LOST = 0x0800001A
    const val TELEMETRY_RECOVER = 0x0800001B
}
