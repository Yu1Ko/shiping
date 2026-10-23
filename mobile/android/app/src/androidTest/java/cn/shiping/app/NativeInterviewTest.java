package cn.shiping.app;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeInterviewTest {
    @Test public void localPdfDocxAndChineseEncodings() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);
        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        try (var document = new com.tom_roush.pdfbox.pdmodel.PDDocument()) {
            var page = new com.tom_roush.pdfbox.pdmodel.PDPage(); document.addPage(page);
            try (var stream = new com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12); stream.newLineAtOffset(30, 700); stream.showText("Orion Java interview"); stream.endText();
            }
            document.save(pdf);
        }
        assertTrue(DocumentImport.read(context, new java.io.ByteArrayInputStream(pdf.toByteArray())).contains("Orion Java interview"));
        java.io.ByteArrayOutputStream docx = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(docx)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>项目经历：线程池</w:t></w:r></w:p></w:body></w:document>".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        assertEquals("项目经历：线程池", DocumentImport.read(context, new java.io.ByteArrayInputStream(docx.toByteArray())));
        assertEquals("中文经历", DocumentImport.read(context, new java.io.ByteArrayInputStream("中文经历".getBytes("GB18030"))));
        assertEquals("中文经历", DocumentImport.read(context, new java.io.ByteArrayInputStream("中文经历".getBytes("UTF-16"))));
    }
    private Object command(InterviewRuntime runtime, String action, JSONObject args) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Object> value = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        runtime.command(action, args, (v, e) -> { value.set(v); failure.set(e); done.countDown(); });
        assertTrue(done.await(10, TimeUnit.SECONDS)); if (failure.get() != null) throw failure.get(); return value.get();
    }
    private void dispose(InterviewRuntime runtime) throws Exception {
        command(runtime, "stopHints", new JSONObject());
        CountDownLatch flushed = new CountDownLatch(1); runtime.serial.schedule(flushed::countDown, 1300, TimeUnit.MILLISECONDS); assertTrue(flushed.await(5, TimeUnit.SECONDS));
        for (String name : new String[]{"network", "control", "serial"}) { Field field = InterviewRuntime.class.getDeclaredField(name); field.setAccessible(true); ((ExecutorService) field.get(runtime)).shutdownNow(); }
        Field singleton = InterviewRuntime.class.getDeclaredField("instance"); singleton.setAccessible(true); singleton.set(null, null);
    }
    private Object overlayField(String name) throws Exception {
        Field instance = InterviewOverlay.class.getDeclaredField("visible"); instance.setAccessible(true);
        Object overlay = instance.get(null); assertNotNull("Interview overlay must be visible", overlay);
        Field field = InterviewOverlay.class.getDeclaredField(name); field.setAccessible(true); return field.get(overlay);
    }
    private void clickScreenshot() throws Exception {
        android.widget.Button button = (android.widget.Button) overlayField("captureButton");
        assertEquals("截图", button.getText().toString());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(button::performClick);
    }
    private JSONObject lastCard(InterviewRuntime runtime) throws Exception {
        JSONArray cards = ((JSONObject) command(runtime, "snapshot", new JSONObject())).getJSONObject("session").getJSONArray("hints");
        return cards.length() == 0 ? new JSONObject() : cards.getJSONObject(cards.length() - 1);
    }
    private void awaitCard(InterviewRuntime runtime, String status, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!lastCard(runtime).optString("status").equals(status) && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(status, lastCard(runtime).optString("status"));
    }
    private void deviceShell(String command) throws Exception {
        try (var descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             var input = new java.io.FileInputStream(descriptor.getFileDescriptor())) { input.readAllBytes(); }
    }
    private boolean clickSystemText(String text, int seconds) throws Exception {
        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            var root = automation.getRootInActiveWindow();
            if (root != null) for (var node : root.findAccessibilityNodeInfosByText(text)) {
                if (!text.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                Thread.sleep(700);
                deviceShell("input tap " + bounds.centerX() + " " + bounds.centerY()); Thread.sleep(500); return true;
            }
            Thread.sleep(150);
        } while (System.nanoTime() < deadline);
        return false;
    }
    private void allowProjection() throws Exception {
        if (clickSystemText("共享一个应用", 3)) { Thread.sleep(300); assertTrue(clickSystemText("共享整个屏幕", 5)); }
        Thread.sleep(300); assertTrue("Whole-screen consent must be accepted", clickSystemText("共享屏幕", 8));
    }
    /** Opt-in: exercises visible Android consent UI on the connected, unlocked test phone. */
    @Test public void overlayScreenshotUsesActualDisplayAndLocalVision() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("overlayCapture")));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        boolean playback = "playback".equals(InstrumentationRegistry.getArguments().getString("captureSource"));
        assertFalse(ListeningService.active); assertFalse(CaptureService.active);
        Field singleton = InterviewRuntime.class.getDeclaredField("instance"); singleton.setAccessible(true);
        InterviewRuntime previous = (InterviewRuntime) singleton.get(null); if (previous != null) dispose(previous);
        SecureStore settings = new SecureStore(context), history = new SecureStore(context, "interviews.enc");
        String oldSettings = settings.read(), oldHistory = history.read();
        InterviewRuntime runtime = null; AiClient testAi = null;
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            for (int i = 0; i < 2; i++) server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"截图链路验证通过：二分查找每次将搜索范围减半，时间复杂度 O(log n)。\"}}]}\n\ndata: [DONE]\n\n"));
            server.start(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}), 0);
            JSONObject config = new JSONObject(oldSettings);
            JSONObject options = config.getJSONObject("settings");
            options.put("profiles", new JSONArray().put(InterviewEngine.json("id", "local-vision", "baseURL", "https://localhost:" + server.getPort() + "/v1", "apiKey", "local-only", "model", "local-vision", "headers", "")));
            options.put("screenshotProfile", "local-vision"); options.put("conversationProfile", "must-not-use");
            config.put("knowledge", new JSONArray()); settings.write(config.toString()); history.write("null");
            runtime = InterviewRuntime.get(context); command(runtime, "snapshot", new JSONObject());
            Field ai = InterviewRuntime.class.getDeclaredField("ai"); ai.setAccessible(true); ((AiClient) ai.get(runtime)).close();
            testAi = new AiClient(new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build()); ai.set(runtime, testAi);
            {
                deviceShell("am start -W -n cn.shiping.app/.MainActivity");
                if (playback) {
                    instrumentation.runOnMainSync(() -> {
                        try {
                            MainActivity activity = (MainActivity) androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).iterator().next();
                            var call = MainActivity.class.getDeclaredMethod("uiCall", String.class, String.class, JSONObject.class); call.setAccessible(true);
                            call.invoke(activity, "local-audio-test", "startListening", InterviewEngine.json("source", "playback", "purpose", "interview", "diagnostic", true));
                        } catch (Exception e) { throw new AssertionError(e); }
                    });
                    allowProjection();
                } else instrumentation.runOnMainSync(() -> context.startForegroundService(new android.content.Intent(context, ListeningService.class).putExtra("source", "microphone").putExtra("purpose", "interview").putExtra("diagnostic", true)));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                while (!ListeningService.active && System.nanoTime() < deadline) Thread.sleep(100);
                assertTrue(ListeningService.active);
                deviceShell("am start -W -n cn.shiping.app.test/cn.shiping.app.AudioFixtureActivity");
                Thread.sleep(1500);
                if (!playback) {
                    clickScreenshot(); android.util.Log.i("ShipingOverlayTest", "CANCEL_FIRST_CONSENT");
                    assertFalse(((android.widget.Button) overlayField("captureButton")).isEnabled());
                    assertTrue("Screenshot consent must appear", clickSystemText("取消", 10));
                    awaitCard(runtime, "error", 30); assertTrue("Canceling screenshot must preserve microphone capture", ListeningService.active);
                    assertEquals(0, server.getRequestCount());
                    Thread.sleep(500); clickScreenshot(); android.util.Log.i("ShipingOverlayTest", "ACCEPT_SECOND_CONSENT");
                    allowProjection();
                } else clickScreenshot();
                awaitCard(runtime, "done", 30);
                assertTrue(ListeningService.active); assertTrue(CaptureService.active);
                for (int i = 0; i < 2; i++) {
                    if (i == 1) { Thread.sleep(500); clickScreenshot(); awaitCard(runtime, "done", 10); }
                    RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(request);
                    JSONObject body = new JSONObject(request.getBody().readUtf8()); assertEquals("local-vision", body.getString("model"));
                    JSONArray messages = body.getJSONArray("messages");
                    String data = messages.getJSONObject(messages.length() - 1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url");
                    byte[] bytes = android.util.Base64.decode(data.substring(data.indexOf(',') + 1), android.util.Base64.DEFAULT);
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    assertNotNull(bitmap); assertTrue(bitmap.getHeight() <= 1600); assertTrue(bitmap.getWidth() > 300);
                    // The floating panel occupies this point; the fixture's pale green background must be captured instead.
                    int pixel = bitmap.getPixel(bitmap.getWidth() / 8, bitmap.getHeight() * 2 / 5);
                    try (var out = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), "overlay-capture-" + i + ".jpg"))) { out.write(bytes); }
                    assertTrue("Screenshot must exclude the dark overlay and show the test screen; pixel=" + Integer.toHexString(pixel), android.graphics.Color.green(pixel) > 240 && android.graphics.Color.red(pixel) > 225 && android.graphics.Color.green(pixel) - android.graphics.Color.red(pixel) > 8);
                    bitmap.recycle();
                }
                Thread.sleep(500);
                assertTrue(((android.widget.TextView) overlayField("answer")).getText().toString().contains("截图链路验证通过"));
                assertEquals(android.view.View.VISIBLE, ((android.view.View) overlayField("panel")).getVisibility());
                assertTrue(((String) command(runtime, "export", new JSONObject())).contains("O(log n)"));
                assertFalse("The ordinary screenshot confirmation queue must remain untouched", new java.io.File(context.getFilesDir(), "capture.jpg").exists());
                android.util.Log.i("ShipingOverlayTest", "CAPTURE_VERIFIED");
                android.graphics.Bitmap evidence = instrumentation.getUiAutomation().takeScreenshot();
                try (var out = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), "overlay-answer.png"))) { evidence.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                evidence.recycle();
                context.stopService(new android.content.Intent(context, ListeningService.class));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((ListeningService.active || CaptureService.active) && System.nanoTime() < deadline) Thread.sleep(100);
                assertFalse("Stopping listening releases its dedicated capture service", CaptureService.active);
                assertFalse(ProjectionSession.available());
                command(runtime, "new", new JSONObject());
                JSONObject archived = ((JSONObject) command(runtime, "snapshot", new JSONObject())).getJSONArray("archives").getJSONObject(0);
                assertEquals(0, archived.getJSONArray("utterances").length());
                assertTrue(archived.toString().contains("O(log n)")); assertFalse(archived.toString().contains("base64"));
                Thread.sleep(2000);
                deviceShell("am start -W -n cn.shiping.app/.MainActivity");
            }
        } finally {
            context.stopService(new android.content.Intent(context, ListeningService.class)); context.stopService(new android.content.Intent(context, CaptureService.class));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((ListeningService.active || CaptureService.active) && System.nanoTime() < deadline) Thread.sleep(100);
            if (runtime != null) dispose(runtime); if (testAi != null) testAi.close();
            settings.write(oldSettings); history.write(oldHistory);
        }
        assertFalse(ListeningService.active); assertFalse(CaptureService.active); assertFalse(ProjectionSession.available());
    }
    @Test public void firstLaunchPersistsAndNativeHintsUseRealHttps() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Stop audio capture before running device tests", ListeningService.active);
        assertFalse("Stop screen capture before running device tests", CaptureService.active);
        Field singleton = InterviewRuntime.class.getDeclaredField("instance"); singleton.setAccessible(true);
        InterviewRuntime previous = (InterviewRuntime) singleton.get(null);
        if (previous != null) dispose(previous);
        SecureStore settings = new SecureStore(context), history = new SecureStore(context, "interviews.enc");
        String oldSettings = settings.read(), oldHistory = history.read();
        InterviewRuntime runtime = null;
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        AiClient testAi = null;
        AsrClient asr = null;
        ScheduledExecutorService asrScheduler = Executors.newSingleThreadScheduledExecutor();
        OkHttpClient asrHttp = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build();
        try (MockWebServer server = new MockWebServer(); MockWebServer speech = new MockWebServer()) {
            settings.write("null"); history.write("null");
            runtime = InterviewRuntime.get(context);
            JSONObject fresh = (JSONObject) command(runtime, "snapshot", new JSONObject());
            assertEquals("", fresh.getString("storageError"));
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"核对本地资料\",\"content\":\"先说明线程池的核心线程和任务队列。\"}}]}\n\ndata: [DONE]\n\n"));
            server.start(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}), 0);
            JSONObject profile = InterviewEngine.json("id", "test", "baseURL", "https://localhost:" + server.getPort() + "/v1", "apiKey", "local-only", "model", "test-model", "headers", "");
            settings.write(InterviewEngine.json("settings", InterviewEngine.json("profiles", new JSONArray().put(profile), "conversationProfile", "test", "interview", InterviewEngine.json("automatic", true, "prompt", "技术面试提示")),
                "knowledge", new JSONArray().put(InterviewEngine.json("enabled", true, "forInterview", true, "title", "经历", "text", "本地测试项目 Orion"))).toString());
            Field field = InterviewRuntime.class.getDeclaredField("ai"); field.setAccessible(true); ((AiClient) field.get(runtime)).close();
            testAi = new AiClient(new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build()); field.set(runtime, testAi);
            speech.useHttps(serverTls.sslSocketFactory(), false);
            AtomicReference<String> task = new AtomicReference<>(); CountDownLatch ready = new CountDownLatch(1), finished = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger received = new java.util.concurrent.atomic.AtomicInteger();
            speech.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
                @Override public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject header = new JSONObject(text).getJSONObject("header"); task.set(header.getString("task_id"));
                        socket.send(InterviewEngine.json("header", InterviewEngine.json("task_id", task.get(), "event", header.getString("action").equals("run-task") ? "task-started" : "task-finished"), "payload", new JSONObject()).toString());
                    } catch (Exception e) { throw new AssertionError(e); }
                }
                @Override public void onMessage(WebSocket socket, okio.ByteString bytes) {
                    received.addAndGet(bytes.size());
                    for (boolean end : new boolean[]{false, true}) socket.send(InterviewEngine.json("header", InterviewEngine.json("task_id", task.get(), "event", "result-generated"), "payload", InterviewEngine.json("output", InterviewEngine.json("sentence", InterviewEngine.json("sentence_id", 0, "text", "请解释线程池", "sentence_end", end)))).toString());
                }
            }));
            speech.start(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}), 0);
            InterviewRuntime owner = runtime;
            asr = new AsrClient(asrHttp, asrScheduler, new AsrClient.Listener() {
                public void ready() { ready.countDown(); }
                public void sentence(String text, boolean end) { owner.sentence(text, end, "interview"); }
                public void ended(String error, boolean retryable) { finished.countDown(); }
            });
            asr.start(InterviewEngine.json("endpoint", "wss://localhost:" + speech.getPort() + "/asr", "apiKey", "local-only", "model", "test-asr"));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); assertTrue(asr.audio(new byte[3200], 3200));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            JSONObject card = null;
            while (System.nanoTime() < deadline) {
                JSONArray cards = ((JSONObject) command(runtime, "snapshot", new JSONObject())).getJSONObject("session").getJSONArray("hints");
                if (cards.length() > 0) { card = cards.getJSONObject(0); if ("done".equals(card.optString("status"))) break; }
                Thread.sleep(100);
            }
            assertNotNull(card); assertEquals("done", card.getString("status")); assertTrue(card.getString("text").contains("核心线程")); assertTrue(card.getString("reasoning").contains("本地资料"));
            assertEquals(3200, received.get()); asr.finish(); assertTrue(finished.await(5, TimeUnit.SECONDS)); asr.cancel();
            String body = server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8(); assertTrue(body.contains("Orion")); assertTrue(body.contains("请解释线程池"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!history.read().contains("核心线程") && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue("First-launch conversation must be persisted", history.read().contains("核心线程"));
            assertTrue(((String) command(runtime, "export", new JSONObject())).contains("核心线程"));
        } finally {
            if (asr != null) asr.cancel();
            asrScheduler.shutdownNow(); asrHttp.dispatcher().executorService().shutdownNow(); asrHttp.connectionPool().evictAll();
            if (runtime != null) dispose(runtime);
            if (testAi != null) testAi.close();
            settings.write(oldSettings); history.write(oldHistory);
        }
    }
}
