package org.openax12.feature.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.openax12.core.data.ModelEntry
import org.openax12.core.engine.ModelTemplates
import org.openax12.core.engine.TemplateOptionKey
import org.openax12.core.engine.TemplateOptions
import org.openax12.core.model.ModelBlueprint
import org.openax12.core.model.ModelType
import org.openax12.core.model.SourceId
import org.openax12.core.model.SourceInfo
import org.openax12.core.model.WorkingConfig
import org.openax12.core.runtime.SessionError
import org.openax12.core.runtime.SessionResult

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCreateViewModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val names = mapOf(
        ModelTemplates.GLIDER to "Glider",
        ModelTemplates.PLANE to "Plane",
        BLANK_TEMPLATE_ID to "New model",
    )

    private class Rig(existing: List<String> = emptyList(), linkUp: Boolean = false, names: Map<String, String>) {
        val repo = RepoFake(existing.mapIndexed { i, n -> ModelEntry(id = "$i", name = n) })
        val session = SessionFake()
        val created = mutableListOf<Pair<String, ModelBlueprint>>()
        val order = mutableListOf<String>()
        var linkUp = linkUp
        val vm: ModelCreateViewModel

        init {
            session.handlers["createModel"] = { args ->
                order += "create"
                created += (args[0] as String) to (args[1] as ModelBlueprint)
                SessionResult.Success(ModelEntry(id = "new", name = args[0] as String))
            }
            session.handlers["discard"] = { order += "discard"; SessionResult.Success(Unit) }
            vm = ModelCreateViewModel(repo.repository, session.session, { this.linkUp }) { names.getValue(it) }
        }
    }

    @Test
    fun `the suggested name follows the template until the user types`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf("Plane"), names = names)
        backgroundScope.launch { rig.vm.state.collect { } }
        assertEquals("Glider", rig.vm.state.value.name)

        rig.vm.selectTemplate(ModelTemplates.PLANE)
        assertEquals("Plane (2)", rig.vm.state.value.name)

        rig.vm.setName("My Wing")
        rig.vm.selectTemplate(ModelTemplates.GLIDER)
        assertEquals("My Wing", rig.vm.state.value.name)
    }

    @Test
    fun `a taken or invalid name blocks creating`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(listOf("Easy Star"), names = names)
        backgroundScope.launch { rig.vm.state.collect { } }

        rig.vm.setName("easy star")
        assertEquals(NameProblem.EXISTS, rig.vm.state.value.nameProblem)
        assertFalse(rig.vm.state.value.canCreate)
        rig.vm.create()
        assertTrue(rig.created.isEmpty())

        rig.vm.setName("a:b")
        assertEquals(NameProblem.INVALID_CHARACTERS, rig.vm.state.value.nameProblem)
    }

    @Test
    fun `creating builds the template blueprint and announces the new model`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(names = names)
        val events = mutableListOf<CreateEvent>()
        backgroundScope.launch { rig.vm.events.collect { events += it } }
        backgroundScope.launch { rig.vm.state.collect { } }

        rig.vm.updateOptions { it.copy(wingServos = 4, hasMotor = true) }
        rig.vm.create()

        val (name, blueprint) = rig.created.single()
        assertEquals("Glider", name)
        assertEquals(ModelType.GLIDER, blueprint.modelType)
        assertEquals(ModelTemplates.GLIDER, blueprint.templateId)
        assertEquals(listOf<CreateEvent>(CreateEvent.Created("Glider")), events)
        assertFalse(rig.vm.state.value.busy)
    }

    @Test
    fun `a blank model uses the chosen type and no template`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(names = names)
        backgroundScope.launch { rig.vm.state.collect { } }
        rig.vm.selectTemplate(BLANK_TEMPLATE_ID)
        rig.vm.setBlankType(ModelType.HELICOPTER)
        rig.vm.create()
        val blueprint = rig.created.single().second
        assertEquals(ModelType.HELICOPTER, blueprint.modelType)
        assertNull(blueprint.templateId)
    }

    @Test
    fun `discarding unsaved changes happens before the model is created`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(names = names)
        rig.session.dirty.value = true
        backgroundScope.launch { rig.vm.state.collect { } }

        rig.vm.create()
        assertEquals(GuardStep.UNSAVED, rig.vm.state.value.guard.step)
        assertTrue(rig.created.isEmpty())

        rig.vm.guardDiscard()
        assertEquals(listOf("discard", "create"), rig.order)
    }

    @Test
    fun `a linked radio is asked before the model is created`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(names = names, linkUp = true)
        backgroundScope.launch { rig.vm.state.collect { } }
        rig.vm.create()
        assertEquals(GuardStep.LINK_UP, rig.vm.state.value.guard.step)
        assertTrue(rig.created.isEmpty())
        rig.vm.guardConfirmLink()
        assertEquals(1, rig.created.size)
    }

    @Test
    fun `a failed creation reports the error and keeps the screen`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(names = names)
        rig.session.handlers["createModel"] = { SessionResult.Failure(SessionError.PLAN_OPERATION_FAILED) }
        val events = mutableListOf<CreateEvent>()
        backgroundScope.launch { rig.vm.events.collect { events += it } }
        backgroundScope.launch { rig.vm.state.collect { } }

        rig.vm.create()

        assertEquals(R.string.models_err_plan, (events.single() as CreateEvent.Message).message.res)
        assertFalse(rig.vm.state.value.busy)
    }

    // -- pure helpers --

    @Test
    fun `every template builds for the default options`() {
        for (template in ModelTemplates.all) {
            val blueprint = buildBlueprint(template.id, ModelType.OTHER, TemplateOptions(), WorkingConfig.EMPTY)
            assertEquals(template.modelType, blueprint.modelType)
            assertNotNull(blueprint.plan)
        }
    }

    @Test
    fun `switch options read and write the right field`() {
        var options = TemplateOptions()
        val switchKeys = listOf(
            TemplateOptionKey.MODE_SWITCH, TemplateOptionKey.ARM_SWITCH, TemplateOptionKey.BEEPER_SWITCH,
            TemplateOptionKey.THROTTLE_HOLD_SWITCH, TemplateOptionKey.FLAP_SWITCH,
        )
        switchKeys.forEachIndexed { i, key -> options = options.with(key, SourceId(Sources20 + i)) }
        switchKeys.forEachIndexed { i, key -> assertEquals(SourceId(Sources20 + i), options.switchOf(key)) }
        assertNull(options.switchOf(TemplateOptionKey.MOTOR))
        assertEquals(options, options.with(TemplateOptionKey.MOTOR, SourceId.SA))
    }

    @Test
    fun `switch choices come from the radio or fall back to SA to SH`() {
        val fallback = switchChoices(WorkingConfig.EMPTY)
        assertEquals(listOf("SA", "SB", "SC", "SD", "SE", "SF", "SG", "SH"), fallback.map { it.name })

        val config = WorkingConfig(
            sources = listOf(
                SourceInfo(index = SourceId.SA.index, name = "Arm"),
                SourceInfo(index = SourceId.SB.index, enabled = false),
                SourceInfo(index = SourceId.THR.index),
            ),
        )
        assertEquals(listOf("Arm"), switchChoices(config).map { it.name })
    }

    private companion object {
        /** SDK index of SA. */
        const val Sources20 = 20
    }
}
