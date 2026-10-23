package cn.shiping.app;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in real-device test. Only measures local PCM; never uploads or saves speech. */
@RunWith(AndroidJUnit4.class)
public class MeetingAudioTest {
    private String helperCommand(String command) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        String apk = instrumentation.getTargetContext().getApplicationInfo().sourceDir;
        assertTrue(apk.matches("/data/app/[A-Za-z0-9_=/+.~-]+/base\\.apk"));
        try (var descriptor = instrumentation.getUiAutomation().executeShellCommand(
                "env CLASSPATH=" + apk + " app_process / cn.shiping.app.MeetingAudioBridge " + command);
             var input = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }

    @Test public void meetingPcmRestartAndRevocation() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("meetingAudio")));
        assertEquals("READY", helperCommand("ping"));
        for (int cycle = 0; cycle < 2; cycle++) {
            try (MeetingAudioClient client = new MeetingAudioClient(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
                client.start();
                assertEquals("Control must work during capture", "READY", helperCommand("ping"));
                byte[] pcm = new byte[3200];
                double sum = 0;
                for (int frame = 0; frame < 50; frame++) {
                    assertEquals(pcm.length, client.read(pcm));
                    for (int i = 0; i < pcm.length; i += 2) {
                        short sample = (short) ((pcm[i] & 255) | pcm[i + 1] << 8);
                        sum += (double) sample * sample;
                    }
                }
                double rms = Math.sqrt(sum / 80000) / 32768;
                android.util.Log.i("ShipingMeetingTest", "cycle=" + cycle + " rms=" + rms);
                assertTrue("Remote test speech must be audible in captured PCM", rms > 0.0005);
            }
            Thread.sleep(500);
        }
        try (MeetingAudioClient client = new MeetingAudioClient(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            client.start();
            assertEquals("STOPPED", helperCommand("stop"));
            try {
                for (int i = 0; i < 100; i++) client.read(new byte[3200]);
                fail("Revoked helper must close the audio connection");
            } catch (java.io.IOException expected) { /* Revocation must reach the consumer. */ }
        }
        try (MeetingAudioClient client = new MeetingAudioClient(InstrumentationRegistry.getInstrumentation().getTargetContext())) {
            try { client.start(); fail("An inactive helper must not appear to capture"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("activate-meeting-audio.ps1")); }
        }
    }
}
