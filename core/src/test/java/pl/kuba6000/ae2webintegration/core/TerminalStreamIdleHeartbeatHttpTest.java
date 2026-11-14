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

/** Idle counterpart of {@link TerminalStreamActiveHeartbeatHttpTest}, separate so both waits run in parallel. */
class TerminalStreamIdleHeartbeatHttpTest extends GridTestScope {

    @Test
    void idleStreamKeepsTheHeartbeatInterval() throws Exception {
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
                long lastHeartbeat = System.nanoTime();
                Event idle = readAnyEvent(connection.input);
                assertEquals("heartbeat", idle.name());
                assertEquals(
                    "{}",
                    idle.data()
                        .toString());
                assertTrue(
                    System.nanoTime() - lastHeartbeat >= SECONDS.toNanos(8),
                    "Idle keepalives retain the heartbeat interval");
            }
        }
    }
}
