package org.jdownloader.remote

import org.junit.Assert.*
import org.junit.Test
import org.jdownloader.remote.cnl.CnlForm
import org.jdownloader.remote.presentation.CaptchaDocuments
import kotlinx.serialization.json.*

class CnlFormTest {
    @Test fun preservesEncodedBase64AndPassword() {
        val fields = CnlForm.parse("crypted=ab%2Bcd%2Fef%3D&jk=function+f%28%29%7Breturn+%27ab%27%7D&passwords=p%26%3D%C3%A4&package=My+files")
        assertEquals("ab+cd/ef=", fields["crypted"])
        assertEquals("p&=ä", fields["passwords"])
        assertEquals("My files", fields["package"])
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMalformedUrlEncoding() { CnlForm.parse("urls=%zz") }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedBody() { CnlForm.parse("x".repeat(CnlForm.MAX_BODY_BYTES + 1)) }
    @Test fun tokenWidgetUsesSiteOriginAndEscapesScriptInput() {
        val page = CaptchaDocuments.token("recaptchav2", buildJsonObject { put("siteKey", "key</script><script>alert(1)</script>"); put("contextUrl", "https://example.org/") })!!
        assertEquals("https://example.org/", page.baseUrl)
        assertFalse(page.html.contains("key</script>"))
        assertTrue(page.html.contains("grecaptcha.render"))
    }
    @Test fun rejectTokenContextWithCredentialsOrFileScheme() {
        assertNull(CaptchaDocuments.token("hcaptcha", buildJsonObject { put("siteKey", "key"); put("siteUrl", "file:///etc/passwd") }))
        assertNull(CaptchaDocuments.token("hcaptcha", buildJsonObject { put("siteKey", "key"); put("siteUrl", "https://a:b@example.org") }))
    }
    @Test fun parsesStringEncodedTokenMetadata() {
        val json = JsonPrimitive("{\"siteKey\":\"abc\",\"contextUrl\":\"https://example.org\"}")
        assertNotNull(CaptchaDocuments.token("hcaptcha", json))
    }
}
