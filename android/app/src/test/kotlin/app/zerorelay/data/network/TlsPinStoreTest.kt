package app.zerorelay.data.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TlsPinStoreTest {
    private lateinit var context: Context
    private val host = "relay.example.com"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TlsPinStore.clearHost(context, host)
    }

    @Test
    fun addPin_loadPins() {
        TlsPinStore.addPin(context, host, "sha256/AAAA")
        assertEquals(setOf("sha256/AAAA"), TlsPinStore.loadPins(context, host))
        assertTrue(TlsPinStore.hasPin(context, host))
    }

    @Test
    fun exportAll_importAll_roundTrip() {
        TlsPinStore.addPin(context, host, "sha256/OLD")
        TlsPinStore.addPin(context, host, "sha256/NEW")

        val exported = TlsPinStore.exportAll(context)
        TlsPinStore.clearHost(context, host)
        assertFalse(TlsPinStore.hasPin(context, host))

        TlsPinStore.importAll(context, exported)
        assertEquals(setOf("sha256/OLD", "sha256/NEW"), TlsPinStore.loadPins(context, host))
    }

    @Test
    fun importAll_mergesWhenReplaceFalse() {
        TlsPinStore.addPin(context, host, "sha256/KEPT")
        val incoming =
            JSONObject().apply {
                put(host, JSONArray(listOf("sha256/ADDED")))
                put("other.example.com", JSONArray(listOf("sha256/OTHER")))
            }

        TlsPinStore.importAll(context, incoming, replace = false)

        assertEquals(setOf("sha256/KEPT", "sha256/ADDED"), TlsPinStore.loadPins(context, host))
        assertEquals(setOf("sha256/OTHER"), TlsPinStore.loadPins(context, "other.example.com"))
    }
}
