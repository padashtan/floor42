package dev.floor42.iam;

import jakarta.ws.rs.core.MultivaluedMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/**
 * On a token exchange only: `floor42 = {launch, mission, ticket}` from the form params of the authenticated
 * confidential requester (all three valid, else nothing), and a 300 s lifetime for tokens not meant for floor42-api.
 */
public class Floor42ContextMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper {

  public static final String PROVIDER_ID = "floor42-context-mapper";

  private static final Pattern MISSION = Pattern.compile("M-\\d{1,9}");
  private static final Pattern TICKET = Pattern.compile("T-\\d{1,9}");
  private static final List<ProviderConfigProperty> CONFIG = new ArrayList<>();

  static {
    OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG, Floor42ContextMapper.class);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return "Floor42 session context";
  }

  @Override
  public String getDisplayCategory() {
    return TOKEN_MAPPER_CATEGORY;
  }

  @Override
  public String getHelpText() {
    return "Adds floor42 {launch, mission, ticket} from the exchange form params; caps non-API tokens at 300 s.";
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return CONFIG;
  }

  @Override
  public int getPriority() {
    return 100; // run late; the lifetime rule does not depend on it (it reads the request, not mapper output)
  }

  @Override
  protected void setClaim(
      IDToken token, ProtocolMapperModel model, UserSessionModel us, KeycloakSession ks, ClientSessionContext ctx) {
    if (!Exchange.isTokenExchange(ctx)) return;
    if (Exchange.authenticatedRequester(ks, ctx) != null) {
      MultivaluedMap<String, String> form = ks.getContext().getHttpRequest().getDecodedFormParameters();
      String launch = form.getFirst("floor42_launch");
      String mission = form.getFirst("floor42_mission");
      String ticket = form.getFirst("floor42_ticket");
      if (isUuid(launch) && matches(MISSION, mission) && matches(TICKET, ticket)) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("launch", UUID.fromString(launch).toString());
        c.put("mission", mission);
        c.put("ticket", ticket);
        token.getOtherClaims().put("floor42", c);
      }
    }
    // Measured: at this point aud still holds every scope audience; Keycloak narrows it to the requested `audience`
    // params afterwards. The uncapped lifetime is for a token whose audience set is exactly {floor42-api}.
    MultivaluedMap<String, String> form = ks.getContext().getHttpRequest().getDecodedFormParameters();
    List<String> requested = form.get("audience");
    Set<String> aud =
        requested != null && !requested.isEmpty()
            ? new HashSet<>(requested)
            : new HashSet<>(List.of(token.getAudience() == null ? new String[0] : token.getAudience()));
    if (!aud.equals(Set.of("floor42-api")) && token.getExp() != null && token.getIat() != null) {
      token.exp(Math.min(token.getExp(), token.getIat() + 300));
    }
  }

  private static boolean matches(Pattern p, String s) {
    return s != null && p.matcher(s).matches();
  }

  private static boolean isUuid(String s) {
    try {
      return s != null && UUID.fromString(s).toString().equals(s.toLowerCase());
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
