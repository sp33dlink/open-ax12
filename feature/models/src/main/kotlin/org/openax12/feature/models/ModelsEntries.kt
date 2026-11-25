package org.openax12.feature.models

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.Flow
import org.openax12.core.designsystem.AxToastKind
import org.openax12.core.designsystem.LocalAxToastState
import org.openax12.core.engine.ModelTemplates
import org.openax12.core.navigation.LocalNavigator
import org.openax12.core.navigation.LocalShellActions
import org.openax12.core.navigation.ModelCreate
import org.openax12.core.navigation.ModelDetail
import org.openax12.core.navigation.ModelImport
import org.openax12.core.navigation.Models
import org.openax12.core.navigation.PictureCrop
import org.openax12.core.runtime.AppGraph
import org.openax12.core.runtime.LocalAppGraph
import java.util.UUID

/**
 * Installs the entries of `:feature:models`: [Models], [ModelDetail], [ModelCreate],
 * [ModelImport] and [PictureCrop].
 */
fun EntryProviderScope<NavKey>.modelsEntries() {
    entry<Models> { ModelsRoute() }
    entry<ModelDetail> { key -> ModelDetailRoute(key.modelId) }
    entry<ModelCreate> { ModelCreateRoute() }
    entry<ModelImport> { key -> ModelImportRoute(key.uri) }
    entry<PictureCrop> { key -> PictureCropRoute(key.modelId, key.sourceUri) }
}

// -- Shared plumbing ---------------------------------------------------------------------------

/** True if the radio link is up right now (read on demand, never observed per live tick). */
private fun AppGraph.linkUp(): Boolean = radio.live.value.status.isLinkUp

/**
 * A view model whose lifetime is one visit of the screen: the key is unique per composition of
 * the entry (and survives recreation), so a second visit never sees the state of the first even
 * when the host scopes view models to the activity.
 */
@Composable
private inline fun <reified VM : ViewModel> screenViewModel(prefix: String, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalAppGraph.current
    val instance = rememberSaveable { UUID.randomUUID().toString() }
    return viewModel(key = "$prefix.$instance", factory = viewModelFactory { initializer { create(graph) } })
}

/** Shows [UiMessage]s as toasts. */
@Composable
private fun rememberToaster(): (UiMessage) -> Unit {
    val toast = LocalAxToastState.current
    val context = LocalContext.current
    return remember(toast, context) {
        { message -> toast.show(context.getString(message.res, *message.args.toTypedArray()), message.kind) }
    }
}

/** Collects one-shot [events] for as long as the screen is composed. */
@Composable
private fun <T> CollectEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val current = rememberUpdatedState(onEvent)
    LaunchedEffect(events) { events.collect { current.value(it) } }
}

