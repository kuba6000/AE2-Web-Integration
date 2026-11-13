package pl.kuba6000.ae2webintegration.core.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.notification.destination.ntfy.NtfyDestination;
import pl.kuba6000.ae2webintegration.core.notification.message.StatusMessage;

class NtfyDestinationTest {

    @Test
    void aBareHostUsesHttpsEvenWhenTheAddressContainsHttpText() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream input = getClass().getResourceAsStream("/telemetry/localhost.p12")) {
            keys.load(input, "test-only".toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-only".toCharArray());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(keys);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), trusts.getTrustManagers(), null);
        SSLSocketFactory previous = HttpsURLConnection.getDefaultSSLSocketFactory();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        CountDownLatch received = new CountDownLatch(1);
        server.createContext("/http-notify", exchange -> {
            received.countDown();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (ConfigTestFixture config = new ConfigTestFixture()) {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            config.set(
                "ntfy.host",
                "127.0.0.1:" + server.getAddress()
                    .getPort() + "/http-notify");
            config.set("ntfy.topic", "notifications");
            new NtfyDestination().sendNotification(
                new StatusMessage(
                    "Delivery",
                    "A configured host without an explicit scheme",
                    StatusMessage.Severity.NONE));
            assertTrue(received.await(1, TimeUnit.SECONDS));
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(previous);
            server.stop(0);
        }
    }
}
