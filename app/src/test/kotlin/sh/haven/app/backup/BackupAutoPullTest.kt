package sh.haven.app.backup

import android.content.Context
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository

@OptIn(ExperimentalCoroutinesApi::class)
class BackupAutoPullTest {

    private val context = mockk<Context>(relaxed = true)
    private val preferencesRepository = mockk<UserPreferencesRepository>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `scheduler cancels work when auto-pull is disabled`() = runTest {
        val enabledFlow = MutableStateFlow(false)
        every { preferencesRepository.backupAutoPullEnabled } returns enabledFlow

        val scheduler = BackupAutoPullScheduler(context, preferencesRepository)
        val testJob = Job()
        val childScope = CoroutineScope(coroutineContext + testJob)

        scheduler.start(childScope)
        runCurrent()

        verify(exactly = 1) { workManager.cancelUniqueWork("backup-auto-pull-periodic") }

        testJob.cancel()
    }

    @Test
    fun `scheduler schedules work when auto-pull is enabled`() = runTest {
        val enabledFlow = MutableStateFlow(true)
        every { preferencesRepository.backupAutoPullEnabled } returns enabledFlow

        val scheduler = BackupAutoPullScheduler(context, preferencesRepository)
        val testJob = Job()
        val childScope = CoroutineScope(coroutineContext + testJob)

        scheduler.start(childScope)
        runCurrent()

        verify(exactly = 1) { workManager.enqueueUniquePeriodicWork("backup-auto-pull-periodic", any(), any()) }

        testJob.cancel()
    }
}
