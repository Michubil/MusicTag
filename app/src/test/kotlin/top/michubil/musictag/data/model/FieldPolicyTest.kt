package top.michubil.musictag.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldPolicyTest {
    @Test
    fun unavailableAndDisabledFieldsNeverChangeExistingData() {
        for (existing in listOf(false, true)) {
            for (overwrite in listOf(false, true)) {
                assertFalse(FieldPolicy(overwrite = overwrite).shouldWrite(RemoteValue.Unavailable, existing))
                for (value in listOf(RemoteValue.Available("new"), RemoteValue.ConfirmedAbsent, RemoteValue.Unavailable)) {
                    assertFalse(FieldPolicy(enabled = false, overwrite = overwrite).shouldWrite(value, existing))
                }
            }
        }
    }

    @Test
    fun fillMissingPreservesExistingValuesAndNeverClears() {
        val policy = FieldPolicy(overwrite = false)
        assertTrue(policy.shouldWrite(RemoteValue.Available("new"), false))
        assertFalse(policy.shouldWrite(RemoteValue.Available("new"), true))
        assertFalse(policy.shouldWrite(RemoteValue.ConfirmedAbsent, false))
        assertFalse(policy.shouldWrite(RemoteValue.ConfirmedAbsent, true))
    }

    @Test
    fun overwriteAllowsReplacementAndExplicitClearing() {
        for (existing in listOf(false, true)) {
            assertTrue(FieldPolicy().shouldWrite(RemoteValue.Available("new"), existing))
            assertTrue(FieldPolicy().shouldWrite(RemoteValue.ConfirmedAbsent, existing))
        }
    }

    @Test
    fun writeChangeOnlyMutatesWhenTheSharedPolicyAllowsIt() {
        val writes = mutableListOf<String>()
        val clears = mutableListOf<Int>()
        FieldPolicy(overwrite = false).writeChange(RemoteValue.Available("keep"), true, writes::add) { clears += 1 }
        FieldPolicy(overwrite = false).writeChange(RemoteValue.Available("fill"), false, writes::add) { clears += 1 }
        FieldPolicy().writeChange(RemoteValue.ConfirmedAbsent, true, writes::add) { clears += 1 }
        FieldPolicy().writeChange(RemoteValue.Unavailable, false, writes::add) { clears += 1 }
        assertEquals(listOf("fill"), writes)
        assertEquals(listOf(1), clears)
    }
}
