package com.jodli.coffeeshottimer.integration

import android.content.Context
import android.content.SharedPreferences
import com.jodli.coffeeshottimer.data.model.Bean
import com.jodli.coffeeshottimer.data.model.GrinderConfiguration
import com.jodli.coffeeshottimer.data.model.Shot
import com.jodli.coffeeshottimer.data.model.ValidationResult
import com.jodli.coffeeshottimer.data.repository.BasketConfigRepository
import com.jodli.coffeeshottimer.data.repository.BeanRepository
import com.jodli.coffeeshottimer.data.repository.GrinderConfigRepository
import com.jodli.coffeeshottimer.data.repository.ShotRepository
import com.jodli.coffeeshottimer.domain.usecase.CalculateGrindAdjustmentUseCase
import com.jodli.coffeeshottimer.domain.usecase.GetShotDetailsUseCase
import com.jodli.coffeeshottimer.domain.usecase.GetTastePreselectionUseCase
import com.jodli.coffeeshottimer.domain.usecase.ManageGrindRecommendationUseCase
import com.jodli.coffeeshottimer.domain.usecase.RecordShotUseCase
import com.jodli.coffeeshottimer.domain.usecase.RecordTasteFeedbackUseCase
import com.jodli.coffeeshottimer.domain.usecase.ShotRecordingState
import com.jodli.coffeeshottimer.domain.usecase.TimerState
import com.jodli.coffeeshottimer.ui.util.DomainErrorTranslator
import com.jodli.coffeeshottimer.ui.util.StringResourceProvider
import com.jodli.coffeeshottimer.ui.validation.ValidationStringProvider
import com.jodli.coffeeshottimer.ui.viewmodel.ShotRecordingViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.time.LocalDate

/**
 * Integration test for shot recording functionality including draft auto-save.
 */
