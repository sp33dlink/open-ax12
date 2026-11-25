package org.openax12.sdk

/**
 * JNI implementation of [NativeApi]: every `external` function is defined in
 * `src/main/cpp/openax12_jni.cpp` as `Java_org_openax12_sdk_SdkNative_<name>` (signatures are
 * compared by `JniContractTest`).
 *
 * The native library is **not** loaded when this object is initialised but by [ensureLoaded],
 * which [SdkRadio] calls when it is constructed for the first time: the app also runs on an
 * emulator with the simulator backend and must not touch the SDK there.
 *
 * Loading `openax12jni` pulls in `rmAxSdk1`, the Qt libraries and `c++_shared` through
 * `DT_NEEDED`. Never call `System.loadLibrary` for a Qt library directly: Qt's own `JNI_OnLoad`
 * fails without the Qt Android binding classes.
 *
 * The members must stay public members of this object with exactly these names: `internal`
 * functions get mangled JVM names, which the bridge could not find.
 */
internal object SdkNative : NativeApi {

    private const val LIBRARY = "openax12jni"

    private var loaded = false
    private var loadError: Throwable? = null

    @Volatile
    private var sink: NativeMessageSink? = null

    /**
     * Loads the native library once. Returns `null` on success, otherwise the error of the first
     * attempt (wrong ABI, missing library, a failed binding in `JNI_OnLoad`) — it is not retried.
     */
    @Synchronized
    fun ensureLoaded(): Throwable? {
        if (!loaded && loadError == null) {
            try {
                System.loadLibrary(LIBRARY)
                loaded = true
            } catch (t: Throwable) {
                loadError = t
            }
        }
        return loadError
    }

    override fun setMessageSink(sink: NativeMessageSink?) {
        this.sink = sink
    }

    /**
     * Entry point of the SDK message callback, called by the bridge on the SDK output thread
     * (`(I[I[D[B[Ljava/lang/String;)V`). Must never throw and never block.
     */
    @JvmStatic
    fun onNativeMessage(msgId: Int, ints: IntArray?, doubles: DoubleArray?, bytes: ByteArray?, strings: Array<String>?) {
        try {
            sink?.onNativeMessage(msgId, ints, doubles, bytes, strings)
        } catch (_: Throwable) {
            // A failing consumer must not unwind into the SDK output thread.
        }
    }

    external override fun version(): String
    external override fun initialize(appDataPath: String): Int
    external override fun clearCallback()
    external override fun shutdown()
    external override fun readConfigFromDevice()
    external override fun saveConfigToDevice()
    external override fun resetDefault()
    external override fun readLive(ints: IntArray, floats: FloatArray): Boolean
    external override fun readCounts(out: IntArray): Boolean
    external override fun getSources(): Array<RawSrc>?
    external override fun getChannels(): Array<RawChOut>?
    external override fun setChannel(raw: RawChOut): Boolean
    external override fun getMix(chIdx: Int, mixIdx: Int): RawMix?
    external override fun setMix(raw: RawMix): Boolean
    external override fun addMix(chIdx: Int, srcIdx: Int): RawMix?
    external override fun delMix(chIdx: Int, srcIdx: Int): Int
    external override fun getDr(chIdx: Int, idx: Int): RawDr?
    external override fun setDr(raw: RawDr): Int
    external override fun delDr(raw: RawDr): Int
    external override fun getCurves(idx: Int): Array<RawCurve>?
    external override fun setCurve(raw: RawCurve): Int
    external override fun curveApply(curveIdx: Int, rawVal: Double): Double
    external override fun getTimer(timerIdx: Int, isCounter: Int): RawTimer?
    external override fun setTimer(raw: RawTimer, isCounter: Int): Boolean
    external override fun resetTimer(timerIdx: Int)
    external override fun getSysConfig(): RawSysCfg?
    external override fun setSysConfig(raw: RawSysCfg): Boolean
    external override fun skipPreFlightCheck()
    external override fun setAdcCalibration(isStart: Int)
    external override fun setUsbToVcp(mode: Int)
    external override fun rebootRfModule()
    external override fun getFwInfo(): RawFwInfo?
    external override fun requestDeviceInfo()
    external override fun getModels(): Array<RawModel>?
    external override fun getTemplates(): Array<RawModel>?
    external override fun getCurrentModelIndex(): Int
    external override fun createModel(): RawModel?
    external override fun setModel(raw: RawModel): RawModel?
    external override fun saveCurrentCfgToModel(raw: RawModel): Boolean
    external override fun loadModelFile(modelFile: String): Boolean
    external override fun exportModel(sourceFile: String, destinationFile: String): Boolean
    external override fun importModel(importFile: String, modelFile: String): Int
    external override fun deleteModel(filePath: String): Boolean
    external override fun modelToTemplate(modelFile: String, templateName: String): Boolean
    external override fun getTelemetryList(uiOnly: Int): Array<RawTelemetry>?
    external override fun getNotifyCfgList(): Array<RawTelemetry>?
    external override fun getNotifyCfgCount(): Int
    external override fun getNotifyCfgItem(hashValue: Long, index: Int): RawTelemetry?
    external override fun setNotifyCfgItem(hashValue: Long, raw: RawTelemetry, index: Int): Boolean
    external override fun delNotifyItem(index: Int)
    external override fun pollTelemetryNotify(): RawTelemetry?
    external override fun requestElrsMenu(deviceId: Int)
    external override fun elrsSendMenuCmd(idx: Int, value: Int)
    external override fun requestElrsStatus(): Int
    external override fun sendCrsf(data: ByteArray): Boolean
}
