package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.readEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Connection;
import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Fixture;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;

/** Disconnects are noticed by the next heartbeat write, so this wait lives in its own class for parallel forks. */
class TerminalStreamDisconnectedViewerHttpTest extends GridTestScope {

    @Test
    void disconnectedIdleViewersReleaseAdmissionForAnotherSnapshot() throws Exception {
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            for (int client = 0; client < 6; client++) {
                try (Socket socket = new Socket()) {
                    socket.setSoTimeout(3000);
                    socket.connect(fixture.server.getAddress());
                    socket.getOutputStream()
                        .write(
                            ("GET /events?" + fixture.reserve() + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                                .getBytes(UTF_8));
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
                    assertEquals("ready", readEvent(input).name());
                }
            }
            TerminalRegistry.Bootstrap replacement = null;
            long deadline = System.nanoTime() + SECONDS.toNanos(25);
            while (replacement == null && System.nanoTime() < deadline) {
                fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
                replacement = fixture.state
                    .reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis());
                if (replacement == null) Thread.sleep(100);
            }
            assertNotNull(replacement, "Disconnected viewers must release admission without storage changes");
            try (Connection connection = fixture.open(replacement.subscription())) {
                assertEquals("ready", readEvent(connection.input).name());
            }
        }
    }
}
