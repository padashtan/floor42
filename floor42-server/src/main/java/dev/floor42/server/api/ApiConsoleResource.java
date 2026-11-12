package dev.floor42.server.api;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/** The API console's history; the live calls are on {@code /ws/api-calls}. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ApiConsoleResource {

    private final ApiCalls apiCalls;

    public ApiConsoleResource(ApiCalls apiCalls) {
        this.apiCalls = apiCalls;
    }

    /** The API console's history: the latest calls made to the daemon, oldest first (live ones on /ws/api-calls). */
    @GET
    @Path("/calls")
    public List<ApiCalls.Call> calls(
            @Parameter(
                            description =
                                    "How many of the latest calls to return, 1 up to the console capacity (default 200).")
                    @QueryParam("limit")
                    @DefaultValue("200")
                    int limit) {
        return apiCalls.recent(Math.clamp(limit, 1, ApiCalls.CAPACITY));
    }

    /** One call of the console with its request and response bodies (capped), while the console keeps it. */
    @GET
    @Path("/calls/{sequence}")
    public ApiCalls.CallDetail call(@PathParam("sequence") long sequence) {
        return apiCalls.detail(sequence).orElseThrow(() -> new NotFoundException("No call " + sequence + " is kept"));
    }
}
