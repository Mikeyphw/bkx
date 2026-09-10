package com.mikeyphw.bkx.android.app

import com.mikeyphw.bkx.android.app.navigation.ScreenStatus
import com.mikeyphw.bkx.android.core.jobs.SavedJob
import com.mikeyphw.bkx.android.core.repositories.RepositoryDefinition

data class SavedJobsUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val items: List<SavedJob> = emptyList(),
    val actionInProgress: String? = null,
    val lastActionMessage: String? = null,
)

data class RepositoriesUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val items: List<RepositoryDefinition> = emptyList(),
    val actionInProgress: String? = null,
    val messages: Map<String, String> = emptyMap(),
)
