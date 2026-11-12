package dev.floor42.iam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/** RFC 8693 `act`: on a token exchange only, the authenticated requesting client becomes the actor. */
public class ActorClaimMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper {

  public static final String PROVIDER_ID = "floor42-act-mapper";

  private static final List<ProviderConfigProperty> CONFIG = new ArrayList<>();

  static {
    OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG, ActorClaimMapper.class);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return "Floor42 actor (act)";
  }

  @Override
  public String getDisplayCategory() {
    return TOKEN_MAPPER_CATEGORY;
  }

  @Override
  public String getHelpText() {
    return "Adds act {sub, client_id} of the authenticated requesting client on a token exchange.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return CONFIG;
  }

  @Override
  protected void setClaim(
      IDToken token, ProtocolMapperModel model, UserSessionModel us, KeycloakSession ks, ClientSessionContext ctx) {
    if (!Exchange.isTokenExchange(ctx)) return;
    ClientModel requester = Exchange.authenticatedRequester(ks, ctx);
    if (requester == null) return;
    UserModel serviceAccount = ks.users().getServiceAccount(requester);
    if (serviceAccount == null) return; // not a service-account client: no claim, the verifier refuses
    token.getOtherClaims().put("act", Map.of("sub", serviceAccount.getId(), "client_id", requester.getClientId()));
  }
}
