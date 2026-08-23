package app.amber.core.memory.model

object MemoryWorkerDreamGate {
    fun isAnyDreamEnabled(worker: MemoryWorkerSetting): Boolean =
        worker.dreamMaintenanceEnabled || worker.dreamModelEnabled

    fun isMaintenanceEnabled(worker: MemoryWorkerSetting): Boolean =
        worker.dreamMaintenanceEnabled

    fun isModelDreamEnabled(worker: MemoryWorkerSetting): Boolean =
        worker.dreamModelEnabled
}
