package dev.floor42.server.api.journal;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The mission proof dossier (ADR-0071): what a mission did, read from its journal, with the source of every fact. Every
 * text has been through the replay export's sanitizer. No unmasked form exists.
 */
public record ProofDossier(
        int format,
        String generator,
        String version,
        String generatedAt,
        String statement,
        MissionPart mission,
        boolean iam,
        Section<IdentityFact> identities,
        DelegationFact delegation,
        Section<BrokerFact> brokerCalls,
        Section<RefusalFact> refusals,
        Section<ApprovalFact> approvals,
        List<BasisCount> authorized,
        CostPart costs,
        Section<CheckFact> checks,
        Section<DeliveryFact> deliverables,
        Section<DiffFact> diff,
        String gitReadAt,
        List<MaskCount> masked,
        List<String> limits) {

    /** Where a fact comes from. */
    public static final String JOURNAL = "JOURNAL";

    public static final String GIT = "GIT";
    public static final String STATE = "STATE";
    public static final String DERIVED = "DERIVED";

    /** A list capped at {@value ProofDossiers#CAP}; {@code count} is always the full one. */
    public record Section<T>(int count, List<T> items) {}

    /** One journal event a fact rests on; {@code seq} numbers the mission's events from 1, as the replay export does. */
    public record EventRef(int seq, String eventId, String type) {}

    /**
     * @param source JOURNAL, GIT, STATE or DERIVED
     * @param basis a constant of {@code MissionReports} when the fact is a figure; null otherwise
     * @param origin the envelope's origin (REAL or SIMULATED); null when the source is not the journal
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ref(String source, String basis, String origin, List<EventRef> events) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MissionPart(
            String id,
            String title,
            String objective,
            String origin,
            String status,
            String outcome,
            int events,
            int real,
            int simulated) {}

    /** {@code derived} names the fields Floor42 computed (the journal holds no such claim). */
    public record IdentityFact(
            String agent,
            String client,
            String onBehalfOf,
            String tokenAudience,
            String session,
            String ticket,
            String issuer,
            String expiresAt,
            String jti,
            List<String> derived,
            Ref ref) {}

    /** The delegation as Floor42's state holds it at export time; never a journal event. */
    public record DelegationFact(
            List<String> resources, String grantedAt, String expiresAt, boolean revoked, Ref ref) {}

    public record BrokerFact(
            String agent,
            String resource,
            String method,
            String path,
            String status,
            String exchangedJti,
            String audience,
            Ref ref) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefusalFact(
            String at,
            String agent,
            String ticket,
            String tool,
            String category,
            String summary,
            String reason,
            String detail,
            boolean identity,
            Ref ref) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalFact(String at, String kind, String actor, String text, Ref ref) {}

    public record BasisCount(String basis, int count) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionCost(String agent, String session, Double usd, String basis, Ref ref) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Amount(Double usd, String basis) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CostBound(Double usd, int sessions, String basis) {}

    /** Null where nothing was reported: never a zero. {@code bound} is a cap, shown apart, never added to the total. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CostPart(
            Double budgetUsd, Ref budgetRef, List<SessionCost> sessions, Amount total, CostBound bound) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckFact(
            String at,
            String workspace,
            String command,
            Integer exitCode,
            boolean timedOut,
            long durationMs,
            String trigger,
            String head,
            String kind,
            Ref ref) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeliveryFact(String at, String kind, String text, String commit, Integer files, Ref ref) {}

    /** {@code uncommitted} paths read from git now; {@code commits} beyond the project branch (-1 when git cannot count). */
    public record DiffFact(String agent, String branch, boolean readable, int commits, int uncommitted, Ref ref) {}

    public record MaskCount(String kind, int count) {}
}
