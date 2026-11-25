package org.openax12.sdk

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** What the probe found out about the serial port after a start that produced no data. */
internal enum class PortDiagnosis {
    /** This process holds an open serial device: the SDK opened its port, the radio MCU is silent. */
    OPEN_BY_US,

    /** A serial device answered `EBUSY`: another process (the stock app) holds it exclusively. */
    BUSY,

    /** Serial devices are visible but none exists / may be opened (not an AX12, missing permission). */
    UNAVAILABLE,

    /** Nothing conclusive. */
    INCONCLUSIVE,
}

/** @property detail English text for logs and diagnostics (device names, errno values) */
internal data class PortProbeResult(val diagnosis: PortDiagnosis, val detail: String)

/**
 * Best-effort classification of a silent start (assumption 24). The SDK opens the serial port
 * itself and reports neither the port nor an error, so this looks from the outside:
 *
 * 1. does this process hold a descriptor of a serial device? Then the port is open.
 * 2. otherwise, try to open the serial devices Qt would enumerate, non-blocking and without
 *    becoming the controlling terminal, and close them at once: `EBUSY` means somebody else has
 *    the port exclusively (QSerialPort sets `TIOCEXCL`), which is what the stock app does.
 *
 * Only run after a start timed out: it must not compete with the SDK for a port.
 *
 * @param ownSerialDescriptors paths of serial devices this process has open
 * @param candidates paths of the serial devices to probe
 * @param tryOpen opens and closes a device; returns 0 or the `errno` of the failed `open`
 */
internal class SerialPortProbe(
    private val ownSerialDescriptors: () -> List<String>,
    private val candidates: () -> List<String>,
    private val tryOpen: (String) -> Int,
) {

    fun probe(): PortProbeResult {
        val own = runCatching(ownSerialDescriptors).getOrDefault(emptyList())
        if (own.isNotEmpty()) {
            return PortProbeResult(PortDiagnosis.OPEN_BY_US, "open by this process: ${own.joinToString()}")
        }
        val devices = runCatching(candidates).getOrDefault(emptyList()).take(SdkAssumptions.SERIAL_PROBE_MAX_DEVICES)
        if (devices.isEmpty()) {
            // Not UNAVAILABLE: an app may simply not be allowed to list /sys and /dev.
            return PortProbeResult(PortDiagnosis.INCONCLUSIVE, "no serial device visible")
        }
        val results = devices.map { it to runCatching { tryOpen(it) }.getOrDefault(EIO) }
        val detail = results.joinToString { (path, errno) -> if (errno == 0) "$path: free" else "$path: errno $errno" }
        return when {
            results.any { it.second == EBUSY } -> PortProbeResult(PortDiagnosis.BUSY, detail)
            results.all { it.second in UNAVAILABLE_ERRNOS } -> PortProbeResult(PortDiagnosis.UNAVAILABLE, detail)
            else -> PortProbeResult(PortDiagnosis.INCONCLUSIVE, detail)
        }
    }

    companion object {
        // Linux errno values (identical on every Android ABI); kept as literals so that the
        // classification is testable on the JVM, where OsConstants are all zero.
        const val ENOENT = 2
        const val EIO = 5
        const val ENXIO = 6
        const val EACCES = 13
        const val EPERM = 1
        const val EBUSY = 16
        const val ENODEV = 19

        private val UNAVAILABLE_ERRNOS = setOf(ENOENT, ENXIO, EACCES, EPERM, ENODEV)

        private val SERIAL_NAME = Regex("^(" + SdkAssumptions.SERIAL_DEVICE_PREFIXES.joinToString("|") + ")\\d+$")

        /** True for the name of a serial device node such as `ttyS1` (not `tty`, `ptmx`, `pts/0`). */
        fun isSerialDeviceName(name: String): Boolean = SERIAL_NAME.matches(name)

        /** The probe against the real `/proc`, `/sys` and `/dev`. */
        fun forDevice(): SerialPortProbe = SerialPortProbe(
            ownSerialDescriptors = ::listOwnSerialDescriptors,
            candidates = ::listSerialDevices,
            tryOpen = ::openAndClose,
        )

        private fun listOwnSerialDescriptors(): List<String> {
            val entries = File("/proc/self/fd").list() ?: return emptyList()
            val result = ArrayList<String>()
            for (entry in entries) {
                val target = try {
                    Os.readlink("/proc/self/fd/$entry")
                } catch (_: Exception) {
                    continue
                }
                if (target.startsWith("/dev/") && isSerialDeviceName(target.substringAfterLast('/'))) result += target
            }
            return result.distinct()
        }

        private fun listSerialDevices(): List<String> {
            // Like QSerialPortInfo: real devices listed in sysfs first, the /dev listing as fallback.
            val fromSysfs = File("/sys/class/tty").list().orEmpty()
                .filter { isSerialDeviceName(it) && File("/sys/class/tty/$it/device").exists() }
            val names = fromSysfs.ifEmpty { File("/dev").list().orEmpty().filter(::isSerialDeviceName) }
            return names.sorted().map { "/dev/$it" }.filter { File(it).exists() }
        }

        private fun openAndClose(path: String): Int = try {
            val descriptor = Os.open(path, OsConstants.O_RDWR or OsConstants.O_NOCTTY or OsConstants.O_NONBLOCK, 0)
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // Closing can only fail after the fact; the open itself succeeded.
            }
            0
        } catch (e: ErrnoException) {
            if (e.errno != 0) e.errno else EIO
        }
    }
}
