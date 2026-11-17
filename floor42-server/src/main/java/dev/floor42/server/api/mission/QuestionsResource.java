package dev.floor42.server.api.mission;

import static dev.floor42.server.api.DirectorRequests.parse;

import dev.floor42.domain.Actor;
import dev.floor42.domain.AssurancePolicy;
import dev.floor42.domain.Origin;
import dev.floor42.domain.QuestionId;
import dev.floor42.domain.QuestionKind;
import dev.floor42.server.api.AssuranceResponses;
import dev.floor42.server.api.mission.QuestionViews.QuestionView;
import dev.floor42.server.app.core.OfficeService;
import dev.floor42.server.app.core.RefusedException;
import dev.floor42.server.iam.AssuredActs;
import dev.floor42.server.iam.IamSettings;
import dev.floor42.server.security.LocalAccessGuard;
import dev.floor42.server.security.SessionRegistry;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/** The director's desk: the open questions, their answers, and remembered answers taken back. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class QuestionsResource {

    /**
     * @param askAgain nullable: the remembered refusal (its approval question's id, one of the question's
     *     {@code askAgain}) the director wants to be asked again for; its refusal no longer decides, nothing is allowed
     */
    public record AnswerRequest(String answer, String askAgain) {}

    /** @param text the director's question or remark for the agent, 1 to 4000 characters (ADR-0059) */
    public record AskBackRequest(String text) {}

    private final OfficeService office;
    private final CurrentVertxRequest request;
    private final IamSettings iam;
    private final AssuredActs acts;

    public QuestionsResource(OfficeService office, CurrentVertxRequest request, IamSettings iam, AssuredActs acts) {
        this.office = office;
        this.request = request;
        this.iam = iam;
        this.acts = acts;
    }

    @GET
    @Path("/questions")
    public List<QuestionView> openQuestions() {
        return office.query(
                c -> c.desk().open().stream().map(q -> QuestionView.of(c, q)).toList());
    }

    /** The director answers from the desk. The answer is recorded in the mode (simulated or real) of the mission. */
    @POST
    @Path("/questions/{id}/answer")
    public QuestionView answer(@PathParam("id") String id, AnswerRequest request) {
        QuestionId question = parse(() -> new QuestionId(id));
        Origin origin = originOf(question, id);
        refuseAPermissionToANarrowScope(question);
        refuseADecisionToABoundToken(question);
        QuestionId askAgain = request == null
                        || request.askAgain() == null
                        || request.askAgain().isBlank()
                ? null
                : parse(() -> new QuestionId(request.askAgain()));
        // Lifting a remembered refusal takes an approval back, as /forget does: the director's own scope only
        // (ADR-0044), so the lift journaled as the director's is the director's.
        if (askAgain != null && LocalAccessGuard.scopeOf(this.request.getCurrent()) != SessionRegistry.Scope.ALL) {
            throw new RefusedException(
                    403, "SCOPE", "Lifting a remembered refusal needs a token of scope all, as forgetting one does");
        }
        AssurancePolicy.Level level = assuranceOf(question, origin, request == null ? null : request.answer());
        if (level != AssurancePolicy.Level.BASIC) {
            throw stepUp(question, origin, request.answer(), askAgain, level);
        }
        office.execute(c -> c.desk()
                .answer(
                        office.context(Actor.director(), origin),
                        question,
                        request == null ? null : request.answer(),
                        askAgain));
        return view(question);
    }

    /**
     * What this answer needs besides the click (ADR-0081): in IAM mode, an ALLOW to a call that reaches outside needs a
     * fresh sign-in at the strong level. Any other answer, and every answer with IAM mode off, keeps the click. A
     * malformed answer is left to the desk to refuse.
     */
    private AssurancePolicy.Level assuranceOf(QuestionId question, Origin origin, String answer) {
        if (!iam.enabled() || answer == null || answer.isBlank()) return AssurancePolicy.Level.BASIC;
        return office.query(c -> {
            String chosen = c.desk().checkAnswer(office.context(Actor.director(), origin), question, answer);
            return c.desk().assuranceFor(question, chosen);
        });
    }

    /**
     * A token cannot step up in band: 403 {@code HUMAN_ONLY} with the RFC 9470 challenge. A browser is sent to the
     * sign-in (202 {@code {redirect}}); the answer is recorded with its signature when Keycloak confirmed the strong
     * level, not before.
     */
    private RuntimeException stepUp(
            QuestionId question, Origin origin, String answer, QuestionId askAgain, AssurancePolicy.Level level) {
        if (LocalAccessGuard.viaOf(this.request.getCurrent()) != LocalAccessGuard.Via.COOKIE)
            return AssuranceResponses.humanOnly();
        var session = LocalAccessGuard.sessionOf(this.request.getCurrent());
        if (!acts.mayAssure(session)) return AssuranceResponses.needsSignIn();
        String path = acts.record(
                new AssurancePolicy.Act(AssurancePolicy.ActKind.QUESTION, question.value()),
                level == AssurancePolicy.Level.FOUR_EYES,
                session.get(),
                signed -> {
                    try {
                        office.execute(c -> c.desk()
                                .answer(office.context(Actor.director(), origin), question, answer, askAgain, signed));
                    } catch (RefusedException e) {
                        throw AssuranceResponses.failure(e);
                    }
                });
        return AssuranceResponses.followStepUp(path);
    }

    /**
     * The director asks the agent back instead of answering (ADR-0059): the question is withdrawn with their words, and
     * the agent's waiting session is told. The director's credentials only: a script token of a narrower scope never
     * speaks to an agent. 409 SESSION_GONE when no session waits any more.
     */
    @POST
    @Path("/questions/{id}/ask-back")
    public QuestionView askBack(@PathParam("id") String id, AskBackRequest body) {
        QuestionId question = parse(() -> new QuestionId(id));
        Origin origin = originOf(question, id);
        office.execute(c -> {
            boolean eligible = c.desk()
                    .find(question)
                    .map(q -> c.desk().askBack(q).possible())
                    .orElse(false);
            if (eligible && !office.isWaitedOn(question)) {
                throw new RefusedException(
                        409, "SESSION_GONE", "The agent's session no longer waits for " + id + ": answer it instead");
            }
            return c.desk()
                    .askBack(office.context(Actor.director(), origin), question, body == null ? null : body.text());
        });
        return view(question);
    }

    /** Takes back a remembered answer: the next request in its scope asks the director again. */
    @POST
    @Path("/questions/{id}/forget")
    public QuestionView forget(@PathParam("id") String id) {
        QuestionId question = parse(() -> new QuestionId(id));
        Origin origin = originOf(question, id);
        office.execute(c -> c.desk().forgetApproval(office.context(Actor.director(), origin), question));
        return view(question);
    }

    /**
     * An agent's own free question (ask_director): the one kind of question a script token answers, here and through
     * the MCP tool. Floor42's decision questions (a budget, a plan limit, staffing, a review escalation, an arbitration,
     * a retry, a dropped ticket) carry agent-written context and start paid work or cancel tickets, so they stay human.
     */
    public static boolean agentsFreeQuestion(QuestionView q) {
        return "OPEN".equals(q.kind()) && q.action() == null && q.askedBy() != null && !"floor42".equals(q.askedBy());
    }

    /** A token bound to Keycloak (ADR-0083) answers an agent's free question only; anything else is the desk's. */
    private void refuseADecisionToABoundToken(QuestionId question) {
        if (!LocalAccessGuard.boundOf(request.getCurrent())) return;
        boolean free = office.query(c -> c.desk()
                .find(question)
                .map(q -> agentsFreeQuestion(QuestionView.of(c, q)))
                .orElse(false));
        if (!free) {
            throw new RefusedException(
                    403,
                    "HUMAN_ONLY",
                    "An approval or a Floor42 decision is the director's own act: answer it on the desk");
        }
    }

    /**
     * A permission question (an agent's tool call waiting for ALLOW or DENY) needs the director's full scope: a
     * {@code missions:write} script token steers missions, it does not grant an agent a tool (ADR-0044). The guard let
     * the request through on its path; only the desk knows the question's kind.
     */
    private void refuseAPermissionToANarrowScope(QuestionId question) {
        if (LocalAccessGuard.scopeOf(request.getCurrent()) == SessionRegistry.Scope.ALL) return;
        boolean permission = office.query(c -> c.desk()
                .find(question)
                .map(q -> q.kind() == QuestionKind.ACTION_APPROVAL)
                .orElse(false));
        if (permission) {
            throw new RefusedException(
                    403,
                    "SCOPE",
                    "A permission question needs a token of scope all: this one may not grant a tool call");
        }
    }

    /** The mode of the question's mission, which its answer is recorded in. */
    private Origin originOf(QuestionId question, String id) {
        return office.query(c -> c.desk()
                .find(question)
                .flatMap(q -> c.missions().find(q.mission()))
                .orElseThrow(() -> new NotFoundException("No question " + id))
                .origin());
    }

    private QuestionView view(QuestionId question) {
        return office.query(c -> QuestionView.of(c, c.desk().find(question).orElseThrow()));
    }
}
