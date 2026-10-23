package cn.shiping.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class MeetingAudioAuthTest {
    @Test public void credentialsAndRolesCannotBeReplayed() throws Exception {
        String key = MeetingAudioAuth.nonce(), challenge = MeetingAudioAuth.nonce() + MeetingAudioAuth.nonce();
        String server = MeetingAudioAuth.proof(key, "server", challenge);
        assertTrue(MeetingAudioAuth.matches(server, MeetingAudioAuth.proof(key, "server", challenge)));
        assertFalse(MeetingAudioAuth.matches(server, MeetingAudioAuth.proof(key, "client", challenge)));
        assertFalse(MeetingAudioAuth.matches(server, MeetingAudioAuth.proof(MeetingAudioAuth.nonce(), "server", challenge)));
        assertFalse(MeetingAudioAuth.matches(server, MeetingAudioAuth.proof(key, "server", challenge.substring(0, 64) + MeetingAudioAuth.nonce())));
    }
    @Test public void malformedCredentialIsRejected() throws Exception {
        try { MeetingAudioAuth.proof("", "client", MeetingAudioAuth.nonce()); fail("Missing key accepted"); }
        catch (IllegalArgumentException expected) { }
        try { MeetingAudioAuth.proof(MeetingAudioAuth.nonce(), "client", "bad"); fail("Invalid nonce accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
