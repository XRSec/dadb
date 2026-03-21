package dadb.android.wireless.internal

import android.net.nsd.NsdServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import sun.misc.Unsafe

class AndroidServiceInfoCallbackTest {
    @Test
    fun callbackMethods_forwardToHandlersExactlyOnce() {
        val events = mutableListOf<String>()
        var updatedService: NsdServiceInfo? = null
        val serviceInfo = allocateWithoutAndroidConstructor<NsdServiceInfo>()
        val callback =
            AndroidServiceInfoCallback(
                registrationFailedHandler = { events += "registration-failed:$it" },
                serviceUpdatedHandler = {
                    events += "service-updated"
                    updatedService = it
                },
                serviceLostHandler = { events += "service-lost" },
                unregisteredHandler = { events += "unregistered" },
            )

        callback.onServiceInfoCallbackRegistrationFailed(7)
        callback.onServiceUpdated(serviceInfo)
        callback.onServiceLost()
        callback.onServiceInfoCallbackUnregistered()

        assertEquals(
            listOf("registration-failed:7", "service-updated", "service-lost", "unregistered"),
            events,
        )
        assertSame(serviceInfo, updatedService)
    }

    @Test
    fun resolveListenerMethods_forwardToHandlersExactlyOnce() {
        val events = mutableListOf<String>()
        var resolvedService: NsdServiceInfo? = null
        val serviceInfo = allocateWithoutAndroidConstructor<NsdServiceInfo>()
        val listener =
            AndroidResolveListener(
                serviceResolvedHandler = {
                    events += "service-resolved"
                    resolvedService = it
                },
                resolveFailedHandler = { events += "resolve-failed:$it" },
            )

        listener.onResolveFailed(serviceInfo, 8)
        listener.onServiceResolved(serviceInfo)

        assertEquals(listOf("resolve-failed:8", "service-resolved"), events)
        assertSame(serviceInfo, resolvedService)
    }

    private inline fun <reified T> allocateWithoutAndroidConstructor(): T {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null).let { it as Unsafe }.allocateInstance(T::class.java) as T
    }
}
