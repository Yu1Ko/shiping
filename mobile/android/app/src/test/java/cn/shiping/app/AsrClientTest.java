package cn.shiping.app;

import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.tls.*;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class AsrClientTest {
    @Test public void realWssHandshakePcmSentenceDedupAndFinalFlush() throws Exception {
        HeldCertificate cert = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(cert).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate()).build();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        OkHttpClient http = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build();
        BlockingQueue<JSONObject> commands = new LinkedBlockingQueue<>();
        BlockingQueue<ByteString> audio = new LinkedBlockingQueue<>();
        BlockingQueue<String> sentences = new LinkedBlockingQueue<>();
        AtomicReference<WebSocket> peer = new AtomicReference<>();
        CountDownLatch ready = new CountDownLatch(1), ended = new CountDownLatch(1);
        AtomicInteger endCount = new AtomicInteger();
        AtomicReference<String> failure = new AtomicReference<>();
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) { peer.set(ws); }
                @Override public void onMessage(WebSocket ws, String text) { try { commands.add(new JSONObject(text)); } catch (Exception e) { throw new AssertionError(e); } }
                @Override public void onMessage(WebSocket ws, ByteString bytes) { audio.add(bytes); }
            }));
            server.start(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}), 0);
            AsrClient client = new AsrClient(http, scheduler, new AsrClient.Listener() {
                public void ready() { ready.countDown(); }
                public void sentence(String text, boolean end) { sentences.add(text + ":" + end); }
                public void ended(String error, boolean retryable) { failure.set(error); endCount.incrementAndGet(); ended.countDown(); }
            });
            client.start(InterviewEngine.json("endpoint", "wss://localhost:" + server.getPort() + "/asr", "apiKey", "local-only", "model", "fun-asr-realtime", "silenceMs", 800));
            JSONObject run = commands.poll(5, TimeUnit.SECONDS); assertNotNull(run);
            assertEquals("run-task", run.getJSONObject("header").getString("action"));
            assertEquals(16000, run.getJSONObject("payload").getJSONObject("parameters").getInt("sample_rate"));
            assertEquals("Bearer local-only", server.takeRequest().getHeader("Authorization"));
            assertFalse(client.audio(new byte[3200], 3200));
            String task = run.getJSONObject("header").getString("task_id");
            peer.get().send(event(task, "task-started", new JSONObject()));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(client.audio(new byte[]{1,2,3,4}, 4));
            assertArrayEquals(new byte[]{1,2,3,4}, audio.poll(5, TimeUnit.SECONDS).toByteArray());
            peer.get().send(sentence(task, 0, "请解释", false));
            assertEquals("请解释:false", sentences.poll(5, TimeUnit.SECONDS));
            assertTrue(client.finish());
            assertEquals("finish-task", commands.poll(5, TimeUnit.SECONDS).getJSONObject("header").getString("action"));
            assertFalse(client.audio(new byte[20], 20));
            peer.get().send(sentence(task, 0, "请解释线程池", true));
            peer.get().send(sentence(task, 0, "重复结果", true));
            peer.get().send(event(task, "result-generated", InterviewEngine.json("output", InterviewEngine.json("sentence", InterviewEngine.json("heartbeat", true)))));
            peer.get().send(event(task, "task-finished", new JSONObject()));
            assertEquals("请解释线程池:true", sentences.poll(5, TimeUnit.SECONDS));
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            assertNull(failure.get()); assertTrue(sentences.isEmpty());
            assertFalse("Stopping an ended connection must not wait for another callback", client.finish());
            client.cancel(); assertEquals(1, endCount.get());
        } finally { scheduler.shutdownNow(); http.dispatcher().executorService().shutdownNow(); http.connectionPool().evictAll(); }
    }
    private static String event(String task, String name, JSONObject payload) {
        return InterviewEngine.json("header", InterviewEngine.json("task_id", task, "event", name), "payload", payload).toString();
    }
    private static String sentence(String task, int id, String text, boolean end) {
        return event(task, "result-generated", InterviewEngine.json("output", InterviewEngine.json("sentence", InterviewEngine.json("sentence_id", id, "text", text, "sentence_end", end))));
    }
    @Test public void rejectsInsecureTransportBeforeAnyNetworkRequest() throws Exception {
        try { AsrClient.validate(InterviewEngine.json("endpoint", "ws://localhost/asr", "apiKey", "test", "model", "test")); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("WSS")); }
    }
}
