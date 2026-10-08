package app.amber.feature.tools

import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocationAccessToolsTest {
    @Test
    fun equalTimestampChoosesTheMoreAccurateProvider() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val precise = location(LocationManager.GPS_PROVIDER, 1000L, 5f)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, precise)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, location(LocationManager.NETWORK_PROVIDER, 1000L, 50f))

        assertEquals(precise, latest(manager))
    }

    @Test
    fun aNewerLocationStillWinsRegardlessOfAccuracy() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val newer = location(LocationManager.NETWORK_PROVIDER, 2000L, 50f)
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, location(LocationManager.GPS_PROVIDER, 1000L, 5f))
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, newer)

        assertEquals(newer, latest(manager))
    }

    private fun latest(manager: LocationManager): Location? {
        val method = Class.forName("app.amber.feature.tools.LocationAccessToolsKt")
            .getDeclaredMethod("latestLocation", LocationManager::class.java)
        method.isAccessible = true
        return method.invoke(null, manager) as Location?
    }

    private fun location(provider: String, timestamp: Long, meters: Float) = Location(provider).apply {
        time = timestamp
        accuracy = meters
    }
}
