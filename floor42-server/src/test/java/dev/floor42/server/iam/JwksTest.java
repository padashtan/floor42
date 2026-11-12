package dev.floor42.server.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JwksTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);

    private static Jwks jwks(FakeKeycloak kc) {
        return new Jwks(URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), CLOCK);
    }

    @Test
    void aKnownKidIsFetchedOnceAndCached() {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            assertThat(j.key("fake-1")).isPresent();
            assertThat(j.key("fake-1")).isPresent();
            assertThat(kc.requests().stream().filter(r -> r.get("_path").endsWith("/certs")))
                    .hasSize(1);
        }
    }

    @Test
    void manyUnknownKidsCostOneFetchPerWindow() {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            for (int i = 0; i < 20; i++) assertThat(j.key("forged-" + i)).isEmpty();
            assertThat(kc.requests().stream().filter(r -> r.get("_path").endsWith("/certs")))
                    .hasSize(1);
        }
    }

    @Test
    void keysUnder2048BitsAndKeysWithoutKidAreSkipped() throws Exception {
        var g = java.security.KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        var small = g.generateKeyPair().getPublic();
        g.initialize(2048);
        var big = g.generateKeyPair().getPublic();
        String all = String.join(
                ",", JwksServer.rsaJwk(small, "small"), JwksServer.rsaJwk(big, null), JwksServer.rsaJwk(big, "big"));
        try (var server = new JwksServer(all)) {
            Jwks j = new Jwks(server.uri(), HttpClient.newHttpClient(), CLOCK);
            assertThat(j.key("small")).isEmpty();
            assertThat(j.key("")).isEmpty();
            assertThat(j.key("big")).isPresent();
        }
    }

    @Test
    void aFailedFetchKeepsTheOldKeysAndSaysUnreachable() {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            kc.down(true);
            assertThat(j.key("fake-1")).isEmpty();
            assertThat(j.reachable()).isFalse();
        }
    }

    @Test
    void codeHoldingTheInstanceLockCannotStallTheKeyRefreshOrAMiss() throws Exception {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            var held = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            Thread holder = new Thread(() -> {
                synchronized (j) {
                    held.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            holder.start();
            held.await();
            try {
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
                    j.refresh();
                    assertThat(j.key("fake-1")).isPresent();
                });
            } finally {
                release.countDown();
                holder.join();
            }
        }
    }

    @Test
    void eachFetchIsShownAmongTheExchangesWithItsStatusAndDuration() {
        try (var kc = FakeKeycloak.start("floor42")) {
            IdpExchanges log = new IdpExchanges();
            Jwks j = jwks(kc);
            assertThat(j.recordInto(log)).isSameAs(j);
            kc.delay(java.time.Duration.ofMillis(200));
            assertThat(j.key("fake-1")).isPresent();

            assertThat(log.list()).singleElement().satisfies(e -> {
                assertThat(e.op()).isEqualTo("signing keys");
                assertThat(e.method()).isEqualTo("GET");
                assertThat(e.path()).endsWith("/protocol/openid-connect/certs");
                assertThat(e.status()).isEqualTo(200);
                assertThat(e.millis()).isBetween(150L, 10_000L);
                assertThat(e.error()).isNull();
            });
        }
    }

    @Test
    void anUnreachableIssuerIsRecordedOnceWithTheErrorAndNoStatus() {
        IdpExchanges log = new IdpExchanges();
        Jwks j = new Jwks(URI.create("http://127.0.0.1:1/certs"), HttpClient.newHttpClient(), CLOCK).recordInto(log);

        assertThat(j.key("any")).isEmpty();

        assertThat(j.reachable()).isFalse();
        assertThat(log.list()).singleElement().satisfies(e -> {
            assertThat(e.status()).isZero();
            assertThat(e.error()).isEqualTo("ConnectException");
        });
    }

    @Test
    void aFetchInterruptedKeepsTheFlagAndSaysUnreachable() {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            Thread.currentThread().interrupt();
            try {
                assertThat(j.key("fake-1")).isEmpty();
                assertThat(j.reachable()).isFalse();
                assertThat(Thread.currentThread().isInterrupted())
                        .as("the interrupt is not swallowed")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void aMissIsRefetchedOnceTheWindowHasPassedAndNotBefore() {
        try (var kc = FakeKeycloak.start("floor42")) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-11-05T10:00:00Z"));
            Clock clock = new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            Jwks j = new Jwks(
                    URI.create(kc.issuer() + "/protocol/openid-connect/certs"), HttpClient.newHttpClient(), clock);
            assertThat(j.key("forged")).isEmpty();
            now.updateAndGet(t -> t.plusSeconds(29).plusMillis(999));
            assertThat(j.key("forged")).isEmpty();
            assertThat(certsRequests(kc)).isEqualTo(1);
            now.updateAndGet(t -> t.plusMillis(1));
            assertThat(j.key("forged")).isEmpty();
            assertThat(certsRequests(kc)).isEqualTo(2);
        }
    }

    private static long certsRequests(FakeKeycloak kc) {
        return kc.requests().stream()
                .filter(r -> r.get("_path").endsWith("/certs"))
                .count();
    }

    @Test
    void refreshFetchesAtOnceAndReachableFollowsTheLastFetch() {
        try (var kc = FakeKeycloak.start("floor42")) {
            Jwks j = jwks(kc);
            j.refresh();
            assertThat(certsRequests(kc)).isEqualTo(1);
            assertThat(j.reachable()).isTrue();
            assertThat(j.key("fake-1")).isPresent();
            assertThat(certsRequests(kc))
                    .as("served from the keys the refresh fetched")
                    .isEqualTo(1);

            kc.down(true);
            j.refresh();
            assertThat(j.reachable()).isFalse();
            assertThat(j.key("fake-1")).as("the old keys stay usable").isPresent();
            kc.down(false);
            j.refresh();
            assertThat(j.reachable()).isTrue();
        }
    }
}
