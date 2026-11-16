package pl.kuba6000.ae2webintegration.core;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.readAnyEvent;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.row;

import java.net.HttpURLConnection;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Connection;
import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Event;
import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Fixture;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Heartbeats are due every ten seconds of wall-clock time. Each cadence has its own class so parallel test forks
 * overlap the unavoidable waits.
 */
class TerminalStreamActiveHeartbeatHttpTest extends GridTestScope {

    @Test
    void continuousDeltasNeitherPostponeNorMultiplyHeartbeats() throws Exception {
        StableKey iron = TestGridFixtures.key(51);
        try (Fixture fixture = new Fixture(Collections.singletonList(row(iron, "Iron", 1)))) {
            HttpURLConnection request = fixture.connection(fixture.reserve());
            // Heartbeats are due every ten seconds; allow transport and scheduler headroom for this connection.
            request.setReadTimeout(15_000);
            try (Connection connection = new Connection(request)) {
                assertEquals("ready", readAnyEvent(connection.input).name());
                Event initial = readAnyEvent(connection.input);
                assertEquals("heartbeat", initial.name());
                assertEquals(
                    "{}",
                    initial.data()
                        .toString());
                long initialHeartbeat = System.nanoTime();
                for (int quantity = 2; quantity <= 3; quantity++) {
                    fixture.publish(quantity, Collections.singletonList(row(iron, "Iron", quantity)));
                    assertEquals("delta", readAnyEvent(connection.input).name());
                }

                boolean activeHeartbeat = false;
                long deadline = initialHeartbeat + SECONDS.toNanos(15);
                for (int quantity = 4; System.nanoTime() < deadline; quantity++) {
                    fixture.publish(quantity, Collections.singletonList(row(iron, "Iron", quantity)));
                    Event event = readAnyEvent(connection.input);
                    if (event.name()
                        .equals("heartbeat")) {
                        assertEquals(
                            "{}",
                            event.data()
                                .toString());
                        activeHeartbeat = true;
                        break;
                    }
                    assertEquals("delta", event.name());
                }
                assertTrue(activeHeartbeat, "Continuous deltas must not postpone the heartbeat");
                assertTrue(
                    System.nanoTime() - initialHeartbeat >= SECONDS.toNanos(8),
                    "Deltas must not generate extra heartbeats between scheduled keepalives");
            }
        }
    }
}
