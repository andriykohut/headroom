package dev.andrii.headroom.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Shared test double for every task that needs a SecureStore. */
class InMemorySecureStore : SecureStore {
    private val values = mutableMapOf<String, String>()
    override fun put(key: String, value: String) { values[key] = value }
    override fun get(key: String): String? = values[key]
    override fun remove(key: String) { values.remove(key) }
}

class FakeSecureStoreTest {

    @Test
    fun `round trips a value`() {
        val store = InMemorySecureStore()
        store.put("k", "v")
        assertEquals("v", store.get("k"))
    }

    @Test
    fun `absent key reads null`() {
        assertNull(InMemorySecureStore().get("missing"))
    }

    @Test
    fun `remove clears the value`() {
        val store = InMemorySecureStore()
        store.put("k", "v")
        store.remove("k")
        assertNull(store.get("k"))
    }

    @Test
    fun `put overwrites`() {
        val store = InMemorySecureStore()
        store.put("k", "one")
        store.put("k", "two")
        assertEquals("two", store.get("k"))
    }
}
