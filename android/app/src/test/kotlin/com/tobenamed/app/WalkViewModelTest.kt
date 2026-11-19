package com.tobenamed.app

import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WalkViewModelTest {
    @Test
    fun `empty input is rejected and unit details survive review`() {
        val model = WalkViewModel(SavedStateHandle())
        model.reviewAddress()
        assertEquals(Screen.INPUT, model.state.value.screen)
        assertNotNull(model.state.value.inputError)
        model.editAddress("  123 Main Street\nApt 4B, rear door  ")
        model.reviewAddress()
        assertEquals(Screen.ADDRESS, model.state.value.screen)
        assertEquals("123 Main Street\nApt 4B, rear door", model.state.value.address)
    }

    @Test
    fun `sharing during a walk returns to review without using the real address in the demo`() {
        val model = WalkViewModel(SavedStateHandle())
        model.startDemo()
        model.receiveAddress("84 High Street, Unit 2")
        assertEquals(Screen.INPUT, model.state.value.screen)
        assertNull(model.state.value.walk)
        assertEquals("84 High Street, Unit 2", model.state.value.address)
    }

    @Test
    fun `address draft survives process recreation and imported text is bounded`() {
        val handle = SavedStateHandle()
        val model = WalkViewModel(handle)
        model.editAddress("A".repeat(5_000))
        assertEquals(2_000, WalkViewModel(handle).state.value.address.length)
    }
}
