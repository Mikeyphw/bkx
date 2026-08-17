package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.core.backend.BackendReadiness
import com.mikeyphw.bkx.android.core.backend.FakeBackendPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BkxAppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun refreshBackendUpdatesStructuredReadinessState() = runTest(dispatcher) {
        val backend = FakeBackendPort(BackendReadiness.RunCommandPermissionMissing)
        val viewModel = BkxAppViewModel(backend)

        advanceUntilIdle()

        assertEquals(BackendReadiness.RunCommandPermissionMissing, viewModel.state.value.readiness)
        assertEquals(false, viewModel.state.value.isRefreshing)
    }
}
