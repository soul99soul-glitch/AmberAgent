package app.amber.feature.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectAvailabilityTest {
    @Test
    fun api33IsUnsupportedEvenIfServiceIsReported() {
        assertEquals(
            HealthConnectAvailability.UNSUPPORTED_BY_OS_VERSION,
            HealthConnectAvailabilityDetector.detect(33, servicePresent = true),
        )
    }

    @Test
    fun api34RequiresSystemService() {
        assertEquals(
            HealthConnectAvailability.AVAILABLE,
            HealthConnectAvailabilityDetector.detect(34, servicePresent = true),
        )
        assertEquals(
            HealthConnectAvailability.SERVICE_NOT_FOUND,
            HealthConnectAvailabilityDetector.detect(34, servicePresent = false),
        )
    }

    @Test
    fun everyStateHasChineseExplanation() {
        HealthConnectAvailability.entries.forEach { assertTrue(it.message.isNotBlank()) }
        HealthAdapterState.entries.forEach { assertTrue(HealthAdapterStatusText.forState(it).isNotBlank()) }
    }

    @Test
    fun plannedExerciseOnlyStartsAtApi35() {
        assertEquals(PlannedExerciseCapability.UNSUPPORTED, PlannedExerciseFeatureProbe.probe(34))
    }

    @Test
    fun authorizationAndReadFailuresAreNotSuccess() {
        assertEquals(HealthAdapterState.READY, HealthAdapterStateMachine.afterAuthorization(true))
        assertEquals(HealthAdapterState.AUTHORIZATION_DENIED, HealthAdapterStateMachine.afterAuthorization(false))
        assertEquals(HealthAdapterState.AUTHORIZATION_REVOKED, HealthAdapterStateMachine.afterReadFailure(true))
        assertEquals(HealthAdapterState.READ_FAILED, HealthAdapterStateMachine.afterReadFailure(false))
    }
}
