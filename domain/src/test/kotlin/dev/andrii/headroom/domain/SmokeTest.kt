package dev.andrii.headroom.domain

import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun `domain module has no android on its classpath`() {
        // If :domain ever gains an Android dependency this starts passing the
        // load, which is exactly what we want to catch: the spec requires the
        // logic to stay framework-free.
        val androidPresent = try {
            Class.forName("android.content.Context"); true
        } catch (_: ClassNotFoundException) {
            false
        }
        assertTrue(!androidPresent, "android.* must not be on :domain's classpath")
    }
}
