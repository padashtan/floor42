# ADR-0024: The idea committee, before a new project

Date: 2026-10-29 · Status: Accepted

## Context
Before starting a project, the director wants an idea discussed by a committee of very different people who are told to be neutral, and who must end up agreeing on the solution that fits best and can really be run. The seats asked for: a lawyer, an altruist, a bold visionary, the director themself, a global architect, a security architect, an experienced developer, a salesperson with her own ideas, and someone who knows nothing about IT but listens and has a say.

The architecture committee (ADR-0015) already gives independent analyses, a debate, a record and a ruling, all from real sessions. But its chair takes part, it debates once, and it never says whether the members agree.

The director chose, on 2026-10-29:
- agreement is sought, but never forced;
- a neutral moderator chairs;
- the director has a double who debates, and may speak in person in its seat at any round.

## Decision
- **A second panel.** A mission may convene an `idea` committee instead of an `architecture` one. Its seats:
  - moderator (Lucie, chairs);
  - lawyer (Clara), altruist (Malik), visionary disruptor (Iris), the director's double, global architect (Geoffrey), security (Nora), experienced developer (Chris), salesperson (Sofia), and someone who knows nothing about IT (Marcel).
  
  Each seat is an agent with its own lens, model and effort, hired on first need. The visionary is a style of thinking, not an impersonation: the agent is named Max.
- **Neutral by brief and by structure.** Every member's brief tells it to serve no side and not to guess what the director wants to hear. Round 1 stays independent: no member sees another's analysis.
  The moderator neither analyses nor debates. It only writes the record, and its brief forbids it from proposing anything of its own.
- **Agreement is measured, not declared.** Office reads each member's `POSITION` line: `ADOPTED <member>` stands behind that member's proposal; `NEW`, `MAINTAINED` or `REVISED` stand behind one's own. A missing line is not agreement.
  The members agree when every one of them stands behind the same proposal and no critical objection stands. Until then, Office holds another debate round, at most 3. The record then comes either way: agreement reached, or "did not all agree after N rounds", and the director's question says which.
- **The director's double.**
  - Its lens is the `DIRECTOR_PROFILE` setting, which the director writes in their own words. Until they do, Office fills it with what it knows of them.
  - The double sits last, so the director has time to speak first.
  - The director may speak in person in its seat during any analysis or debate round (`POST /api/missions/{id}/committees/{no}/contribute` since 1.8.0, ADR-0046; `/committee/contribute` before). The contribution is recorded as the director's (`byDirector`), and members and the moderator see it marked so.
  - Said before the double in a round, the double does not speak that round. Said after, it replaces the double's position.
- **No repository needed.** An idea may come before its project. With no repository given, Office creates an empty git repository under its data directory (`projects/<title>`), so the committee runs read only like any other.
- **Existing committees are unchanged.** The architecture committee keeps one debate round and a chair that takes part. Events recorded before this change read as an architecture committee.

## Consequences
- The cost is (members × rounds) + 1 sessions. With the nine members and the moderator, that is 19 sessions if they agree in the first debate round and 37 at most. Geoffrey on Opus with high effort is the most expensive seat. The mission budget holds the next session when it is reached.
- An agreement is still not a proof: the moderator's record lists what remains to be verified, and the director still rules (adopt, adopt and implement, send back, reject).
- The director's in-person contribution and the double's session may cross: when the director fills the seat while the double's session runs, the session's text is set aside and logged, and the director's stands.

## Reopen if
Members agree too early to please (agreement in the first debate round on most ideas, with records that hide disagreements), three rounds are not enough to converge, or the director wants the committee to wait for them before each round.
