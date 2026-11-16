package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.readEvent;
import static pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.row;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.TerminalStreamFixtures.Fixture;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;

/** Stalled writers are cancelled by the write timeout, so this wait lives in its own class for parallel forks. */
class TerminalStreamStalledReaderHttpTest extends GridTestScope {

    @Test
    void stalledReadersAreCancelledAndWriterCapacityBecomesUsableAgain() throws Exception {
        List<Socket> stalled = new ArrayList<>();
        try (Fixture fixture = new Fixture(Collections.emptyList())) {
            try {
                for (int client = 0; client < 6; client++) {
                    Socket socket = new Socket();
                    socket.setReceiveBufferSize(1024);
                    socket.setSoTimeout(3000);
                    socket.connect(fixture.server.getAddress());
                    stalled.add(socket);
                    socket.getOutputStream()
                        .write(
                            ("GET /events?" + fixture.reserve() + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                                .getBytes(UTF_8));
                    socket.getOutputStream()
                        .flush();
                    int matched = 0;
                    byte[] terminator = "\r\n\r\n".getBytes(UTF_8);
                    while (matched < terminator.length) {
                        int value = socket.getInputStream()
                            .read();
                        assertTrue(value >= 0);
                        matched = value == terminator[matched] ? matched + 1 : 0;
                    }
                }
                char[] label = new char[900_000];
                Arrays.fill(label, 'x');
                String firstLabel = new String(label);
                label[0] = 'y';
                String secondLabel = new String(label);
                long revision = 2;
                long deadline = System.nanoTime() + SECONDS.toNanos(18);
                boolean recovered = false;
                while (System.nanoTime() < deadline && !recovered) {
                    // A single event can fit entirely in an OS send buffer without blocking a writer.
                    // Keep changing definitions below the per-event limit until unread connections exert backpressure.
                    String name = (revision & 1) == 0 ? firstLabel : secondLabel;
                    fixture.publish(revision++, Collections.singletonList(row(TestGridFixtures.key(99), name, 1)));
                    fixture.state.activate(fixture.grid, 5, System.currentTimeMillis());
                    TerminalRegistry.Bootstrap reservation = fixture.state
                        .reserve(fixture.grid, fixture.session, false, null, null, System.currentTimeMillis());
                    if (reservation == null) {
                        Thread.sleep(100);
                        continue;
                    }
                    HttpURLConnection probe = fixture.connection(reservation.subscription());
                    try {
                        int status = probe.getResponseCode();
                        if (status == 200) {
                            try (BufferedReader input = new BufferedReader(
                                new InputStreamReader(probe.getInputStream(), UTF_8))) {
                                assertEquals("ready", readEvent(input).name());
                                recovered = true;
                            }
                        } else assertEquals(503, status);
                    } finally {
                        probe.disconnect();
                    }
                    if (!recovered) Thread.sleep(100);
                }
                assertTrue(recovered, "Slow readers must not permanently exhaust the bounded writer pool");
            } finally {
                for (Socket socket : stalled) socket.close();
            }
        }
    }
}
