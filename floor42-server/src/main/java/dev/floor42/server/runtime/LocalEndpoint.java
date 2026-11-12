package dev.floor42.server.runtime;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.HttpServerStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/** The daemon's actual loopback address, for the hooks of live sessions (tests run on a random port). */
@ApplicationScoped
public class LocalEndpoint {

    private volatile int port = -1;

    /** The application's own configuration: ConfigProvider.getConfig() follows the calling thread's context class loader, and a pool thread may see another one (a zero port). */
    @Inject
    Config config;

    /** Quarkus announces the started server asynchronously. */
    void started(@ObservesAsync HttpServerStart start) {
        port = start.options().getPort();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + port() + path;
    }

    private int port() {
        if (port > 0) return port;
        // Fallback when the start notification has not arrived (or carries no port): the effective configuration. With
        // a random test port, Quarkus publishes the port it actually bound under quarkus.http.test-port.
        String key = LaunchMode.current() == LaunchMode.TEST ? "quarkus.http.test-port" : "quarkus.http.port";
        Optional<Integer> configured = config.getOptionalValue(key, Integer.class);
        if (configured.isPresent() && configured.get() > 0) return configured.get();
        throw new IllegalStateException("HTTP server port unknown yet");
    }
}
