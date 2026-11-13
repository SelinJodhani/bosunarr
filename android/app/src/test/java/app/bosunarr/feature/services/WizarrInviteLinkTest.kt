package app.bosunarr.feature.services

import app.bosunarr.data.ServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class WizarrInviteLinkTest {
    private fun wizarr(url: String, altUrl: String? = null, inviteUrl: String? = null) =
        ServiceConfig(id = "w", type = "wizarr", name = "Wizarr", url = url, altUrl = altUrl, inviteUrl = inviteUrl)

    @Test fun inviteAddressWinsOverPrivateHttps() {
        val s = wizarr("https://wizarr.home.example", inviteUrl = "https://invite.example.com")
        assertEquals("https://invite.example.com", inviteBase(s))
    }

    @Test fun blankInviteAddressFallsBackToHttps() {
        val s = wizarr("http://192.168.1.10:5690", altUrl = "https://wizarr.example.com", inviteUrl = " ")
        assertEquals("https://wizarr.example.com", inviteBase(s))
    }

    @Test fun noHttpsFallsBackToMainAddress() {
        assertEquals("http://192.168.1.10:5690", inviteBase(wizarr("http://192.168.1.10:5690")))
    }
}
