package com.mikeyphw.bkx.android.core.operations

interface OperationStateStore {
    suspend fun read(): OperationRuntimeState
    suspend fun update(state: OperationRuntimeState)
}

class InMemoryOperationStateStore(
    initial: OperationRuntimeState = OperationRuntimeState(),
) : OperationStateStore {
    private var current: OperationRuntimeState = initial

    override suspend fun read(): OperationRuntimeState = current

    override suspend fun update(state: OperationRuntimeState) {
        current = state
    }
}
