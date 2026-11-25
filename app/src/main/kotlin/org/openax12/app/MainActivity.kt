package org.openax12.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openax12.app.shell.Ax12Root
import org.openax12.core.navigation.ShellActions

/**
 * The only activity: landscape-locked, edge-to-edge, immersive, `singleTask`. It hosts the
 * Compose shell ([Ax12Root]), starts [RadioService], asks for the runtime permissions and routes
 * incoming `.oax12` files to the model import.
 *
 * Permissions: the storage permission on API <= 29 (the model library is `/sdcard/OpenAX12`) and
 * `POST_NOTIFICATIONS` from API 33, in one system dialog on the first start. Later requests come
 * from the screens through `LocalShellActions`.
 */
class MainActivity : ComponentActivity() {

    private var pendingImport by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsAnswered() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // A restored activity must not re-open the file it was started with.
        if (savedInstanceState == null) pendingImport = importUriOf(intent)

        val graph = applicationContext.appGraph
        if (!RadioService.start(this)) {
            // No foreground service: the graph still has to start; the process is simply less protected.
            graph.scope.launch { graph.start() }
        }
        requestPermissionsOnFirstStart()

        val shellActions = ShellActions(
            requestStoragePermission = ::requestStoragePermission,
            openAppSettings = ::openAppSettings,
        )
        setContent {
            Ax12Root(
                graph = graph,
                window = window,
                shellActions = shellActions,
                pendingImport = pendingImport,
                onImportHandled = { pendingImport = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importUriOf(intent)?.let { pendingImport = it }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system settings with the storage permission granted there.
        val models = applicationContext.appGraph.models
        if (needsStoragePermission() && hasStoragePermission() && !models.storage.value.permissionGranted) {
            refreshLibrary()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back after dialogs or a swipe from the edge; hide them again.
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- Permissions -----------------------------------------------------------------------------

    /** The storage permission only exists as a runtime permission up to API 29. */
    private fun needsStoragePermission(): Boolean = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q

    private fun hasStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions(): List<String> = buildList {
        if (needsStoragePermission() && !hasStoragePermission()) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** One system dialog for everything that is missing, asked once per installation. */
    private fun requestPermissionsOnFirstStart() {
        val missing = missingPermissions()
        if (missing.isEmpty()) return
        val preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_PERMISSIONS_ASKED, false)) return
        preferences.edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestStoragePermission() {
        if (needsStoragePermission() && !hasStoragePermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // No settings app: nothing else can be done here.
        }
    }

    private fun onPermissionsAnswered() = refreshLibrary()

    /**
     * Re-reads the storage state and reconciles the library once the radio is up (CONTRACTS.md,
     * "What the app shell owns"). The graph's own startup reconciles too; a second run is harmless.
     */
    private fun refreshLibrary() {
        val graph = applicationContext.appGraph
        graph.scope.launch {
            graph.models.refreshStorage()
            graph.startup.first { it.isRunning }
            graph.models.reconcileLibrary()
        }
    }

    private companion object {
        const val PREFERENCES = "shell"
        const val KEY_PERMISSIONS_ASKED = "permissions_asked"
    }
}