/** Opens the share sheet for an exported model archive; false if nothing can handle it. */
private fun shareArchive(context: Context, uri: Uri, modelName: String, chooserTitle: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, modelName)
        // The chooser only forwards the read grant for URIs in the clip data (API 28).
        clipData = ClipData.newRawUri(modelName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

// -- Routes --------------------------------------------------------------------------------------

@Composable
private fun ModelsRoute() {
    val navigator = LocalNavigator.current
    val shell = LocalShellActions.current
    val vm = screenViewModel("models.list") { ModelsViewModel(it.models, it.configSession, it::linkUp) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = rememberToaster()
    CollectEvents(vm.events) { event ->
        when (event) {
            is ModelsEvent.Message -> toast(event.message)
        }
    }
    val nowMs = remember { System.currentTimeMillis() }
    ModelsScreen(
        state = state,
        nowMs = nowMs,
        actions = ModelsActions(
            onOpen = { navigator.navigate(ModelDetail(it.id)) },
            onSelect = vm::select,
            onNew = { navigator.navigate(ModelCreate) },
            onImport = { navigator.navigate(ModelImport(null)) },
            onLayout = vm::setLayout,
            onSort = vm::setSort,
            onAllowStorage = shell.requestStoragePermission,
            onWriteToRadio = vm::writeToRadio,
            onReview = vm::review,
            onAcceptChange = vm::acceptChange,
            onRejectChange = vm::rejectChange,
            onDismissReview = vm::dismissReview,
            onGuardSave = vm::guardSave,
            onGuardDiscard = vm::guardDiscard,
            onGuardConfirmLink = vm::guardConfirmLink,
            onGuardCancel = vm::guardCancel,
        ),
    )
}

@Composable
private fun ModelDetailRoute(modelId: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val vm = screenViewModel("models.detail.$modelId") { ModelDetailViewModel(modelId, it.models, it.configSession, it::linkUp) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toast = rememberToaster()
    val chooserTitle = stringResource(R.string.models_share_title)
    CollectEvents(vm.events) { event ->
        when (event) {
            is DetailEvent.Message -> toast(event.message)
            is DetailEvent.Share -> if (!shareArchive(context, event.uri, event.modelName, chooserTitle)) {
                toast(UiMessage(R.string.models_err_no_share_target, kind = AxToastKind.Error))
            }
        }
    }
    LaunchedEffect(state.notFound) { if (state.notFound) navigator.back() }
    val pickImage = rememberFilePicker(
        mimeType = "image/*",
        onPicked = { uri -> if (uri != null) navigator.navigate(PictureCrop(modelId, uri.toString())) },
        onUnavailable = { toast(UiMessage(R.string.models_err_no_picker, kind = AxToastKind.Error)) },
    )
    val nowMs = remember { System.currentTimeMillis() }
    ModelDetailScreen(
        state = state,
        nowMs = nowMs,
        actions = DetailActions(
            onBack = { navigator.back() },
            onSelect = vm::select,
            onShowDialog = vm::showDialog,
            onDismissDialog = vm::dismissDialog,
            onRename = vm::rename,
            onDuplicate = vm::duplicate,
            onDelete = vm::delete,
            onSetType = vm::setType,
            onSetModelMatch = vm::setModelMatch,
            onSetNotes = vm::setNotes,
            onChangePicture = pickImage,
            onRemovePicture = vm::removePicture,
            onExport = vm::export,
            onRestoreSnapshot = vm::restore,
            onDeleteSnapshot = vm::deleteSnapshot,
            onGuardSave = vm::guardSave,
            onGuardDiscard = vm::guardDiscard,
            onGuardConfirmLink = vm::guardConfirmLink,
            onGuardCancel = vm::guardCancel,
        ),
    )
}

@Composable
private fun ModelCreateRoute() {
    val navigator = LocalNavigator.current
    val toast = rememberToaster()
    val appContext = LocalContext.current.applicationContext
    val vm = screenViewModel("models.create") { graph ->
        ModelCreateViewModel(graph.models, graph.configSession, graph::linkUp) { templateId ->
            appContext.getString(defaultNameRes(templateId))
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events) { event ->
        when (event) {
            is CreateEvent.Message -> toast(event.message)
            is CreateEvent.Created -> {
                toast(UiMessage(R.string.models_created, listOf(event.name), AxToastKind.Success))
                navigator.back()
            }
        }
    }
    ModelCreateScreen(
        state = state,
        actions = CreateActions(
            onBack = { navigator.back() },
            onSelectTemplate = vm::selectTemplate,
            onBlankType = vm::setBlankType,
            onName = vm::setName,
            onOptions = vm::updateOptions,
            onCreate = vm::create,
            onGuardSave = vm::guardSave,
            onGuardDiscard = vm::guardDiscard,
            onGuardConfirmLink = vm::guardConfirmLink,
            onGuardCancel = vm::guardCancel,
        ),
    )
}

/** String resource of the suggested name of a new model per template. */
private fun defaultNameRes(templateId: String): Int = when (templateId) {
    ModelTemplates.GLIDER -> R.string.models_template_glider
    ModelTemplates.PLANE -> R.string.models_template_plane
    ModelTemplates.FLYING_WING -> R.string.models_template_wing
    ModelTemplates.MULTIROTOR -> R.string.models_template_multirotor
    ModelTemplates.HELICOPTER -> R.string.models_template_helicopter
    else -> R.string.models_template_blank_name
}

@Composable
private fun ModelImportRoute(uri: String?) {
    val navigator = LocalNavigator.current
    val toast = rememberToaster()
    val vm = screenViewModel("models.import") { ModelImportViewModel(it.models) }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events) { event ->
        when (event) {
            is ImportEvent.Message -> toast(event.message)
            is ImportEvent.Imported -> {
                toast(UiMessage(R.string.models_imported, listOf(event.name), AxToastKind.Success))
                navigator.back()
            }
        }
    }
    val choose = rememberFilePicker(
        mimeType = "*/*",
        onPicked = { picked ->
            when {
                picked != null -> vm.inspect(picked)
                // Cancelled before any file was chosen: nothing to show here.
                vm.state.value.phase == ImportPhase.Idle -> navigator.back()
            }
        },
        onUnavailable = { toast(UiMessage(R.string.models_err_no_picker, kind = AxToastKind.Error)) },
    )
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uri) {
        if (started) return@LaunchedEffect
        started = true
        if (uri != null) vm.inspect(Uri.parse(uri)) else choose()
    }
    ModelImportScreen(
        state = state,
        actions = ImportActions(
            onBack = { navigator.back() },
            onChoose = choose,
            onName = vm::setName,
            onImport = vm::import,
        ),
    )
}

@Composable
private fun PictureCropRoute(modelId: String, sourceUri: String) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val toast = rememberToaster()
    val vm = screenViewModel("models.crop.$modelId") { graph ->
        val appContext = context.applicationContext
        PictureCropViewModel(modelId, sourceUri, graph.models) { PictureDecoder.decode(appContext, Uri.parse(sourceUri)) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    CollectEvents(vm.events) { event ->
        when (event) {
            is CropEvent.Message -> toast(event.message)
            CropEvent.Saved -> {
                toast(UiMessage(R.string.models_picture_saved, kind = AxToastKind.Success))
                navigator.back()
            }
        }
    }
    val image = remember(state.image) { state.image?.asImageBitmap() }
    PictureCropScreen(
        loading = state.loading,
        image = image,
        saving = state.saving,
        onBack = { navigator.back() },
        onSave = vm::save,
    )
}
