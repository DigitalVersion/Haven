package sh.haven.feature.connections.tinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import sh.haven.core.data.db.entities.ConnectionProfile

class TinPreviewCompanionTest {

    @Test
    fun testTinSessionKeyOf() {
        val profile = ConnectionProfile(
            label = "Robot",
            host = "robot.tailscale.net",
            username = "tin",
            remoteCommand = "unset TMUX; exec tmux new -A -s my-session"
        )
        val key = TinPreviewClient.tinSessionKeyOf(profile)
        assertNotNull(key)
        assertEquals("robot", key?.first)
        assertEquals("my-session", key?.second)

        // Invalid remoteCommand format
        val badProfile = ConnectionProfile(
            label = "Bad",
            host = "robot.tailscale.net",
            username = "tin",
            remoteCommand = "exec bash"
        )
        assertNull(TinPreviewClient.tinSessionKeyOf(badProfile))
    }

    @Test
    fun testGetCardForProfileMatching() {
        val cards = mapOf(
            Pair("robot", "session-1") to TinShellCard(
                host = "robot",
                name = "session-1",
                alive = true,
                snapshotPlain = "hello",
                preview = null,
                running = true,
                waitingAsk = null,
                viaBase = null,
                isProtected = false
            ),
            Pair("myvps", "session-2") to TinShellCard(
                host = "myvps",
                name = "session-2",
                alive = true,
                snapshotPlain = "world",
                preview = null,
                running = true,
                waitingAsk = null,
                viaBase = null,
                isProtected = false
            )
        )

        // Case 1: Old matching rule (shortname matching)
        val state = TinPreviewState(
            cards = cards,
            hubBaseUrl = "https://tin.tailscale.net/api"
        )
        val profile1 = ConnectionProfile(
            label = "Robot Profile",
            host = "robot.tailscale.net",
            username = "tin",
            remoteCommand = "unset TMUX; exec tmux new -A -s session-1"
        )
        val card1 = state.getCardForProfile(profile1)
        assertNotNull(card1)
        assertEquals("session-1", card1?.name)
        assertEquals("robot", card1?.host)

        // Case 2: New matching rule (hub authority == profile.host, session name matches)
        // Profile host is IP but matches hub URL host (mock self-hosted)
        val selfHostedState = TinPreviewState(
            cards = cards,
            hubBaseUrl = "http://myvps/api" // hub authority is "myvps"
        )
        val profile2 = ConnectionProfile(
            label = "My VPS Profile",
            host = "myvps", // matches hub authority
            username = "root",
            remoteCommand = "unset TMUX; exec tmux new -A -s session-2"
        )
        val card2 = selfHostedState.getCardForProfile(profile2)
        assertNotNull(card2)
        assertEquals("session-2", card2?.name)
        assertEquals("myvps", card2?.host)

        // Case 3: DNS mismatched and no authority matching
        val profile3 = ConnectionProfile(
            label = "Mismatched",
            host = "otherhost",
            username = "root",
            remoteCommand = "unset TMUX; exec tmux new -A -s session-2"
        )
        assertNull(state.getCardForProfile(profile3))
    }

    @Test
    fun testExceptionHierarchy() {
        val authEx = TinAuthRequiredException("401")
        val notTinEx = TinNotTinException("not tin")
        val unreachableEx = TinUnreachableException("timeout")

        assertTrue(authEx is Exception)
        assertTrue(notTinEx is Exception)
        assertTrue(unreachableEx is Exception)
    }
}