package dev.floor42.iam;

import org.keycloak.OAuth2Constants;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;

/** The two guards both mappers share: a token-exchange grant, and the client that authenticated is the requester. */
final class Exchange {

  private Exchange() {}

  static boolean isTokenExchange(ClientSessionContext ctx) {
    return OAuth2Constants.TOKEN_EXCHANGE_GRANT_TYPE.equals(ctx.getAttribute(Constants.GRANT_TYPE, String.class));
  }

  /**
   * The requesting client, only when it is the confidential client that authenticated this very request
   * (never a public or bearer-only client, never a client named in a parameter); otherwise null.
   */
  static ClientModel authenticatedRequester(KeycloakSession ks, ClientSessionContext ctx) {
    ClientModel requester = ctx.getClientSession().getClient();
    ClientModel authenticated = ks.getContext().getClient();
    if (authenticated == null || !authenticated.getId().equals(requester.getId())) return null;
    if (requester.isPublicClient() || requester.isBearerOnly()) return null;
    return requester;
  }
}
