package dev.floor42.server.iam;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** The IAM collaborators that are not CDI classes themselves. Nothing here reaches the network until it is used. */
class IamBeans {

    /** Lazy (a client proxy): the platform lookup is only touched when IAM mode is on. */
    @Produces
    @ApplicationScoped
    SocketOwners socketOwners() {
        return SocketOwners.forPlatform();
    }

    /** Checks agent tokens against the pinned issuer and the one audience; local, no network on the hook path. */
    @Produces
    @Singleton
    AgentTokenVerifier verifier(IamSettings settings, Jwks jwks) {
        return new AgentTokenVerifier(
                settings.issuer(),
                AgentTokenVerifier.AUDIENCE,
                jwks,
                Clock.systemUTC(),
                Duration.ofHours(settings.maxTokenHours()));
    }

    /** The issuer's signing keys, from the certs URL of the pinned issuer. No redirect is ever followed. */
    @Produces
    @Singleton
    Jwks jwks(IamSettings settings, IdpExchanges exchanges) {
        URI certs = URI.create(settings.issuer().toString().replaceAll("/+$", "") + "/protocol/openid-connect/certs");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new Jwks(certs, http, Clock.systemUTC()).recordInto(exchanges);
    }
}
