# ADR-0018: A reviewer in doubt consults a peer or a web research, once per review

Date: 2026-10-20 · Status: Accepted. The director chose: one request then a new review; research governed by the permission table; research in a session without the repository.

## Context
A reviewer who cannot conclude used to have two choices: guess, or answer UNVERIFIABLE and send the question to the director. The director wants the reviewer to be able to ask another agent, or to search the web, before concluding.

## Decision
- **The request.** Instead of a verdict, the reviewer may end its review with one line: `CONSULT: <role>: <question>` or `RESEARCH: <question>`. The role is one of the committee roles: architect, security, developer, operations or integration. Office records `ConsultationRequested`, gets an answer (`ConsultationAnswered`), and runs the review again with the question and the answer in the brief. Only one request is allowed per review round, a rule enforced by the domain. On the second pass the brief asks for a verdict. A verdict line always wins over a request line.
- **Consulting a peer.** The role's agent works in its own session. It reads the producer's worktree with Read, Glob and Grep only, and rules naming it deny writing, the shell and the network. It cannot be the producer: Chris is the developer role, and a request for him is answered by Office ("cannot advise its own reviewer").
- **Web research.** Noe, the researcher, gets only the question. His session runs in an empty directory and is offered only the web tools that the mission's table does not deny. Rules naming her deny reading files, writing and the shell. She never sees the repository, so she cannot send any of it to the web. Following the director's choice, the permission table governs research:
  - If WebSearch and WebFetch are both denied (the default), no session runs. Office answers "Research not run" and the reviewer concludes without it.
  - With ASK, the first query is shown to the director before it leaves; "ask once" then covers the researcher's later queries on that tool for the mission. With "ask every time", each query is shown. (Corrected 2026-10-21: the first version of this ADR said "each query" for ASK.)
  - With ALLOW, queries leave directly.
  Every call is journaled like any other.
- **Visible.** The event log shows the request and who answered. The mission board shows each consultation, its question and its answer next to the reviews.

## Consequences
- One or two more sessions when a reviewer asks for help. The budget and the plan-limit hold (ADR-0016) cover them like any other session.
- Verified end to end with the fake Claude Code (`ConsultationApiTest`): a consultation of the security specialist, and a research both denied by the default table and allowed (one governed WebSearch). No real reviewer was made to ask for help, because one cannot be made to doubt on demand. The first real request will show how well the brief works.

## Reopen if
Reviewers ask for help where checking themselves would do, one request proves too few, or research needs sources the web tools cannot reach.
