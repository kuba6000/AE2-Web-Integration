package pl.kuba6000.ae2webintegration.core;

import java.lang.reflect.Field;

import com.sun.net.httpserver.HttpServer;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;

/** Lets the actual listener reserve its test port without a probe-and-close race. */
final class HttpServerTestFixture {

    private HttpServerTestFixture() {}

    static int start(ConfigTestFixture config) {
        int port = start(AE2Controller::startHTTPServer);
        config.set("general.port", port);
        return port;
    }

    static int startWorld(ConfigTestFixture config) {
        int port = startWorld();
        config.set("general.port", port);
        return port;
    }

    static int startWorld() {
        return start(CoreEngine::onServerStarted);
    }

    private static int start(Runnable startup) {
        int configuredPort = Config.INSTANCE.general.port;
        // Only the test listener uses port zero; normal configuration validation remains unchanged.
        Config.INSTANCE.general.port = 0;
        try {
            startup.run();
        } finally {
            Config.INSTANCE.general.port = configuredPort;
        }
        try {
            Field serverField = AE2Controller.class.getDeclaredField("server");
            serverField.setAccessible(true);
            return ((HttpServer) serverField.get(null)).getAddress()
                .getPort();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot read the test HTTP listener address", failure);
        }
    }
}
