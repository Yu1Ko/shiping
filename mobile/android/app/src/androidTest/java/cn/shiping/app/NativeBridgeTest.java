package cn.shiping.app;

import android.webkit.WebView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs the actual packaged WebView, bridge, Keystore and HTTPS transport on a device. */
@RunWith(AndroidJUnit4.class)
public class NativeBridgeTest {
    private String evaluate(ActivityScenario<MainActivity> scenario, String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        scenario.onActivity(activity -> {
            try {
                Field field = MainActivity.class.getDeclaredField("web");
                field.setAccessible(true);
                ((WebView) field.get(activity)).evaluateJavascript(script, value -> { result.set(value); done.countDown(); });
            } catch (Exception e) { throw new AssertionError(e); }
        });
        assertTrue("WebView callback timed out", done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private void waitUntil(ActivityScenario<MainActivity> scenario, String condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if ("true".equals(evaluate(scenario, condition))) return;
            Thread.sleep(100);
        }
        fail("Condition not met: " + condition);
    }

    @Test public void coldStartEncryptedStorageAndRealStreamingBridge() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Stop screen capture before running the device test", CaptureService.active);
        SecureStore store = new SecureStore(context);
        String original = store.read();
        java.io.File pendingCapture = new java.io.File(context.getFilesDir(), "capture.jpg");
        java.io.File savedCapture = new java.io.File(context.getFilesDir(), "capture.test-backup.jpg");
        assertFalse("A previous test screenshot backup needs recovery", savedCapture.exists());
        boolean hadCapture = pendingCapture.exists();
        if (hadCapture) assertTrue("Cannot preserve pending screenshot", pendingCapture.renameTo(savedCapture));
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"本地检查\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"真机流式验证成功\"}}]}\n\n" +
                "data: [DONE]\n\n"));
            server.start(java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
            JSONObject state = new JSONObject("{\"version\":1,\"settings\":{\"profiles\":[{\"id\":\"device-test\",\"name\":\"本地测试\",\"baseURL\":\"\",\"apiKey\":\"device-test-key\",\"headers\":\"\",\"model\":\"test-vision\",\"disableThinking\":false}],\"screenshotProfile\":\"device-test\",\"conversationProfile\":\"device-test\",\"scene\":\"general\",\"conversationScene\":\"interview\",\"customPrompt\":\"\",\"fontSize\":16},\"sessions\":[],\"knowledge\":[]}");
            state.getJSONObject("settings").getJSONArray("profiles").getJSONObject(0).put("baseURL", "https://localhost:" + server.getPort() + "/v1");
            store.write(state.toString());
            assertEquals(state.toString(), store.read());
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                scenario.onActivity(activity -> {
                    try {
                        // The test trusts only its ephemeral localhost certificate. Production TLS is untouched.
                        Field field = MainActivity.class.getDeclaredField("ai");
                        field.setAccessible(true);
                        ((AiClient) field.get(activity)).close();
                        field.set(activity, new AiClient(new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build()));
                    } catch (Exception e) { throw new AssertionError(e); }
                });
                waitUntil(scenario, "!!document.querySelector('[aria-label=\"输入问题\"]')");
                evaluate(scenario, "(()=>{const t=document.querySelector('textarea'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(t,'本地真机问题'); t.dispatchEvent(new Event('input',{bubbles:true}));})()");
                waitUntil(scenario, "!document.querySelector('[aria-label=\"发送问题\"]').disabled");
                evaluate(scenario, "document.querySelector('[aria-label=\"发送问题\"]').click()");
                waitUntil(scenario, "document.body.innerText.includes('真机流式验证成功') && !document.querySelector('[aria-label=\"停止生成\"]')");
                var request = server.takeRequest(5, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("Bearer device-test-key", request.getHeader("Authorization"));
                assertTrue(request.getBody().readUtf8().contains("本地真机问题"));
                waitUntil(scenario, "document.querySelectorAll('.message.assistant').length===1");
                // Wait for the debounced final history write, then verify encrypted persistence.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!store.read().contains("真机流式验证成功") && System.nanoTime() < deadline) Thread.sleep(100);
                assertTrue(store.read().contains("真机流式验证成功"));
            }
        } finally {
            try { store.write(original); }
            finally {
                if (hadCapture) assertTrue("Cannot restore pending screenshot", savedCapture.renameTo(pendingCapture));
            }
        }
    }
}
