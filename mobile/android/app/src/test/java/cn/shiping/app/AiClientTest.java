package cn.shiping.app;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AiClientTest {
    @Test public void retriesOnlyUnsupportedThinkingAndPreservesAuthAndImages() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverTls.sslSocketFactory(), false);
            server.enqueue(new MockResponse().setResponseCode(400).setBody("unsupported enable_thinking"));
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"choices\":[{\"delta\":{\"content\":\"成功\"}}]}\n\ndata: [DONE]\n\n"));
            server.start(java.net.InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
            AiClient client = new AiClient(new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build());
            JSONObject profile = new JSONObject().put("baseURL", server.url("/v1").newBuilder().host("localhost").build().toString()).put("model", "vision")
                .put("apiKey", "secret-test-key").put("headers", "authorization: Bearer override").put("disableThinking", true);
            JSONObject params = new JSONObject().put("messages", new JSONArray("[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,abc\"}}]}]"));
            StringBuilder result = new StringBuilder();
            client.chat("1", profile, params, (kind, value) -> { if (kind.equals("text")) result.append(value); });
            assertEquals("成功", result.toString());
            var first = server.takeRequest(1, TimeUnit.SECONDS);
            var second = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("Bearer override", first.getHeader("Authorization"));
            assertTrue(first.getBody().readUtf8().contains("enable_thinking"));
            String retried = second.getBody().readUtf8();
            assertFalse(retried.contains("enable_thinking"));
            assertTrue(retried.contains("image_url"));
            client.close();
        }
    }
}
