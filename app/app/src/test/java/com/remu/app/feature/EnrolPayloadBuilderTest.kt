package com.remu.app.feature

import com.remu.app.feature.enrol.BillingForm
import com.remu.app.feature.enrol.EnrolPayloadBuilder
import com.remu.app.feature.enrol.FaceCrop
import com.remu.app.feature.enrol.FaceCropper
import com.remu.app.feature.enrol.FieldError
import com.remu.app.network.RemuJson
import com.remu.app.network.hub.EnrolRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The `POST /enrol` body matches docs/API_CONTRACT.md section 1 (TASK-044, TASK-046 to TASK-048). */
class EnrolPayloadBuilderTest {
    private val form = BillingForm(
        name = "Rajesh Sharma", phone = "98765 43210", billNo = "INV-2026-10-18-0417", amount = "20,000",
        items = "Gold necklace", occasion = "Daughter's engagement", notes = "Asked about matching earrings",
    )
    private fun crop(n: Int = 1000) = FaceCrop(ByteArray(n) { (it % 251).toByte() }, List(5) { listOf(it * 1f, it * 2f) })

    private fun json(req: EnrolRequest): JsonObject = RemuJson.parseToJsonElement(RemuJson.encodeToString(EnrolRequest.serializer(), req)).jsonObject

    @Test fun singleFaceShape() {
        val req = EnrolPayloadBuilder.build(form, listOf(crop()), "s_ravi")
        val o = json(req)
        assertEquals(setOf("staff_id", "phone", "display_name", "bill_no", "amount", "items", "occasion", "notes", "faces"), o.keys)
        assertEquals("s_ravi", o["staff_id"]!!.jsonPrimitive.content)
        assertEquals("+919876543210", o["phone"]!!.jsonPrimitive.content)
        assertEquals(20000.0, o["amount"]!!.jsonPrimitive.content.toDouble(), 0.0)
        val face = o["faces"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("crop_jpeg_b64", "landmarks"), face.keys)
        assertEquals(5, (face["landmarks"] as JsonArray).size)
        assertEquals(2, face["landmarks"]!!.jsonArray[0].jsonArray.size)
        assertEquals(1000, Base64.getDecoder().decode(face["crop_jpeg_b64"]!!.jsonPrimitive.content).size)
    }

    @Test fun householdFieldsOnlyWithTwoOrMoreFaces() {
        val f = form.copy(householdLabel = "Sharma family", payerIndex = 1, adultNames = listOf("Sunita Sharma"), adultPhones = listOf("9123456780"))
        val one = json(EnrolPayloadBuilder.build(f, listOf(crop()), "s_1"))
        assertFalse("household_label" in one.keys)
        assertFalse("payer_index" in one.keys)
        val two = json(EnrolPayloadBuilder.build(f, listOf(crop(), crop()), "s_1"))
        assertEquals("Sharma family", two["household_label"]!!.jsonPrimitive.content)
        assertEquals(1, two["payer_index"]!!.jsonPrimitive.content.toInt())
        val second = two["faces"]!!.jsonArray[1].jsonObject
        assertEquals("Sunita Sharma", second["display_name"]!!.jsonPrimitive.content)
        assertEquals("+919123456780", second["phone"]!!.jsonPrimitive.content)
        assertNull(two["faces"]!!.jsonArray[0].jsonObject["display_name"])
    }

    @Test fun phoneMemberHasNoFaces() {
        val o = json(EnrolPayloadBuilder.build(form, emptyList(), "s_1"))
        assertEquals(0, o["faces"]!!.jsonArray.size)
    }

    @Test fun validation() {
        assertTrue(EnrolPayloadBuilder.validate(form, 1).ok)
        val bad = EnrolPayloadBuilder.validate(BillingForm(phone = "123", amount = "x", billNo = ""), 1)
        assertEquals(FieldError.PHONE_INVALID, bad["phone"])
        assertEquals(FieldError.AMOUNT_INVALID, bad["amount"])
        assertEquals(FieldError.BILL_NO, bad["billNo"])
        assertEquals(FieldError.NAME_EMPTY, bad["name"])
        // Bill only: a name is optional.
        assertNull(EnrolPayloadBuilder.validate(form.copy(name = ""), 0)["name"])
        // Household needs a label.
        assertEquals(FieldError.HOUSEHOLD_LABEL, EnrolPayloadBuilder.validate(form, 2)["householdLabel"])
        assertEquals(FieldError.ADULT_PHONE_INVALID, EnrolPayloadBuilder.validate(form.copy(householdLabel = "S", adultPhones = listOf("12")), 2)["adultPhone1"])
    }

    @Test fun cropLimitIs300Kb() {
        assertEquals(300 * 1024, FaceCropper.MAX_BYTES)
    }
}
