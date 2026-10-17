package dev.musebots.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceServerTest {

    private PerformanceServer server;

    @BeforeAll
    void start() throws IOException {
        server = PerformanceServer.start(0, freePort());
    }

    @AfterAll
    void stop() {
        server.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** A WebSocket client that records every JSON message and signals on a terminal status. */
    private static final class Client implements WebSocket.Listener {
        final List<JsonNode> messages = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode message = Wire.MAPPER.readTree(partial.toString());
                    messages.add(message);
                    if ("status".equals(message.path("type").asText())
                            && List.of("FINISHED", "FAILED").contains(message.path("kind").asText())) {
                        finished.countDown();
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    partial.setLength(0);
                }
            }
            webSocket.request(1);
            return null;
        }

        List<JsonNode> ofType(String type) {
            return messages.stream().filter(m -> type.equals(m.path("type").asText())).toList();
        }
    }

    private WebSocket connect(Client client) {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws"), client).join();
    }

    @Test
    void servesTheWebClient() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Musebots"));
    }

    @Test
    void streamsANegotiatedPerformanceToTheBrowser() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"time.sync\",\"t0\":123}", true).join();
            socket.sendText("{\"type\":\"start\",\"tempo\":200,\"choruses\":1}", true).join();

            assertTrue(client.finished.await(30, TimeUnit.SECONDS), "performance did not finish");

            assertEquals(1, client.ofType("hello").size());
            JsonNode sync = client.ofType("time.sync").get(0);
            assertEquals(123, sync.path("t0").asLong());

            List<String> performatives = client.ofType("conversation").stream()
                    .map(m -> m.path("performative").asText()).toList();
            assertEquals(List.of("INFORM", "CONFIRM", "CFP", "PROPOSE", "ACCEPT-PROPOSAL", "INFORM", "REQUEST"), performatives);

            List<JsonNode> notes = client.ofType("note");
            assertTrue(notes.stream().anyMatch(n -> "BASS".equals(n.path("instrument").asText())));
            assertTrue(notes.stream().anyMatch(n -> "PIANO".equals(n.path("instrument").asText())));
            assertEquals("FINISHED", client.ofType("status").stream()
                    .reduce((a, b) -> b).orElseThrow().path("kind").asText());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void rejectsOutOfRangeTempo() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"tempo\":5000}", true).join();
            long deadline = System.currentTimeMillis() + 5_000;
            while (client.ofType("error").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("error").isEmpty());
            assertTrue(client.ofType("note").isEmpty());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }
}
