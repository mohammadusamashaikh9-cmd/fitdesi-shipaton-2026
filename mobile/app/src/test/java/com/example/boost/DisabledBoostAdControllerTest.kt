package com.example.boost

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DisabledBoostAdControllerTest {
    @Test
    fun `disabled controller never exposes a rewarded path`() {
        val controller = DisabledBoostAdController()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        controller.prepare(activity)
        controller.show(activity)

        assertEquals(BoostAdState.Disabled, controller.state.value)
    }
}
