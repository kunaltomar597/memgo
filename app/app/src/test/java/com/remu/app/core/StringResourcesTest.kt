package com.remu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TASK-052 and TASK-083: every English key has a Hindi counterpart, and the v1.0 consent texts
 * are byte for byte the ones in docs/BRIEF.md section 6 and docs/DESIGN.md section 7.
 */
class StringResourcesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }

    private data class Entry(val value: String, val translatable: Boolean)

    private fun load(dir: String): Map<String, Entry> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = mutableMapOf<String, Entry>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as Element
                out[el.getAttribute("name")] = Entry(el.textContent, el.getAttribute("translatable") != "false")
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")

    @Test fun everyEnglishStringHasHindi() {
        val en = load("values")
        val hi = load("values-hi")
        val missing = en.filter { it.value.translatable }.keys - hi.keys
        assertTrue("Missing Hindi strings: $missing", missing.isEmpty())
        val extra = hi.keys - en.keys
        assertTrue("Hindi strings with no English key: $extra", extra.isEmpty())
        val notTranslatableInHindi = hi.keys.filter { en[it]?.translatable == false }
        assertTrue("Non-translatable keys must not be in values-hi: $notTranslatableInHindi", notTranslatableInHindi.isEmpty())
    }

    @Test fun hindiStringsAreInDevanagari() {
        val hi = load("values-hi")
        val latinOnly = hi.filter { (_, e) -> e.value.none { it in 'ऀ'..'ॿ' } }.keys
        // Brand and product words stay as spoken: Remu, WhatsApp, PIN, QR, CSV, PDF.
        val allowed = setOf<String>()
        assertTrue("Hindi strings without Devanagari: ${latinOnly - allowed}", (latinOnly - allowed).isEmpty())
    }

    @Test fun consentTextsAreVerbatim() {
        val en = load("values")
        assertEquals("आप अब हमारे मेंबर हो गए। अगली बार आते ही हम आपको पहचान के वेलकम करेंगे, और 5% ऑफ मिलेगा।", unescape(en["script_line_hi"]!!.value))
        assertEquals("You're a member now. Next time, we'll recognise you as you walk in and welcome you, and you get 5% off.", unescape(en["script_line_en"]!!.value))
        assertEquals("मेंबर्स को 5% ऑफ। न कार्ड, न ऐप।", unescape(en["standee_hi"]!!.value))
        assertEquals("Members get 5% off. No card, no app.", unescape(en["standee_en"]!!.value))
        assertEquals("hi-IN-v1.0", en["script_version"]!!.value)
        assertEquals("wa-v1.0", en["notice_version"]!!.value)
    }

    @Test fun designStringsAreVerbatim() {
        val en = load("values")
        val hi = load("values-hi")
        fun e(k: String) = unescape(en[k]!!.value)
        fun h(k: String) = unescape(hi[k]!!.value)
        assertEquals("Member added. Notice pending on WhatsApp.", e("enrol_success"))
        assertEquals("मेंबर बन गए। WhatsApp पर सूचना बाकी।", h("enrol_success"))
        assertEquals("Make member", e("make_member"))
        assertEquals("मेंबर बनाएँ", h("make_member"))
        assertEquals("Under 18", e("face_under_18"))
        assertEquals("18 से कम", h("face_under_18"))
        assertEquals("One face was refused (under 18). The others are members.", e("enrol_partial_refused"))
        assertEquals("Skip face, save bill only", e("skip_face"))
        assertEquals("Saved without face membership", e("enrol_phone_member_saved"))
        assertEquals("Removed. Face data is deleted within 1 hour.", e("removed_done"))
        assertEquals("Remove %1\$s? Face data is deleted within one hour. The consent record stays, as proof.", e("remove_confirm"))
        assertEquals("%1\$s को हटाएँ? चेहरे का डेटा एक घंटे में मिट जाएगा। सहमति का रिकॉर्ड सबूत के तौर पर रहेगा।", h("remove_confirm"))
        assertEquals("No internet. Billing continues. Notices will go when internet returns.", e("banner_no_internet"))
        assertEquals("इंटरनेट नहीं है। बिलिंग चालू है। इंटरनेट आने पर सूचना जाएगी।", h("banner_no_internet"))
        assertEquals("Hub not reachable. Make member is paused. Billing continues.", e("banner_hub_unreachable"))
        assertEquals("हब नहीं मिल रहा। Make member रुका है। बिलिंग चालू है।", h("banner_hub_unreachable"))
        assertEquals("Hub did not reply. Check the hub light and the WiFi, then try again.", e("error_hub_no_reply"))
        assertEquals("No members yet. Make the first one at billing.", e("members_empty"))
        assertEquals("No visits yet today.", e("alerts_empty"))
        assertEquals("Greeted", e("greeted"))
        assertEquals("Not them", e("not_them"))
        assertEquals("स्वागत किया", h("greeted"))
        assertEquals("ये नहीं", h("not_them"))
        assertEquals("Clips play only on shop WiFi", e("clips_lan_only"))
        assertEquals("Notice pending", e("status_pending")); assertEquals("सूचना बाकी", h("status_pending"))
        assertEquals("Active", e("status_active")); assertEquals("चालू", h("status_active"))
        assertEquals("Removed", e("status_withdrawn")); assertEquals("हटाया गया", h("status_withdrawn"))
        assertEquals("Expired", e("status_expired")); assertEquals("खत्म", h("status_expired"))
        assertEquals("5% off next visit", e("discount_chip"))
        assertEquals("Last visit %1\$s", e("alert_last_visit"))
        assertEquals("Note: %1\$s", e("alert_note"))
    }
}
