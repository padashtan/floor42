package dev.floor42.server.api;

import dev.floor42.domain.MissionId;
import dev.floor42.server.app.DemoScenario;
import dev.floor42.server.app.PracticeScenario;
import dev.floor42.server.app.RefusedException;
import dev.floor42.server.app.SampleRepository;
import dev.floor42.server.runtime.SessionReport;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

/**
 * The simulated missions: the demo, and the guided first mission on Floor42's sample repository (ADR-0045). Nothing
 * real is executed, no model is called, every event is recorded as simulated.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class DemoResource {

    public record DemoResponse(String mission, SessionReport session) {}

    public record LiveDemoResponse(String mission) {}

    /** Where Floor42's sample repository is: in its data folder, never one of the director's. */
    public record SampleView(String path) {}

    /** Where a rehearsal stands: PLAYING, DONE, or STOPPED (the daemon restarted, a refusal, patience ran out). */
    public record PracticeView(String mission, String state) {}

    /** The guided first mission: the director's own title and objective, on the sample repository. */
    public record PracticeRequest(String title, String objective) {}

    private final DemoScenario demo;
    private final PracticeScenario practice;
    private final SampleRepository sample;

    public DemoResource(DemoScenario demo, PracticeScenario practice, SampleRepository sample) {
        this.demo = demo;
        this.practice = practice;
        this.sample = sample;
    }

    /** Runs the end-to-end simulated mission. Each call creates a new mission; nothing real is executed. */
    @POST
    @Path("/demo")
    public DemoResponse runDemo() {
        DemoScenario.Result result = demo.run();
        return new DemoResponse(result.mission().value(), result.session());
    }

    /**
     * Plays the demo at a watchable pace in the background and waits for the director to answer the approval request
     * from the desk. Returns at once with the new mission id.
     */
    @POST
    @Path("/demo/live")
    public Response startLiveDemo(
            @Parameter(description = "Milliseconds between two steps of the demo, 0 to 10000.")
                    @QueryParam("paceMs")
                    @DefaultValue("1500")
                    long paceMs) {
        MissionId mission = demo.startLive(Duration.ofMillis(Math.clamp(paceMs, 0, 10_000)));
        return Response.accepted(new LiveDemoResponse(mission.value())).build();
    }

    /** Creates Floor42's sample repository the first time (one README, one commit), and says where it is. */
    @POST
    @Path("/demo/sample")
    public SampleView prepareSample() {
        return new SampleView(sample.prepare().toString());
    }

    /**
     * Starts the guided first mission: a simulated rehearsal on the sample repository, its plan awaiting the director's
     * approval on return. It then waits for the director at each decision, as a real mission would. Returns the id.
     */
    @POST
    @Path("/demo/practice")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response startPractice(
            PracticeRequest request,
            @Parameter(description = "Milliseconds between two steps of the demo, 0 to 10000.")
                    @QueryParam("paceMs")
                    @DefaultValue("800")
                    long paceMs) {
        if (request == null) throw RefusedException.invalid("INVALID_ARGUMENT", "Give a title and an objective");
        MissionId mission =
                practice.start(request.title(), request.objective(), Duration.ofMillis(Math.clamp(paceMs, 0, 10_000)));
        return Response.accepted(new LiveDemoResponse(mission.value())).build();
    }

    /** Where the rehearsal stands, for the guide: a STOPPED one is not resumed, the guide offers a new one. */
    @GET
    @Path("/demo/practice/{id}")
    public PracticeView practice(@PathParam("id") String id) {
        MissionId m = DirectorRequests.parse(() -> new MissionId(id));
        return practice.state(m)
                .map(s -> new PracticeView(m.value(), s.name()))
                .orElseThrow(() -> new NotFoundException(m + " is not a rehearsal"));
    }
}
