package top.michubil.musictag.data.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SourceJsonTest {
    @Test
    fun stringsAreTrimmedWithoutCoercingOtherTypes() {
        assertEquals("Title", JSONObject().put("value", " Title ").string("value"))
        for (value in listOf("  ", 123, false, JSONObject.NULL, JSONObject())) {
            assertNull(JSONObject().put("value", value).string("value"))
        }
        assertNull(JSONObject().string("missing"))
    }

    @Test
    fun integersAcceptNumericStringsButRejectFractionsOverflowAndInvalidTypes() {
        for (value in listOf(123, 123L, " 123 ")) {
            assertEquals(123L, JSONObject().put("value", value).long("value"))
        }
        for (value in listOf(1.5, "1.5", "9223372036854775808", "bad", false, JSONObject.NULL)) {
            assertNull(JSONObject().put("value", value).long("value"))
        }
        assertEquals(0L, JSONObject().put("value", 0).long("value"))
        assertNull(JSONObject().long("missing"))
    }

    @Test
    fun booleansDoNotInventAbsenceFromMalformedFlags() {
        assertEquals(true, JSONObject().put("value", true).boolean("value"))
        assertEquals(false, JSONObject().put("value", false).boolean("value"))
        for (value in listOf("true", 1, JSONObject.NULL)) {
            assertNull(JSONObject().put("value", value).boolean("value"))
        }
    }

    @Test
    fun objectArraysKeepOrderAndSkipNonObjects() {
        val array = JSONArray("""[{"id":1},null,3,{"id":2}]""")
        assertEquals(listOf(1L, 2L), array.objects().map { it.long("id") })
        assertTrue((null as JSONArray?).objects().isEmpty())
    }
}