@ExperimentalCoroutinesApi
class ShotRecordingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var recordShotUseCase: RecordShotUseCase
    private lateinit var getShotDetailsUseCase: GetShotDetailsUseCase
    private lateinit var getTastePreselectionUseCase: GetTastePreselectionUseCase
    private lateinit var recordTasteFeedbackUseCase: RecordTasteFeedbackUseCase
    private lateinit var calculateGrindAdjustmentUseCase: CalculateGrindAdjustmentUseCase
    private lateinit var manageGrindRecommendationUseCase: ManageGrindRecommendationUseCase
    private lateinit var beanRepository: BeanRepository
    private lateinit var shotRepository: ShotRepository
    private lateinit var stringResourceProvider: StringResourceProvider
    private lateinit var domainErrorTranslator: DomainErrorTranslator
    private lateinit var validationStringProvider: ValidationStringProvider
    private lateinit var grinderConfigRepository: GrinderConfigRepository
    private lateinit var basketConfigRepository: BasketConfigRepository
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var viewModel: ShotRecordingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Create mock dependencies
        recordShotUseCase = mockk(relaxed = true)
        getShotDetailsUseCase = mockk(relaxed = true)
        getTastePreselectionUseCase = mockk(relaxed = true)
        recordTasteFeedbackUseCase = mockk(relaxed = true)
        calculateGrindAdjustmentUseCase = mockk(relaxed = true)
        manageGrindRecommendationUseCase = mockk(relaxed = true)
        beanRepository = mockk(relaxed = true)
        shotRepository = mockk(relaxed = true)
        stringResourceProvider = mockk(relaxed = true)
        domainErrorTranslator = mockk(relaxed = true)
        validationStringProvider = mockk(relaxed = true)
        grinderConfigRepository = mockk(relaxed = true)
        basketConfigRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        // Mock the StateFlow properties that cause ClassCastException
        val mockTimerState = MutableStateFlow(TimerState(elapsedTimeSeconds = 28, isRunning = false))
        val mockRecordingState = MutableStateFlow(ShotRecordingState())
        every { recordShotUseCase.timerState } returns mockTimerState.asStateFlow()
        every { recordShotUseCase.recordingState } returns mockRecordingState.asStateFlow()

        // Mock SharedPreferences behavior
        every { context.getSharedPreferences("shot_drafts", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } just Runs
        every { sharedPreferences.getString("current_draft", null) } returns null

        // Mock bean repository to return test beans
        val testBean = Bean(
            id = "test-bean-1",
            name = "Test Bean",
            roastDate = LocalDate.now().minusDays(5),
            notes = "Test notes",
            isActive = true
        )
        every { beanRepository.getActiveBeans() } returns flowOf(Result.success(listOf(testBean)))

        // Mock the new current bean methods I added
        coEvery { beanRepository.getCurrentBean() } returns Result.success(null)
        coEvery { beanRepository.setCurrentBean(any()) } returns Result.success(Unit)
        every { beanRepository.clearCurrentBean() } just Runs
        every { beanRepository.getCurrentBeanId() } returns null

        // Mock use case validation and recording
        coEvery { recordShotUseCase.validateShotParameters(any(), any(), any(), any(), any(), any()) } returns
            ValidationResult(isValid = true, errors = emptyList())

        // Mock the new grinder setting suggestion method I added
        coEvery { recordShotUseCase.getSuggestedGrinderSetting(any()) } returns Result.success("15")

        // Mock grinder configuration repository to return default config
        coEvery {
            grinderConfigRepository.getOrCreateDefaultConfig()
        } returns Result.success(GrinderConfiguration.DEFAULT_CONFIGURATION)

        // Mock timer update method
        coEvery { recordShotUseCase.updateTimer() } just Runs
        every { recordShotUseCase.clearError() } just Runs

        val testShot = Shot(
            id = "test-shot-1",
            beanId = "test-bean-1",
            coffeeWeightIn = 18.0,
            coffeeWeightOut = 36.0,
            extractionTimeSeconds = 28,
            grinderSetting = "15",
            notes = "Test shot"
        )
        coEvery { recordShotUseCase.recordShotWithCurrentTimer(any(), any(), any(), any(), any()) } returns
            Result.success(testShot)

        // Create ViewModel
        viewModel = ShotRecordingViewModel(
            recordShotUseCase,
            getShotDetailsUseCase,
            recordTasteFeedbackUseCase,
            calculateGrindAdjustmentUseCase,
            manageGrindRecommendationUseCase,
            beanRepository,
            shotRepository,
            domainErrorTranslator,
            stringResourceProvider,
            validationStringProvider,
            grinderConfigRepository,
            basketConfigRepository,
            context,
            androidx.lifecycle.SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    @Ignore("somewhere there's a race condition in the recordShot() fun.")
    fun `should save draft to SharedPreferences when form has data`() = runTest {
        // Given: Form has some data
        viewModel.updateCoffeeWeightIn("18.0")
        viewModel.updateCoffeeWeightOut("36.0")
        viewModel.updateGrinderSetting("15")

        // When: Manual draft save is triggered
        viewModel.saveDraftManually()
        testDispatcher.scheduler.advanceTimeBy(500)

        // Then: SharedPreferences should be called to save draft
        verify { editor.putString("current_draft", any()) }
        verify { editor.apply() }
    }

    @Test
    @Ignore("somewhere there's a race condition in the recordShot() fun.")
    fun `should show success message after successful shot recording`() = runTest {
        // Given: Valid form data
        viewModel.updateCoffeeWeightIn("18.0")
        viewModel.updateCoffeeWeightOut("36.0")
        viewModel.updateGrinderSetting("15")

        // Mock bean selection
        val testBean = Bean(
            id = "test-bean-1",
            name = "Test Bean",
            roastDate = LocalDate.now().minusDays(5)
        )
        viewModel.selectBean(testBean)
        testDispatcher.scheduler.advanceTimeBy(500)

        // When: Shot is recorded
        viewModel.recordShot()
        testDispatcher.scheduler.advanceTimeBy(1000)

        // Then: Success message should be set
        assertNotNull(viewModel.successMessage.value)
        assertTrue(viewModel.successMessage.value!!.contains("Shot recorded successfully"))
        assertTrue(viewModel.successMessage.value!!.contains("1:2.0")) // Brew ratio
    }

    @Test
    @Ignore("somewhere there's a race condition in the recordShot() fun.")
    fun `should clear draft after successful shot recording`() = runTest {
        // Given: Form has data and draft exists
        viewModel.updateCoffeeWeightIn("18.0")
        viewModel.updateCoffeeWeightOut("36.0")
        viewModel.updateGrinderSetting("15")

        // Mock bean selection
        val testBean = Bean(
            id = "test-bean-1",
            name = "Test Bean",
            roastDate = LocalDate.now().minusDays(5)
        )
        viewModel.selectBean(testBean)
        testDispatcher.scheduler.advanceTimeBy(500)

        // When: Shot is recorded successfully
        viewModel.recordShot()
        testDispatcher.scheduler.advanceTimeBy(500)

        // Then: Draft should be cleared from SharedPreferences
        verify { editor.remove("current_draft") }
        verify { editor.apply() }
    }

    @Test
    @Ignore("somewhere there's a race condition in the recordShot() fun.")
    fun `should handle validation errors gracefully`() = runTest {
        // Given: Invalid form data and validation failure
        coEvery { recordShotUseCase.validateShotParameters(any(), any(), any(), any(), any(), any()) } returns
            ValidationResult(isValid = false, errors = listOf("Coffee weight is too low"))

        viewModel.updateCoffeeWeightIn("0.05") // Invalid weight
        viewModel.updateCoffeeWeightOut("36.0")
        viewModel.updateGrinderSetting("15")

        val testBean = Bean(id = "test-bean-1", name = "Test Bean", roastDate = LocalDate.now())
        viewModel.selectBean(testBean)
        testDispatcher.scheduler.advanceTimeBy(500)

        // When: Shot recording is attempted
        viewModel.recordShot()
        testDispatcher.scheduler.advanceTimeBy(500)

        // Then: Error message should be displayed
        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("Coffee weight is too low"))

        // And: Success message should not be set
        assertNull(viewModel.successMessage.value)
    }

    // Weight entry tests. These deliberately do NOT use runTest: the ViewModel keeps a
    // perpetual `while (isActive) { delay(...) }` coroutine on Dispatchers.Main, so runTest
    // would advance virtual time forever and hang instead of finishing. The functions under
    // test are synchronous, so asserting on the StateFlow values directly is enough.

    @Test
    fun `accepts extracted amount above the basket configuration maximum`() {
        // Given: a weight well above the default basket coffeeOutMax of 55g, which used to
        // cap the +/- stepper and made ratios beyond roughly 1:3 unreachable
        viewModel.updateCoffeeWeightOut("80")

        // Then: it is stored as typed and flagged as valid
        assertEquals("80", viewModel.coffeeWeightOut.value)
        assertNull(viewModel.coffeeWeightOutError.value)
    }

    @Test
    fun `accepts coffee in above the basket configuration maximum`() {
        viewModel.updateCoffeeWeightIn("35")

        assertEquals("35", viewModel.coffeeWeightIn.value)
        assertNull(viewModel.coffeeWeightInError.value)
    }

    @Test
    fun `rejects extracted amount above the domain maximum`() {
        every { stringResourceProvider.getString(any(), *anyVararg()) } returns "too high"

        viewModel.updateCoffeeWeightOut("101")

        assertEquals("too high", viewModel.coffeeWeightOutError.value)
    }

    @Test
    fun `rejects extracted amount below one gram`() {
        every { stringResourceProvider.getString(any(), *anyVararg()) } returns "too low"

        viewModel.updateCoffeeWeightOut("0")

        assertEquals("too low", viewModel.coffeeWeightOutError.value)
    }

    @Test
    fun `rejects a non-numeric extracted amount`() {
        every { stringResourceProvider.getString(any(), *anyVararg()) } returns "not a number"

        viewModel.updateCoffeeWeightOut("abc")

        assertEquals("not a number", viewModel.coffeeWeightOutError.value)
    }

    @Test
    fun `treats a blank extracted amount as incomplete rather than invalid`() {
        viewModel.updateCoffeeWeightOut("")

        assertNull(viewModel.coffeeWeightOutError.value)
        assertFalse(viewModel.isFormValid.value)
    }
}
