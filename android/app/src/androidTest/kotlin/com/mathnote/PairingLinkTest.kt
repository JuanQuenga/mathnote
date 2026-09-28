package com.mathnote

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingLinkTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test fun acceptsDesktopQrAndPrivateLanAddresses() {
        for (server in listOf("http://192.168.50.236:8765", "http://10.0.0.2:8765", "http://172.20.0.4:8765")) {
            assertEquals(PairingConfig(server, token), PairingLink.parse(link(server)))
        }
        assertEquals(PairingConfig("https://notes.example.com", token),
            PairingLink.parse(link("https://notes.example.com/")))
    }

    @Test fun rejectsUntrustedOrMalformedLinks() {
        val badServers = listOf("http://example.com:8765", "http://127.0.0.1:8765",
            "http://192.168.1.999:8765", "http://192.168.1.2.evil.com:8765",
            "http://user:pass@192.168.1.2:8765", "http://192.168.1.2:8765/other",
            "http://192.168.1.2:8765?leak=1")
        badServers.forEach { assertNull(PairingLink.parse(link(it))) }
        assertNull(PairingLink.parse(link("http://192.168.1.2:8765", "short")))
        assertNull(PairingLink.parse(Uri.parse("mathnote://pair?server=http%3A%2F%2F192.168.1.2%3A8765&token=$token&token=$token")))
        assertNull(PairingLink.parse(Uri.parse("mathnote://other?server=http%3A%2F%2F192.168.1.2%3A8765&token=$token")))
    }

    @Test fun tabletCameraCanOpenPairingLinkInMathNote() {
        val intent = Intent(Intent.ACTION_VIEW, link("http://192.168.1.2:8765"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val apps = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .queryIntentActivities(intent, 0)
        assertTrue(apps.any { it.activityInfo.name == MainActivity::class.java.name })
    }

    private fun link(server: String, secret: String = token): Uri = Uri.Builder()
        .scheme("mathnote").authority("pair")
        .appendQueryParameter("server", server).appendQueryParameter("token", secret).build()
}
