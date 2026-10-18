package com.remu.app.core

import com.remu.app.core.observability.SentryScrubber
import com.remu.app.core.observability.SentrySetup
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-118 (app half) and the leak guard: no phone, name, note, token or binary in an event. */
class SentryScrubberTest {
    @Test fun scrubsEverySensitivePlace() {
        val e = SentryEvent()
        e.user = User().apply { id = "u_1"; email = "o@x.in"; username = "Rajesh" }
        e.request = Request().apply { data = "{\"phone\":\"9876543210\"}" }
        e.message = Message().apply { formatted = "enrol failed for +91 98765 43210 owner o@x.in" }
        e.exceptions = listOf(SentryException().apply { value = "Bearer eyJhbGciOi.secret.token phone 9876543210" })
        e.setExtra("member_id", "m_1")
        e.setExtra("display_name", "Rajesh Sharma")
        e.setExtra("crop", "A".repeat(500))
        e.setExtra("blob", "QUJD".repeat(80))
        e.setTag("visit_id", "v_1")
        e.setTag("phone", "9876543210")
        e.contexts.setDevice(Device().apply { name = "Rajesh's phone"; model = "Pixel" })
        e.breadcrumbs = mutableListOf(Breadcrumb("tapped Make member for 9876543210").apply { setData("notes", "likes gold"); setData("member_id", "m_1") })

        val out = SentryScrubber.scrub(e)
        assertNull(out.user)
        assertNull(out.request)
        assertEquals("enrol failed for [phone] owner [email]", out.message!!.formatted)
        assertFalse(out.exceptions!!.single().value!!.contains("9876543210"))
        assertFalse(out.exceptions!!.single().value!!.contains("secret"))
        assertEquals("m_1", out.getExtra("member_id"))
        assertNull(out.getExtra("display_name"))
        assertNull(out.getExtra("crop"))
        assertEquals("[binary]", out.getExtra("blob"))
        assertEquals("v_1", out.getTag("visit_id"))
        assertNull(out.getTag("phone"))
        assertNull(out.contexts.device!!.name)
        assertEquals("Pixel", out.contexts.device!!.model)
        val b = out.breadcrumbs!!.single()
        assertEquals("tapped Make member for [phone]", b.message)
        assertNull(b.getData("notes"))
        assertEquals("m_1", b.getData("member_id"))
    }

    @Test fun keyNames() {
        listOf("phone", "phone_e164", "display_name", "notes", "items_text", "vec_blob", "landmarks", "crop_jpeg_b64", "app_token", "shop_key").forEach {
            assertTrue(it, SentryScrubber.isSensitiveKey(it))
        }
        listOf("member_id", "visit_id", "shop_id", "consent_id", "duration_ms").forEach { assertFalse(it, SentryScrubber.isSensitiveKey(it)) }
    }

    @Test fun disabledWhenDsnEmptyAndConfiguredWithoutPii() {
        assertFalse(SentrySetup.enabled(""))
        assertTrue(SentrySetup.enabled("https://k@o.ingest.sentry.io/1"))
        val o = SentryOptions()
        SentrySetup.configure(o, "https://k@o.ingest.sentry.io/1")
        assertFalse(o.isSendDefaultPii)
        assertTrue(o.release!!.startsWith("remu-app@"))
        val ev = SentryEvent().apply { message = Message().apply { formatted = "x 9876543210" } }
        assertEquals("x [phone]", o.beforeSend!!.execute(ev, io.sentry.Hint())!!.message!!.formatted)
    }
}
