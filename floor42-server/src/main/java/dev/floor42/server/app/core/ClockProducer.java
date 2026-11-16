package dev.floor42.server.app.core;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;

public class ClockProducer {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
