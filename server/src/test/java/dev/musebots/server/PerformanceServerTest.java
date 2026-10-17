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
        // Wide tempo range so whole performances (intro, head, solos, ending) take seconds, not minutes.
        server = PerformanceServer.start(0, freePort(), new ServerLimits(60, 960, 2));
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

    private static String start(int tempo, String ensemble) {
        return "{\"type\":\"start\",\"tempo\":" + tempo + ",\"soloChoruses\":1,\"ensemble\":\"" + ensemble + "\"}";
    }

    private static List<String> sections(Client client) {
        return client.ofType("status").stream().filter(m -> "SECTION".equals(m.path("kind").asText()))
                .map(m -> m.path("message").asText()).toList();
    }

    private static boolean played(Client client, String instrument) {
        return client.ofType("note").stream().anyMatch(n -> instrument.equals(n.path("instrument").asText()));
    }

    @Test
    void streamsANegotiatedDuoToTheBrowser() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"time.sync\",\"t0\":123}", true).join();
            socket.sendText(start(960, "duo"), true).join();

            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");

            assertEquals(1, client.ofType("hello").size());
            assertEquals(123, client.ofType("time.sync").get(0).path("t0").asLong());
            assertEquals("FINISHED", client.ofType("status").stream().reduce((a, b) -> b).orElseThrow().path("kind").asText());

            // The browser is told when each part of the tune begins.
            assertEquals(List.of("The head", "piano solo", "bass solo", "The ending"), sections(client));

            // It sees the negotiation, with the conversation each message belongs to.
            List<JsonNode> talk = client.ofType("conversation");
            assertTrue(talk.stream().anyMatch(m -> "CFP".equals(m.path("performative").asText())
                    && "intro".equals(m.path("conversationId").asText())));
            assertTrue(talk.stream().anyMatch(m -> "take-lead".equals(m.path("conversationId").asText())));
            assertTrue(talk.stream().anyMatch(m -> m.path("from").asText().endsWith("-bass-synchronizer")));
            assertTrue(talk.stream().anyMatch(m -> m.path("from").asText().endsWith("-piano-composer")));

            assertTrue(played(client, "BASS"));
            assertTrue(played(client, "PIANO"));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void streamsTheFullQuartetByDefault() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"tempo\":960}", true).join();
            assertTrue(client.finished.await(60, TimeUnit.SECONDS), "performance did not finish");

            assertEquals("FINISHED", client.ofType("status").stream().reduce((a, b) -> b).orElseThrow().path("kind").asText());
            assertEquals(List.of("The head", "sax solo", "piano solo", "bass solo", "drums solo", "The ending"), sections(client));
            for (String instrument : List.of("SAX", "PIANO", "BASS", "DRUMS")) {
                assertTrue(played(client, instrument), instrument + " never played");
            }
            assertEquals(3, client.ofType("conversation").stream()
                    .filter(m -> "PROPOSE".equals(m.path("performative").asText())).count());
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void stoppingEndsThePerformanceEarly() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText(start(120, "duo"), true).join();
            long deadline = System.currentTimeMillis() + 10_000;
            while (client.ofType("note").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("note").isEmpty());

            socket.sendText("{\"type\":\"stop\"}", true).join();
            Thread.sleep(500);
            int before = client.messages.size();
            Thread.sleep(1_500);
            // Nothing more arrives once the agents are gone (the performance would last for a minute at 120 bpm).
            assertEquals(before, client.messages.size());
            assertFalse(client.finished.getCount() == 0, "the performance carried on to the end");

            // And a new performance can start on the same connection.
            socket.sendText(start(960, "duo"), true).join();
            assertTrue(client.finished.await(60, TimeUnit.SECONDS));
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        }
    }

    @Test
    void rejectsAnUnknownEnsemble() throws Exception {
        Client client = new Client();
        WebSocket socket = connect(client);
        try {
            socket.sendText("{\"type\":\"start\",\"ensemble\":\"big-band\"}", true).join();
            long deadline = System.currentTimeMillis() + 5_000;
            while (client.ofType("error").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(client.ofType("error").isEmpty());
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
