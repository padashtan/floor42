package dev.floor42.server.api;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Applies the API's documentation and examples ({@link ApiDocs}), then what every route shares: error responses,
 * security schemes per path group and stability marks ({@link ApiContract}). The status banner is open.
 */
public class OpenApiSecurity implements OASFilter {

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        ApiDocs.apply(openAPI);
        ApiContract.apply(openAPI);
    }
}
