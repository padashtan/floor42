"""Page models of docs/architecture. Every name here was checked against the code at 1.17 (branch integ/1.17).

A page names its boxes once and places them on a grid of rows: an id repeated in adjacent cells spans them, None
leaves a cell empty. build.py computes every position; nothing here is a coordinate.

The event catalogue and the route lists are read from the sources when the diagrams are built, so they cannot drift.
"""

import json
import os
import re

from build import Edge as E
from build import Group as G
from build import Matrix, Node, Page, Seq, Table


def B(id, title, sub=(), kind="floor42", **kw):
    return Node(id, title, sub, kind, "box", **kw)


def S(id, title, sub=()):
    return Node(id, title, sub, "state", "state")


def START(id="start"):
    return Node(id, "", (), "state", "start")


def END(id="end"):
    return Node(id, "", (), "state", "end")


def PILL(id, title):
    return Node(id, title, (), "time", "pill")


def NOTE(id, lines):
    return Node(id, "", lines, "note", "note")


def CARD(id, title, lines, kind="floor42", mono=False):
    return Node(id, title, lines, kind, "card", mono=mono)


# ---------------------------------------------------------------- 10 agent security (IAM mode)

def security():
    p1 = Page(
        "1 Identities", "Who is who, and where each secret lives (IAM mode)",
        "Every identity in the realm, what holds its secret, and the one token an agent process ever sees.",
        [
            G("realm", "Keycloak, realm floor42", [["dir", "desk"], ["agentc", "api"], [None, "daemon"]], "keycloak"),
            B("dir", "Director", ["Keycloak user; signs in through", "floor42-desk (browser, PKCE S256)"], "director"),
            B("desk", "floor42-desk", ["public client: the director's sign-in", "and the refresh of a delegation"], "keycloak"),
            B("agentc", "floor42-agent-<id>", ["one confidential client per agent;", "it performs the token exchange"], "keycloak"),
            B("api", "floor42-api", ["audience of every agent session token;", "Floor42 introspects with its secret"], "keycloak"),
            B("daemon", "floor42-daemon", ["service account, manage-clients:", "creates and disables agent clients"], "keycloak"),
            G("vaultg", "Data folder, iam/ (never mounted in the sandbox)", [["vault"], ["deleg"]], "store"),
            B("vault", "SecretVault", ["AES-256-GCM, key file iam/vault.key; entries:", "floor42-api, floor42-daemon, agent-<id> secrets"], "secret"),
            B("deleg", "delegation-<mission>", ["offline refresh token of the director, 30 days;", "delegations.json keeps metadata only"], "secret"),
            G("mem", "Daemon memory only", [["sreg", "dtok", "isess"]]),
            B("sreg", "SessionRegistry", ["browser session: cookie floor42_session,", "HttpOnly, SameSite=Strict, SHA-256 kept"]),
            B("dtok", "DirectorTokens", ["the director's Keycloak tokens", "after sign-in, never shown"]),
            B("isess", "IamSessions", ["live session record: launch, mission,", "ticket, jti, bound PID"]),
            B("proc", "Claude Code process", ["holds only its session token,", "env FLOOR42_HOOK_TOKEN"], "agent"),
        ],
        [["realm", "vaultg"], ["mem", "proc"]],
        [
            E("vault", "agentc", "client secrets", "flow"),
            E("deleg", "dir", "acts for", "flow"),
            E("dtok", "dir", "tokens of", "flow"),
            E("isess", "proc", "mints, binds PID", "call"),
        ],
    )
    p2 = Page(
        "2 Token chain", "From the director's approval to one allowed or refused tool call",
        "Each step can stop the chain; a refusal is journaled. Read left to right, then right to left.",
        [
            B("approve", "1. Plan approval", ["StepUpApprovals: prompt=login,", "max_age=0, the password again"], "director"),
            B("deleg", "2. Mission delegation", ["Delegations.grant: offline token in the", "vault; refreshed via floor42-desk"], "secret"),
            B("exch", "3. Token exchange, RFC 8693", ["KeycloakClient.exchange by floor42-agent-<id>,", "subject = the director's access token"], "keycloak"),
            B("tok", "4. Session token", ["sub director, act agent, aud floor42-api,", "floor42 {launch, mission, ticket}, exp <= 8 h"], "secret"),
            B("bind", "5. Process binding", ["env FLOOR42_HOOK_TOKEN; PID from", "builder.start() kept in memory"], "agent"),
            B("hook", "6. PreToolUse hook checks", ["signature, issuer, aud, exp + 30 s; launch,", "mission, ticket, revocation, socket PID"], "check"),
            B("table", "7. Permission table", ["MissionGovernor.decide, ActionLog:", "ALLOW, ASK, ASK_ALWAYS, DENY"], "check"),
            B("ok", "Allowed", ["ActionAuthorized; Bash rewritten", "into the WSL sandbox"]),
            B("no", "Refused", ["401 TOKEN_REFUSED or ActionDenied;", "journaled when attributable"], "check"),
        ],
        [["approve", "deleg", "exch", "tok"], ["no", "table", "hook", "bind"], [None, "ok", None, None]],
        [
            E("approve", "deleg", "grant"), E("deleg", "exch", "subject token"), E("exch", "tok", "issued"),
            E("tok", "bind", "verified first"), E("bind", "hook", "every call"), E("hook", "table", "accepted"),
            E("table", "ok", "allow"), E("table", "no", "deny"), E("hook", "no", "refused", "refuse"),
        ],
    )
    p3 = Page(
        "3 Resource broker", "call_resource: the agent asks, Floor42 holds the credential and makes the call",
        "The agent never sees a resource credential; it gets the answer as untrusted data.",
        [
            B("agent", "Agent", ["MCP tool call_resource", "(resource, method, path, body)"], "agent"),
            B("hook", "PreToolUse hook", ["same checks as any tool;", "ActionAuthorized for the toolUseId"], "check"),
            B("mcp", "McpResource", ["governor.authorized(toolUseId):", "one broker call per authorization"], "check"),
            B("broker", "ResourceBroker.call", ["the caller's verified claims,", "never a token from the agent"]),
            B("cat", "ResourceCatalogue", ["both grants: the agent's and the mission's;", "method and path prefix allowed"], "check"),
            B("kc", "Second token exchange", ["audience = exactly the resource;", "at most 300 s, checked after issue"], "keycloak"),
            B("pin", "SafeUrls, PinnedClients", ["resolve once, refuse private ranges, pin", "the IP; SNI checked, no redirect"], "check"),
            B("res", "External resource", ["HTTPS API from iam/resources.json"], "external"),
            B("ans", "Answer to the agent", ["token scrubbed, body capped, quoted", "between BEGIN-UNTRUSTED-<nonce> lines"]),
        ],
        [["agent", "hook", "mcp", "broker", "cat"], ["ans", "res", "pin", "kc", None]],
        [
            E("agent", "hook", "1"), E("hook", "mcp", "2 allow"), E("mcp", "broker", "3"), E("broker", "cat", "4"),
            E("cat", "kc", "5 granted"), E("kc", "pin", "6 token"), E("pin", "res", "7 one call"),
            E("res", "ans", "8 status, body", "flow"), E("ans", "agent", "9", "flow"),
        ],
    )
    p4 = Page(
        "4 Trust boundaries", "The five boundaries an attack has to cross, and what guards each",
        "From docs/threat-model.md section 2. The daemon is orchestrator and security authority in one process.",
        [
            B("browser", "Director's browser", ["cookie floor42_session"], "director"),
            G("d", "Floor42 daemon, one process on 127.0.0.1", [["orch", "broker"], ["hooks", "vault"]]),
            B("orch", "Orchestrator, journal", ["OfficeService, SqliteJournal"]),
            B("hooks", "Hooks and MCP", ["HookResource, McpResource"], "check"),
            B("broker", "Token broker", ["ResourceBroker, IdentityWatch"]),
            B("vault", "Vault", ["SecretVault"], "secret"),
            B("agent", "Agent sessions", ["Claude Code, own worktree"], "agent"),
            B("shell", "WSL sandbox", ["the agent's Bash commands"], "sandbox"),
            B("kc", "Keycloak", ["identity, revocation"], "keycloak"),
            B("res", "External resources", ["HTTPS"], "external"),
        ],
        [["browser", "d", "kc"], [None, "d", "res"], ["shell", "agent", None]],
        [
            E("browser", "d", "(1) LocalAccessGuard: Host, Origin, header"),
            E("agent", "hooks", "(2) per-session token, fail-closed hook, PID"),
            E("agent", "shell", "(3) sandbox, mandatory for real work"),
            E("broker", "kc", "(4) local checks, a pass every 60 s"),
            E("broker", "res", "(5) catalogue, grants, pinned address"),
        ],
    )
    rows4 = [
        ("inj", "Prompt injection", ["boundary (2): the agent obeys text", "from a file or a resource answer"],
         "Bounded by the code", ["permission table, fail-closed hook,", "own worktree, sandbox; answers wrapped"],
         "Residual", ["within what the table allows it acts,", "with its real identity"]),
        ("steal", "Session token stolen", ["boundary (2): a copy of the", "token outside Floor42"],
         "Token checks", ["RS256, issuer, aud floor42-api, exp <= 8 h,", "a live session; ending it refuses the token"],
         "Residual", ["expiry is not revocation; a copy inside", "the owning process is accepted"]),
        ("copy", "Token used by another process", ["boundary (2): curl from the", "agent's shell with the token"],
         "Process binding", ["socket owner PID must be the PID", "Floor42 launched: WRONG_PROCESS"],
         "Residual", ["owner resolved per request; only the native", "claude install measured; none outside IAM"]),
        ("shell", "Agent shell compromised", ["boundary (3): code runs", "in the agent's sandbox"],
         "WSL sandbox", ["journal, vault, settings, other worktrees", "not mounted; network cut by default"],
         "Residual", ["kernel escape out of scope; unsafe mode", "or Docker: the shell can write the journal"]),
    ]
    items4 = [PILL("h1", "What the attacker has"), PILL("h2", "What stops it"), PILL("h3", "What remains")]
    grid4 = [["h1", "h2", "h3"]]
    edges4 = []
    for k, t1, s1, t2, s2, t3, s3 in rows4:
        items4 += [B(k + "a", t1, s1, "threat"), B(k + "c", t2, s2), B(k + "r", t3, s3, "residual")]
        grid4.append([k + "a", k + "c", k + "r"])
        edges4 += [E(k + "a", k + "c"), E(k + "c", k + "r", "", "flow")]
    p5 = Page("5 Attacks", "What an attacker gets at each boundary, what stops it, what remains",
              "Four attacks from docs/threat-model.md section 3; residual risk is what is left with the control on.",
              items4, grid4, edges4, gap=(60, 40), legend_edges=[])
    p6 = Page(
        "6 Revocation", "Revocation as a timeline: when each path stops an agent",
        "Bounds measured by RevocationGuaranteesTest against FakeKeycloak (docs/iam-revocation.md), not a real Keycloak.",
        [
            PILL("t0", "Next call (0 passes)"), PILL("t1", "Next pass (60 s + the pass)"),
            PILL("t2", "Second failed pass (about 2 min)"), PILL("t3", "300 s"), PILL("t4", "exp + 30 s (at most 8 h)"),
            B("a1", "1a Delegation revoked", ["in the UI, or the mission ends:", "next hook REVOKED, session stopped"], "director"),
            B("a2", "2a Agent revoked, dismissed", ["AgentClients.revokeLocally:", "hooks and broker, 0 passes"], "director"),
            B("a3", "Broker after a revoke", ["IDENTITY_REFUSED on the next", "call_resource, even Keycloak down"]),
            B("b1", "2b Client disabled at Keycloak", ["hooks: introspection active:false;", "broker: refused at once"], "keycloak"),
            B("b2", "3a Token inactive", ["active:false at Keycloak:", "that launch refused for good"], "keycloak"),
            B("b3", "Silent session", ["stopRevoked(), first step of", "a pass: no call needed"]),
            B("c1", "4 Keycloak unreachable", ["pass 1: no new session; pass 2:", "running ones KEYCLOAK_UNREACHABLE"], "keycloak"),
            B("c2", "6 floor42-api refused", ["IAM_API_CREDENTIAL_REFUSED;", "same limit as case 4"], "keycloak"),
            B("d1", "5 Broker token", ["at the resource until it expires,", "unless the resource introspects"], "secret"),
            B("e1", "3b Expiry", ["local check, no network;", "exp - iat over 8 h + 30 s refused"]),
            B("e2", "2c Secret rotated", ["revokes nothing: hooks run to exp;", "disable the client instead"], "keycloak"),
        ],
        [["t0", "t1", "t2", "t3", "t4"], ["a1", "b1", "c1", "d1", "e1"], ["a2", "b2", "c2", None, "e2"], ["a3", "b3", None, None, None]],
        [E("t0", "t1", "", "plain"), E("t1", "t2", "", "plain"), E("t2", "t3", "", "plain"), E("t3", "t4", "", "plain")],
        gap=(40, 30), legend_edges=[],
    )
    return [p1, p2, p3, p4, p5, p6]


# ---------------------------------------------------------------- 01 system context

def context():
    p1 = Page(
        "1 Outside view", "Floor42 and what it talks to",
        "One daemon on the director's machine; agents are Claude Code processes it starts and governs.",
        [
            B("browser", "Director's browser", ["Vue app served by the daemon"], "director"),
            B("daemon", "Floor42 daemon", ["one Quarkus process,", "127.0.0.1:7420 (dev mode 7421)"]),
            B("db", "Data folder", ["floor42.db (SQLite journal),", "sessions/<launch>/, iam/"], "store"),
            B("claude", "Claude Code sessions", ["one process per session,", "claude -p, stream-json"], "agent"),
            B("sandbox", "WSL sandbox", ["Bash only, rewritten", "by the hook"], "sandbox"),
            B("wt", "Agent worktrees", ["one git worktree and branch", "per mission and agent"], "store"),
            B("repo", "Director's repository", ["the checkout a mission names;", "agents never write to it"], "external"),
            B("kc", "Keycloak", ["realm floor42", "(IAM mode only)"], "keycloak"),
            B("res", "External resources", ["HTTPS APIs reached only", "through call_resource"], "external"),
            B("gh", "GitHub", ["only through the director's", "own gh CLI"], "external"),
        ],
        [["browser", "kc", "res"], ["daemon", "daemon", "gh"], ["db", "claude", "sandbox"], [None, "wt", "repo"]],
        [
            E("browser", "daemon", "HTTP, WebSocket"),
            E("daemon", "kc", "sign-in, exchange"),
            E("daemon", "res", "brokered call"),
            E("daemon", "gh", "gh pr create"),
            E("daemon", "db", "append, replay"),
            E("daemon", "claude", "starts, governs"),
            E("claude", "sandbox", "Bash"),
            E("claude", "wt", "file tools"),
            E("wt", "repo", "branches of", "flow"),
        ],
    )
    p2 = Page(
        "2 Inside the daemon", "The parts of the one daemon process",
        "Entry points on top, the single writer in the middle, what it drives below.",
        [
            B("browser", "Director's browser", [], "director"),
            B("claude", "Claude Code", ["agent session"], "agent"),
            G("entry", "Entry points", [["guard", "rest", "ws", "hooks"]]),
            B("guard", "Access guard", ["LocalAccessGuard"], "check"),
            B("rest", "REST API", ["api/*Resource"]),
            B("ws", "WebSockets", ["/ws/events, ..."]),
            B("hooks", "Hooks and MCP", ["HookResource, McpResource"], "check"),
            B("orch", "Orchestration", ["OfficeService, Dispatcher,", "MissionGovernor, SessionGate"]),
            B("dom", "Domain model", ["floor42-domain: Company,", "facades, events (JDK only)"]),
            B("rt", "Agent runtime", ["ClaudeCodeRuntime, HookRegistry,", "Sandbox, ShellGuard"]),
            B("iam", "Identity (IAM mode)", ["IamSessions, ResourceBroker,", "IdentityWatch, SecretVault"]),
            B("jr", "Journal", ["SqliteJournal, JournalBackups"]),
            B("db", "floor42.db", ["table event_journal,", "append-only"], "store"),
        ],
        [["browser", None, "claude", None], ["entry", "entry", "entry", "entry"], [None, "orch", "dom", None], ["rt", "iam", "jr", "db"]],
        [
            E("browser", "guard"), E("guard", "rest"), E("claude", "hooks", "Bearer token"),
            E("rest", "orch"), E("hooks", "orch", "decide"), E("orch", "dom", "execute"),
            E("orch", "rt"), E("orch", "iam"), E("orch", "jr", "append"), E("jr", "db", "SQL"),
            E("ws", "browser", "events", "flow"),
        ],
    )
    p3 = Page(
        "3 Planned (not built)", "Planned work: nothing on this page exists in the code",
        "Shown so that nobody reads it into the other diagrams, which draw only what the code holds at 1.17.",
        [
            B("cb", "Container broker", ["ADR-0065, Proposed: containers for", "missions; no container engine today"], "planned"),
            B("fga", "OpenFGA", ["relationship-based authorization,", "after public v1.0.0"], "planned"),
            B("split", "Security authority apart", ["hooks, broker, vault out of the", "orchestrator: after v1.0.0"], "planned"),
            B("linux", "Native Linux sandbox", ["none yet: on Linux use Docker", "(docs/docker.md) or unsafe mode"], "planned"),
        ],
        [["cb", "fga"], ["split", "linux"]],
    )
    return [p1, p2, p3]


# ---------------------------------------------------------------- 02 modules

def server_imports(root):
    srv = os.path.join(root, "floor42-server/src/main/java/dev/floor42/server")
    counts = {}
    for dp, _, fn in os.walk(srv):
        pkg = os.path.relpath(dp, srv).replace("\\", "/").split("/")[0]
        if pkg == ".":
            continue
        for f in fn:
            if f.endswith(".java"):
                text = open(os.path.join(dp, f), encoding="utf-8").read()
                for m in re.finditer(r"^import (?:static )?dev\.floor42\.(?:server\.(\w+)|(domain))", text, re.M):
                    t = m.group(1) or "domain"
                    if t != pkg:
                        counts[(pkg, t)] = counts.get((pkg, t), 0) + 1
    return counts


def web_imports(root):
    src = os.path.join(root, "floor42-web", "src")
    counts = {}
    for dp, _, fn in os.walk(src):
        for f in fn:
            if not re.search(r"\.(ts|vue)$", f) or ".test." in f:
                continue
            rel = os.path.relpath(os.path.join(dp, f), src).replace("\\", "/")
            layer = rel.split("/")[0] if "/" in rel else "src/*.ts"
            if f == "App.vue" or f == "main.ts":
                layer = "App.vue, main.ts"
            text = open(os.path.join(dp, f), encoding="utf-8").read()
            for m in re.finditer(r"""(?:from\s+|import\s*\(\s*)['"](\.[^'"]+)['"]""", text):
                trel = os.path.relpath(os.path.normpath(os.path.join(dp, m.group(1))), src).replace("\\", "/")
                tl = trel.split("/")[0] if "/" in trel else ("App.vue, main.ts" if trel in ("App.vue", "main", "main.ts") else "src/*.ts")
                if tl.startswith(".."):
                    continue
                if tl != layer:
                    counts[(layer, tl)] = counts.get((layer, tl), 0) + 1
    return counts


def matrix(name, title, shows, counts, order, note):
    rows = [o for o in order if any(k[0] == o for k in counts)]
    cols = [o for o in order if any(k[1] == o for k in counts)]
    cells = {(rows.index(a), cols.index(b)): v for (a, b), v in counts.items() if a in rows and b in cols}
    return Matrix(name, title, shows, rows, cols, cells, note)


def modules(root):
    p1 = Page(
        "1 Maven modules", "Three modules: a pure domain, the Quarkus daemon, the web app",
        "The domain knows only the JDK; the web app talks to the daemon over HTTP and WebSocket only.",
        [
            G("dom", "floor42-domain (Java 25, JDK only)", [["comp", "areas"], ["event", "wf"]]),
            B("comp", "Company and facades", ["Company (root), MissionsFacade,", "TicketsFacade, ActionsFacade, ..."]),
            B("areas", "Area models", ["MissionBook, TicketBoard, ActionLog,", "Desk, PermissionPolicy, ..."]),
            B("event", "event", ["EventEnvelope (Origin, Actor),", "sealed *Event interfaces"]),
            B("wf", "workflow", ["WorkflowInterpreter,", "MissionInterpreter"]),
            B("srv", "floor42-server (Quarkus)", ["packages api, app, iam, runtime,", "journal, security, config"]),
            B("web", "floor42-web (Vue 3, TypeScript)", ["built by Quinoa into the jar; checked", "against src/api/openapi.json"], "director"),
        ],
        [["web", "srv"], ["dom", "dom"]],
        [E("web", "srv", "HTTP, WebSocket"), E("srv", "dom", "imports")],
    )
    order = ["api", "app", "iam", "runtime", "journal", "security", "config", "domain"]
    p2 = matrix("2 Server imports", "Which server package imports which (counted in src/main)",
                "Each cell counts import lines from the row's package to the column's; dev.floor42.domain is the domain module.",
                server_imports(root), order,
                ["Counted from the sources when the diagrams are built. Empty cell: no import.",
                 "runtime and iam import each other; iam no longer imports app (it asks through OfficeFacts)."])
    rules = [
        ("domainDependsOnJdkOnly", ["dev.floor42.domain.. depends only on", "dev.floor42.domain.. and java.."]),
        ("runtimesDoNotReachIntoTheApplication", ["runtime.. may not depend on", "app.., api.., journal.."]),
        ("iamDoesNotReachIntoTheApplication", ["iam.. may not depend on app.., api..,", "journal..; it asks through OfficeFacts"]),
        ("journalIsLowLevel", ["journal.. may not depend on", "app.., api.., runtime.."]),
        ("apiIsALeaf", ["nothing outside api..", "depends on api.."]),
        ("securityIsIndependent", ["security.. may not depend on", "domain.., app.., journal.."]),
        ("domainClassesDoNotExtendDomainClasses", ["composition, not inheritance:", "DOMAIN_SUBCLASSES_ALLOWED is empty"]),
        ("threadsStartOnlyInTheScheduler", ["threads start in app.MissionScheduler", "and the classes of THREADS_ALLOWED"]),
        ("scene/boundaries.test.ts", ["Vitest: floor42-web/src/scene imports", "no vue, pinia, ../ui, ../components"]),
    ]
    items = [B(f"r{k}", t, s, "check") for k, (t, s) in enumerate(rules)]
    p3 = Page("3 ArchitectureTest rules", "The module boundaries the build enforces",
              "ArchUnit rules of floor42-server/.../ArchitectureTest.java, and one Vitest rule for the web app.",
              items, [["r0", "r1", "r2"], ["r3", "r4", "r5"], ["r6", "r7", "r8"]])
    worder = ["App.vue, main.ts", "components", "ui", "state", "api", "i18n", "scene", "demo", "src/*.ts"]
    p4 = matrix("4 Web imports", "Which folder of floor42-web/src imports which (test files left out)",
                "Each cell counts import statements from the row's folder to the column's; the scene imports types only.",
                web_imports(root), worder,
                ["Counted from the sources when the diagrams are built. The scene's imports of api are",
                 "import type only, and nothing in scene imports Vue (boundaries.test.ts)."])
    return [p1, p2, p3, p4]


# ---------------------------------------------------------------- 03 event model

def event_families(root):
    d = os.path.join(root, "floor42-domain/src/main/java/dev/floor42/domain/event")
    fams = {}
    for f in sorted(os.listdir(d)):
        if not f.endswith("Event.java") or f == "DomainEvent.java":
            continue
        fam = f[:-5]
        text = open(os.path.join(d, f), encoding="utf-8").read()
        names = re.findall(r"record\s+(\w+)\s*\((?:[^()]|\([^()]*\))*\)\s*implements\s+" + fam + r"\b", text)
        if not names:
            raise ValueError(f"no event record found in {f}")
        fams[fam] = names
    return fams


def events(root):
    p1 = Page(
        "1 Write path", "One command, one writer, one journal",
        "Every change goes through OfficeService.execute; the journal row is written before anyone hears of it.",
        [
            G("who", "Who sends a command", [["dirc"], ["agc"], ["rtc"], ["react"]]),
            B("dirc", "Director", ["a REST resource, e.g.", "MissionsResource"], "director"),
            B("agc", "Agent session", ["a tool call through HookResource", "or McpResource"], "agent"),
            B("rtc", "Runtime observation", ["SessionStream: tool result,", "session end (Actor SYSTEM)"]),
            B("react", "Floor42 reactions", ["EventReactions, Dispatcher,", "on MissionScheduler"]),
            B("exec", "OfficeService.execute", ["synchronized: one writer at a time;", "a refusal is a DomainException"]),
            B("fac", "Company and facades", ["check everything, then emit", "(MissionsFacade, ...)"]),
            B("env", "Recorder.emit", ["EventEnvelope: eventId, origin,", "actor, mission, causationId"]),
            B("mem", "Applied in memory", ["MissionBook.on, TicketBoard.on, ...", "(the projections)"]),
            B("app", "SqliteJournal.append", ["EventCodec writes the payload JSON"]),
            B("tab", "event_journal", ["append-only: triggers refuse", "UPDATE and DELETE"], "store"),
        ],
        [["who", "exec", "fac"], ["who", "app", "env"], ["who", "tab", "mem"]],
        [
            E("who", "exec", "command"), E("exec", "fac", "apply"), E("fac", "env", "emit"),
            E("env", "mem", "on(event)"), E("exec", "app", "append(events)"), E("app", "tab", "INSERT"),
        ],
    )
    p2 = Page(
        "2 After the append", "Who hears an event once it is stored",
        "OfficeService.publish runs after the append; the browser never reads the journal itself.",
        [
            B("pub", "OfficeService.publish", ["to every subscriber,", "after the append"]),
            B("es", "EventStream /ws/events", ["EventView.of(company, event);", "text by api/event renderers"]),
            B("br", "Browser: state/office.ts", ["on every (re)connection", "reload() reads the views"], "director"),
            B("views", "Read views", ["GET /api/...: MissionViews,", "TicketViews, ActionViews, ..."]),
            B("react", "EventReactions", ["event class to reactions;", "work forked onto MissionScheduler"]),
            B("gov", "MissionGovernor.awaitDecision", ["a hook call waiting for the", "director's answer wakes up"], "check"),
            B("exec", "OfficeService.execute", ["new commands"]),
        ],
        [["pub", "es", "br"], ["react", "gov", "views"], ["exec", None, None]],
        [
            E("pub", "es"), E("es", "br", "WebSocket", "flow"), E("br", "views", "GET"), E("pub", "react"),
            E("pub", "gov"), E("react", "exec", "new commands"),
        ],
    )
    p3 = Page(
        "3 Start and provenance", "The state is what replaying the journal gives",
        "At start the Company is rebuilt from event_journal; every row carries its Origin.",
        [
            B("tab", "event_journal", ["sequence, origin, actor_kind,", "event_type, payload, ..."], "store"),
            B("rep", "JournalReplay.run", ["journal.replay() rebuilds the Company;", "unreadable rows listed (ADR-0042)"]),
            B("cont", "JournalContinuity", ["a journal swapped or truncated", "between two runs raises an alarm"], "check"),
            B("comp", "Company", ["the in-memory state", "and its projections"]),
            B("orig", "EventEnvelope", ["refuses a null origin or actor;", "CHECK (origin IN ('SIMULATED','REAL'))"], "check"),
            B("sim", "SIMULATED", ["demo, practice and", "replayed missions"]),
            B("real", "REAL", ["live Claude Code sessions"]),
        ],
        [["tab", "rep", "comp"], ["cont", "orig", None], [None, "sim", "real"]],
        [E("tab", "rep", "ORDER BY sequence"), E("rep", "comp"), E("tab", "cont", "at start"), E("orig", "sim", "origin"), E("orig", "real", "origin")],
    )
    fams = event_families(root)
    total = sum(len(v) for v in fams.values())
    groups = [
        ("Missions and work", ["MissionEvent", "WorkflowEvent", "TicketEvent", "WorkbenchEvent", "DeliveryEvent"]),
        ("Actions, desk, sessions", ["ActionEvent", "DeskEvent", "SessionEvent", "TerminalEvent", "VerificationEvent"]),
        ("Team and deliberation", ["RosterEvent", "StaffingEvent", "CommitteeEvent", "CommitteeSeatEvent", "MeetingEvent", "MeetingRoomEvent", "RetroEvent"]),
        ("Company", ["NotebookEvent", "PreferenceEvent", "RecentProjectEvent"]),
    ]
    listed = [f for _, fs in groups for f in fs]
    missing = [f for f in fams if f not in listed]
    if missing:
        groups[-1][1].extend(missing)
    pages = [p1, p2, p3]
    for k, (gname, fs) in enumerate(groups, 4):
        cards = [CARD(f, f"{f} ({len(fams[f])})", fams[f]) for f in fs if f in fams]
        cols, col, h = [], [], 0
        for c in cards:
            ch = 40 + 13 * len(c.sub)
            if col and h + ch > 330:
                cols.append(col)
                col, h = [], 0
            col.append(c)
            h += ch + 30
        cols.append(col)
        n = sum(len(fams[f]) for f in fs if f in fams)
        pages.append(Table(f"{k} {gname}", f"Event catalogue: {gname.lower()} ({n} types)",
                           f"Read from floor42-domain/.../event/*Event.java at build time: {total} types in {len(fams)} families in all.",
                           cols))
    return pages


# ---------------------------------------------------------------- 04 authentication

def authentication():
    p1 = Page(
        "1 Access guard", "LocalAccessGuard: the checks before every route",
        "Transport checks apply to every request; credential checks to /api/* and /ws/* only.",
        [
            B("req", "Any request", ["browser, script or agent"], "director"),
            B("tr", "Transport checks", ["Host 127.0.0.1, localhost or [::1]; no", "X-Forwarded-*; Origin is the daemon"], "check"),
            B("path", "Path check", ["no ; and no encoded / or \\", "in the path"], "check"),
            B("open", "Open routes", ["/api/status and /auth/*:", "transport checks only"]),
            B("cred", "Director credential", ["cookie floor42_session, or Bearer:", "script token or director's token"], "check"),
            B("chg", "Cookie and a change", ["Origin and X-Floor42-Request: 1;", "a WebSocket names its Origin"], "check"),
            B("scope", "Scope", ["a script token: read, missions:write", "or all; /ws/terminal needs all"], "check"),
            B("route", "The route", ["api/*Resource, /ws/*"]),
            B("agent", "Agent token on /api", ["403: it opens /hooks and", "/mcp only"], "check"),
        ],
        [["req", "tr", "path", "open"], [None, "agent", "cred", "chg"], [None, None, "route", "scope"]],
        [
            E("req", "tr"), E("tr", "path"), E("path", "open", "/auth/*, status"), E("path", "cred", "/api, /ws"),
            E("cred", "chg", "cookie"), E("chg", "scope"), E("cred", "scope", "Bearer"), E("scope", "route"), E("cred", "agent", "agent token", "refuse"),
        ],
    )
    p2 = Page(
        "2 Director sign-in", "Two ways in for the director's browser",
        "The console link always works (in IAM mode it is the break-glass way); Keycloak sign-in in IAM mode.",
        [
            G("cl", "Console link", [["open", "redeem"]]),
            B("open", "POST /auth/open", ["DirectorSignIn: single-use link,", "60 s, opened or printed on stdout"]),
            B("redeem", "GET /auth?...", ["LocalAccessGuard.login; IAM mode:", "journaled as BreakGlassSignIn"]),
            G("kcg", "Keycloak sign-in (IAM mode)", [["kc", "cb"]], "keycloak"),
            B("kc", "GET /auth/keycloak", ["KeycloakSignIn: Authorization Code,", "PKCE S256, nonce, client floor42-desk"], "keycloak"),
            B("cb", "GET /auth/callback", ["IdTokenCheck: RS256, issuer keys,", "audience, nonce, 30 s skew"], "check"),
            B("sess", "SessionRegistry", ["cookie floor42_session; 8 h idle,", "24 h at most; memory only"]),
            B("dt", "DirectorTokens", ["the director's Keycloak tokens,", "memory only, never shown"]),
        ],
        [["cl", "sess"], ["kcg", "dt"]],
        [E("open", "redeem", "the link"), E("redeem", "sess"), E("kc", "cb", "login page"), E("cb", "sess"), E("cb", "dt")],
    )
    p3 = Page(
        "3 Script tokens", "Script tokens and the director's token",
        "Credentials for scripts: shown once, scoped, kept in memory; the journal keeps only a hash.",
        [
            B("tok", "POST /api/auth/tokens", ["issued from the desk, shown once", "(System > Sessions and tokens)"], "director"),
            B("reg", "SessionRegistry: script token", ["scope read, missions:write or all;", "30 days at most, gone at restart"]),
            B("j", "ApiTokenIssued", ["the journal keeps", "the SHA-256 only"], "store"),
            B("dtk", "Director's token", ["SessionToken: random at each", "start, memory only"], "secret"),
        ],
        [["tok", "reg", "j"], [None, "dtk", None]],
        [E("tok", "reg"), E("reg", "j", "journaled")],
    )
    p3b = Page(
        "4 Plan approval step-up", "The password step-up at plan approval (IAM mode)",
        "A script token or a break-glass session cannot approve a plan in IAM mode: no director token to delegate.",
        [
            B("ap", "POST .../approve", ["PlanApprovals.stepUp: a script token", "or break-glass session is refused"], "check"),
            B("rec", "StepUpApprovals.record", ["202 Accepted; pending 5 min, single", "use, bound to session and director"]),
            B("kc", "GET /auth/keycloak/approve", ["prompt=login, max_age=0,", "openid offline_access, PKCE"], "keycloak"),
            B("cb", "StepUpApprovals.callback", ["auth_time at or after the redirect;", "Delegations.grant, then approve"], "check"),
        ],
        [["ap", "rec"], ["cb", "kc"]],
        [E("ap", "rec"), E("rec", "kc", "redirect"), E("kc", "cb", "password again")],
    )
    p4 = Page(
        "5 Agent token minting", "How a session gets its identity before the process starts (IAM mode)",
        "SessionIdentity.mint; any failure is IdentityUnavailableException and the session does not start.",
        [
            B("pre", "Preconditions", ["IamStatus.blocksSessions, revoked", "agent, Delegations.blocks"], "check"),
            B("sub", "Subject token", ["work, retro: Delegations.subjectTokenFor;", "planning: Delegations.forPlanning"]),
            B("ex", "KeycloakClient.exchange", ["by floor42-agent-<id> (vault secret),", "audience floor42-api, floor42_* claims"], "keycloak"),
            B("map", "Keycloak mappers", ["ActorClaimMapper: act;", "Floor42ContextMapper: floor42"], "keycloak"),
            B("tok", "Session token", ["sub director, act agent, aud floor42-api,", "floor42 {launch, mission, ticket}"], "secret"),
            B("ver", "AgentTokenVerifier.verify", ["Floor42 never hands out a", "token it would refuse"], "check"),
            B("jr", "AgentTokenIssued", ["journaled: jti, issuer,", "expiresAt; never the token"], "store"),
            B("bind", "HookRegistry.bindProcess", ["FLOOR42_HOOK_TOKEN in the env;", "the PID kept in memory"], "agent"),
            NOTE("off", ["IAM mode off: HookRegistry mints a random token per session,", "dropped when the session ends; no Keycloak."]),
        ],
        [["pre", "sub", "ex", "map"], ["bind", "jr", "ver", "tok"], ["off", None, None, None]],
        [E("pre", "sub"), E("sub", "ex"), E("ex", "map"), E("map", "tok", "access token"), E("tok", "ver"), E("ver", "jr"), E("jr", "bind")],
    )
    p5 = Page(
        "6 Every agent call", "The four checks on every hook and MCP call (IAM mode)",
        "AgentAuth.check, then IamSessions.authenticate; no network call on this path.",
        [
            B("c1", "1. Local verification", ["AgentTokenVerifier, cached Jwks: RS256,", "issuer, aud, exp + 30 s, act, floor42"], "check"),
            B("c2", "2. Floor42's record", ["the live session of that launch:", "jti, azp, mission, ticket"], "check"),
            B("c3", "3. Revocation", ["inactive launch, revoked agent or", "delegation; failed background checks"], "check"),
            B("c4", "4. Process", ["SocketOwners: the socket owner PID", "must be the launched PID"], "check"),
            B("ok", "Accepted", ["to MissionGovernor.decide"]),
            B("r1", "Refused", ["MALFORMED, ISSUER_UNKNOWN,", "BAD_SIGNATURE, EXPIRED"], "check"),
            B("r2", "Refused", ["SESSION_ENDED, WRONG_AUDIENCE,", "WRONG_AGENT, WRONG_MISSION, WRONG_TICKET"], "check"),
            B("r3", "Refused", ["REVOKED, KEYCLOAK_UNREACHABLE,", "KEYCLOAK_REFUSED"], "check"),
            B("r4", "Refused", ["WRONG_PROCESS"], "check"),
        ],
        [["c1", "c2", "c3", "c4", "ok"], ["r1", "r2", "r3", "r4", None]],
        [E("c1", "c2"), E("c2", "c3"), E("c3", "c4"), E("c4", "ok"),
         E("c1", "r1", "", "refuse"), E("c2", "r2", "", "refuse"), E("c3", "r3", "", "refuse"), E("c4", "r4", "", "refuse")],
    )
    return [p1, p2, p3, p3b, p4, p5]


# ---------------------------------------------------------------- 05 IAM sequences

def sequences():
    s1 = Seq("1 Plan approval", "Sequence 1: the plan approval creates the mission delegation",
             "IAM mode. One password step-up per approval gives each mission its own offline session (ADR-0064).",
             [("br", "Director's browser", [], "director"), ("ma", "MissionsResource", ["PlanApprovals"], "floor42"),
              ("su", "StepUpApprovals", ["KeycloakSignIn"], "floor42"), ("kc", "Keycloak", ["realm floor42"], "keycloak"),
              ("de", "Delegations", ["SecretVault"], "secret"), ("os", "OfficeService", ["Company, journal"], "floor42")],
             [("br", "ma", "POST /api/missions/{id}/approve", "call"),
              ("ma", "su", "record: pending 5 min, single use", "call"),
              ("ma", "br", "202 Accepted", "flow"),
              ("br", "su", "GET /auth/keycloak/approve", "call"),
              ("su", "br", "redirect: prompt=login, max_age=0, PKCE", "flow"),
              ("br", "kc", "the director types the password again", "call"),
              ("kc", "br", "redirect to /auth/callback", "flow"),
              ("br", "su", "GET /auth/callback", "call"),
              ("su", "kc", "redeemCode(code, verifier)", "call"),
              ("kc", "su", "offline refresh token", "flow"),
              ("su", "su", "auth_time at or after the redirect", "call"),
              ("su", "de", "grant: token in the vault, 30 days", "call"),
              ("su", "os", "PlanApprover.approve: MissionPlanApproved", "call")],
             [["Any failure leaves the mission awaiting approval and drops the pending approval."]])
    s2 = Seq("2 Session token", "Sequence 2: a session gets its identity token",
             "IAM mode. Any failure: IdentityUnavailableException, the session does not start. No fallback.",
             [("ga", "SessionGate", ["Dispatcher"], "floor42"), ("rt", "ClaudeCodeRuntime", [], "floor42"),
              ("si", "SessionIdentity", [], "floor42"), ("de", "Delegations", ["SecretVault"], "secret"),
              ("kc", "Keycloak", [], "keycloak"), ("ve", "AgentTokenVerifier", [], "check"),
              ("cc", "Claude Code", ["agent process"], "agent")],
             [("ga", "rt", "run(SessionSpec, MissionGovernor)", "call"),
              ("rt", "rt", "no sandbox: the session fails (ADR-0063)", "call"),
              ("rt", "si", "mint(spec, launch = fresh UUID)", "call"),
              ("si", "si", "blocksSessions, revoked agent, Delegations.blocks", "call"),
              ("si", "de", "subjectTokenFor(mission)", "call"),
              ("de", "kc", "refresh the offline token (floor42-desk)", "call"),
              ("kc", "si", "the director's access token", "flow"),
              ("si", "kc", "exchange by floor42-agent-<id>, aud floor42-api", "call"),
              ("kc", "si", "sub director, act agent, exp <= 8 h", "flow"),
              ("si", "ve", "verify(token)", "call"),
              ("si", "rt", "Minted: AgentTokenIssued journaled (jti)", "flow"),
              ("rt", "cc", "start: env FLOOR42_HOOK_TOKEN; bindProcess(pid)", "call")])
    s3 = Seq("3 Hook call", "Sequence 3: a hook call is checked",
             "IAM mode. No network call on the hook path: keys are cached, Keycloak is asked by the background pass.",
             [("cc", "Claude Code", [], "agent"), ("hr", "HookResource", ["AgentAuth"], "check"),
              ("is", "IamSessions", [], "floor42"), ("ve", "AgentTokenVerifier", ["Jwks"], "check"),
              ("so", "SocketOwners", [], "check"), ("gv", "MissionGovernor", ["ActionLog"], "floor42")],
             [("cc", "hr", "POST /hooks/pre-tool-use, Bearer token", "call"),
              ("hr", "is", "authenticate(token, remote, local)", "call"),
              ("is", "ve", "verify: signature, issuer, aud, exp", "call"),
              ("ve", "is", "claims, jti, or a refusal", "flow"),
              ("is", "is", "compare with the live session; revocation flags", "call"),
              ("is", "so", "owner(remote, local)", "call"),
              ("so", "is", "PID, or empty: refused", "flow"),
              ("is", "hr", "Accepted(governor, claims) or Refused", "flow"),
              ("hr", "gv", "decide(call): ActionRequested, the table", "call"),
              ("gv", "hr", "ALLOW, DENY or PENDING", "flow"),
              ("hr", "cc", "allow or deny (updatedInput for Bash)", "flow")],
             [["Refused: 401 TOKEN_REFUSED and the hook blocks the call. Without IAM mode, steps 2 to 8 are a lookup",
               "of the session's random token in HookRegistry."]])
    s4 = Seq("4 call_resource", "Sequence 4: call_resource through the ResourceBroker",
             "The agent never holds a credential for the resource; Floor42 makes the one call itself.",
             [("cc", "Claude Code", [], "agent"), ("hr", "HookResource", [], "check"),
              ("mc", "McpResource", [], "check"), ("rb", "ResourceBroker", [], "floor42"),
              ("ca", "ResourceCatalogue", ["SafeUrls, PinnedClients"], "check"), ("kc", "Keycloak", [], "keycloak"),
              ("ex", "External resource", ["HTTPS"], "external")],
             [("cc", "hr", "PreToolUse for call_resource", "call"),
              ("hr", "cc", "allow: ActionAuthorized", "flow"),
              ("cc", "mc", "POST /mcp tools/call call_resource", "call"),
              ("mc", "mc", "governor.authorized(toolUseId), once", "call"),
              ("mc", "rb", "call(caller claims, resource, ...)", "call"),
              ("rb", "ca", "both grants; base + allowed prefix", "call"),
              ("ca", "rb", "address resolved once, checked, pinned", "flow"),
              ("rb", "kc", "Token Exchange for the resource audience", "call"),
              ("kc", "rb", "token, at most 300 s (checked)", "flow"),
              ("rb", "ex", "one call to the pinned IP, no redirect", "call"),
              ("ex", "rb", "status, headers, body", "flow"),
              ("rb", "mc", "scrubbed, capped BrokerResult", "flow"),
              ("mc", "cc", "text quoted as UNTRUSTED DATA", "flow")],
             [["Refusals: RESOURCE_UNKNOWN, RESOURCE_NOT_GRANTED, METHOD_NOT_ALLOWED, PATH_NOT_ALLOWED,",
               "IDENTITY_REFUSED, IDENTITY_UNAVAILABLE, RESOURCE_UNREACHABLE. A resource marked local",
               "(plain HTTP on loopback) gets no Host pinning and no SNI check (ADR-0064 known limits)."]])
    s5 = Seq("5 Revocation", "Sequence 5: revocation paths and the IdentityWatch pass",
             "Local revocations act on the next call; Keycloak-side changes on the next pass (60 s plus the pass).",
             [("dr", "Director", ["UI"], "director"), ("fl", "Floor42", ["Delegations, AgentClients"], "floor42"),
              ("is", "IamSessions", [], "floor42"), ("iw", "IdentityWatch", ["60 s between passes"], "floor42"),
              ("kc", "Keycloak", [], "keycloak"), ("cc", "Claude Code", ["running session"], "agent")],
             [("dr", "fl", "revoke the delegation, or the mission ends", "call"),
              ("fl", "fl", "Delegations.revokeLocally: a flag in memory", "call"),
              ("cc", "is", "next hook or MCP call", "call"),
              ("is", "cc", "REVOKED; the session is stopped", "flow"),
              ("fl", "kc", "revokeRemote (RFC 7009), retried", "call"),
              ("dr", "fl", "revoke an agent, or dismiss it", "call"),
              ("fl", "kc", "AgentClients: the client is disabled", "call"),
              ("iw", "is", "stopRevoked(): sessions that make no call", "call"),
              ("iw", "kc", "JWKS refresh, probeIntrospection", "call"),
              ("iw", "kc", "introspect each live session token", "call"),
              ("kc", "iw", "active:false: that launch refused for good", "flow"),
              ("iw", "is", "no answer twice: refuse running sessions", "call")],
             [["A pass asks Keycloak once per live session (10 s timeout each) and stops at a 50 s deadline",
               "(IdentityWatch.DEADLINE): the gap between passes is at most 120 s. Bounds: 10-agent-security, page 6."]])
    return [s1, s2, s3, s4, s5]


# ---------------------------------------------------------------- 06 lifecycle state machines

def lifecycle():
    mission = Page(
        "1 Mission", "Mission states (MissionStatus, MissionBook)",
        "Allowed moves are those of MissionStatus.canMoveTo; arrow colour says who moves it.",
        [START(), S("plan", "PLANNING", ["the planner proposes tickets"]), S("wait", "AWAITING_APPROVAL", ["plan on the desk"]),
         S("run", "IN_PROGRESS", ["the dispatcher runs the work"]), S("susp", "SUSPENDED", ["no new session starts"]),
         S("done", "COMPLETED", ["a retrospective follows"]), S("canc", "CANCELLED"), END("e1"), END("e2")],
        [["start", "plan", "wait", None], [None, "canc", "run", "susp"], [None, "e2", "done", "e1"]],
        [
            E("start", "plan", "MissionCreated", "dir"), E("plan", "wait", "MissionPlanSubmitted", "agent"),
            E("wait", "run", "MissionPlanApproved", "dir"), E("wait", "plan", "MissionPlanRejected", "dir"),
            E("run", "susp", "MissionSuspended (or an incident)", "dir"), E("susp", "run", "MissionResumed", "dir"),
            E("run", "done", "MissionCompleted (never an agent)", "dir"), E("run", "canc", "MissionCancelled", "dir"),
            E("susp", "canc", "MissionCancelled", "dir"), E("plan", "canc", "MissionCancelled", "dir"),
            E("done", "e1", "", "plain"), E("canc", "e2", "", "plain"),
        ],
        gap=(90, 70),
    )
    ticket = Page(
        "2 Ticket", "Ticket states (TicketStatus, TicketBoard)",
        "A reviewer who is not the producer decides; a rejection at its cap goes to the director instead.",
        [START("s1"), START("s2"), S("prop", "PROPOSED", ["ticket validation on"]), S("todo", "TODO"),
         S("prog", "IN_PROGRESS", ["a developer session"]), S("rev", "IN_REVIEW", ["a reviewer, not the producer"]),
         S("done", "DONE"), S("canc", "CANCELLED"), B("q", "Question to the director", ["escalation or rejection cap"], "director")],
        [["s1", "prop", "todo", "s2"], [None, "canc", "prog", "rev"], [None, None, "done", "q"]],
        [
            E("s1", "prop", "created by the planner", "agent"), E("s2", "todo", "created by the director", "dir"),
            E("prop", "todo", "TicketApproved", "dir"), E("todo", "prog", "TicketStarted", "f42"),
            E("prog", "rev", "TicketSubmittedForReview", "agent"), E("rev", "done", "VALIDATED, TicketCompleted", "agent"),
            E("rev", "prog", "REJECTED, TicketReopened", "agent"), E("rev", "q", "QuestionAsked", "f42"),
            E("done", "prog", "TicketReopened", "dir"), E("prog", "todo", "TicketReleased", "f42"),
            E("todo", "canc", "TicketCancelled", "dir"), E("prop", "canc", "TicketCancelled", "dir"),
        ],
        gap=(90, 70),
    )
    action = Page(
        "3 Action", "An agent's tool call as an action (ActionLog, ApprovalPolicy)",
        "Checks before the table: MissionGovernor.violation (Confinement, ShellGuard), then ActionLog.decide.",
        [START(), S("req", "REQUESTED", ["ActionRequested at the hook"]), B("q", "Question on the desk", ["ASK: once per mission; ASK_ALWAYS:", "every call (shell: per command)"], "director"),
         S("auth", "AUTHORIZED"), S("den", "DENIED"), S("exe", "EXECUTED"), S("fail", "FAILED"), S("conf", "CONFIRMED"),
         B("inc", "Incident", ["a tool ran without authorization:", "IncidentRaised, MissionSuspended"], "check")],
        [["start", "req", "q", None], ["inc", "den", "auth", "fail"], [None, None, "exe", "conf"]],
        [
            E("start", "req", "tool call", "agent"), E("req", "auth", "table ALLOW", "f42"), E("req", "q", "ASK, ASK_ALWAYS", "f42"),
            E("q", "auth", "allow", "dir"), E("q", "den", "deny", "dir"), E("req", "den", "ActionDenied", "f42"),
            E("auth", "exe", "ActionExecuted", "f42"), E("auth", "fail", "ActionFailed", "f42"),
            E("exe", "conf", "ActionConfirmed", "f42"), E("req", "inc", "succeeded, never authorized", "f42"),
        ],
        gap=(90, 70),
    )
    node = Page(
        "4 Graph node", "A node in graph mode (ADR-0062): a ticket with dependencies, risk, tier and tests",
        "One rework per node, counted across the review, every check and failed node tests together.",
        [START(), S("prop", "PROPOSED", ["planner: at most 8 nodes"]), S("todo", "TODO", ["NodePlanned at the approval"]),
         S("prog", "IN_PROGRESS", ["once its dependencies are done"]), B("nt", "Node tests", ["approved commands on a copy,", "sandbox, offline: ChecksRan"]),
         S("rev", "IN_REVIEW", ["high risk: checks too"]), S("done", "DONE"), B("rc", "To recheck (derived)", ["a dependency was", "reopened since"], "check"),
         B("q", "Question to the director", ["second rejection"], "director")],
        [["start", "prop", "todo", "prog"], ["q", "rev", "nt", None], [None, "done", "rc", None]],
        [
            E("start", "prop", "TicketCreated", "agent"), E("prop", "todo", "TicketApproved", "dir"), E("todo", "prog", "TicketStarted", "f42"),
            E("prog", "nt", "delivered", "agent"), E("nt", "rev", "TicketSubmittedForReview", "f42"), E("rev", "done", "TicketCompleted", "agent"),
            E("rev", "prog", "first rejection", "agent"), E("rev", "q", "second rejection", "f42"), E("done", "rc", "", "f42"),
        ],
        gap=(90, 70),
    )
    proof = Page(
        "5 Proof of delivery", "Proof of delivery (ADR-0058): Floor42 runs, only the director gives the verdict",
        "Routes under /api/missions/{id}/proof; runs use copies of the worktrees, in the sandbox, network cut.",
        [START(), S("none", "No proof yet", ["mission at acceptance"]), S("ran", "Ran", ["ProofRan"]), S("wit", "Witnessed", ["ProofStepWitnessed"]),
         S("ver", "Verdict", ["ProofVerdictGiven: PROVEN", "or NOT_PROVEN"]), S("acc", "Accepted", ["MissionCompleted"]),
         S("unp", "Accepted unproven", ["UnprovenDeliveryAccepted"])],
        [["start", "none", "ran", "wit"], [None, "unp", "ver", None], [None, None, "acc", None]],
        [
            E("start", "none", "", "plain"), E("none", "ran", "proof/run", "dir"), E("ran", "wit", "proof/witness", "dir"),
            E("ran", "ver", "proof/verdict", "dir"), E("wit", "ver", "proof/verdict", "dir"), E("ver", "acc", "complete", "dir"),
            E("ver", "ran", "send-back, run again", "dir"), E("none", "unp", "complete without proof", "dir"),
        ],
        gap=(90, 70),
    )
    retro = Page(
        "6 Retrospective", "End-of-mission retrospective (ADR-0020)",
        "Retrospective sessions are short and have no tool at all; only the director makes a lesson binding.",
        [START(), S("open", "Open", ["RetroOpened: a real mission ended"]), S("contrib", "Contributions", ["RetroContributed, once per agent"]),
         S("conc", "Concluded", ["RetroConcluded by the chair"]), S("prop", "Lesson PROPOSED", ["LessonProposed"]),
         S("acc", "ACCEPTED", ["reaches later briefs"]), S("rej", "REJECTED"), S("ret", "RETIRED", ["in no brief any more"])],
        [["start", "open", "contrib", "conc"], [None, "ret", "acc", "prop"], [None, None, None, "rej"]],
        [
            E("start", "open", "", "f42"), E("open", "contrib", "participants' sessions", "f42"),
            E("contrib", "contrib", "RetroSkipped", "f42"), E("contrib", "conc", "chair session", "agent"),
            E("conc", "prop", "", "agent"), E("prop", "acc", "LessonAccepted", "dir"), E("prop", "rej", "LessonRejected", "dir"),
            E("acc", "ret", "LessonRetired", "dir"),
        ],
        gap=(90, 70),
    )
    return [mission, ticket, action, node, proof, retro]


# ---------------------------------------------------------------- 07 agent session runtime

def runtime():
    p1 = Page(
        "1 Starting a session", "From the dispatcher to a running Claude Code process",
        "The brief comes from the journal, never from a chat; the process gets its hook and MCP config from files.",
        [
            B("disp", "Dispatcher", ["one ticket per developer; reviews to", "a free reviewer who did not produce it"]),
            B("run", "TaskRunner", ["ProduceRunner, ReviewRunner,", "PlanRunner, CommitteeRunner, ..."]),
            B("brief", "Briefs", ["from the journal at launch: objective,", "criteria, permissions, decisions"]),
            B("gate", "SessionGate", ["the mission's holds first; then", "the session runs under MissionGovernor"], "check"),
            B("rt", "ClaudeCodeRuntime.run", ["refuses without the sandbox; IAM:", "identity minted first"], "check"),
            B("proc", "claude -p", ["--restricted --strict-mcp-config", "--max-budget-usd; brief on stdin"], "agent"),
            B("set", "settings.json", ["PreToolUse hook: matcher *, type http,", "/hooks/pre-tool-use, onFailure block"], "store"),
            B("mcp", "mcp.json", ["mcpServers.floor42: http /mcp,", "Bearer ${FLOOR42_HOOK_TOKEN}"], "store"),
        ],
        [["disp", "run", "brief", "gate"], [None, None, "proc", "rt"], [None, None, "set", "mcp"]],
        [E("disp", "run"), E("run", "brief"), E("brief", "gate"), E("gate", "rt"), E("rt", "proc", "starts"),
         E("rt", "set", "writes"), E("rt", "mcp", "writes")],
    )
    p2 = Page(
        "2 One tool call", "Every tool call goes through the PreToolUse hook",
        "api/HookResource, app/MissionGovernor and the domain's ActionLog decide; Claude Code runs only what is allowed.",
        [
            B("cc", "Claude Code", ["a tool call"], "agent"),
            B("auth", "AgentAuth.check", ["the session's token", "(IAM mode: four checks)"], "check"),
            B("vio", "MissionGovernor.violation", ["Confinement: file tools in the worktree;", "ShellGuard: no data folder, push, gh"], "check"),
            B("dec", "ActionLog.decide", ["ActionRequested; mission not active", "or not on the ticket: ActionDenied"], "check"),
            B("pol", "PermissionPolicy", ["ALLOW, ASK (once), ASK_ALWAYS,", "DENY; Floor42 tools: authorized"], "check"),
            B("wait", "Waiting for the director", ["QuestionAsked; up to the hook", "timeout minus 15 s, then deny"], "director"),
            G("does", "What the authorized call does", [["files", "bash", "mcpt"]]),
            B("files", "File tools", ["Read, Edit, Write, Glob, Grep", "in the worktree"], "agent"),
            B("bash", "Bash in the sandbox", ["SessionTally.updatedInput:", "Sandbox.wrapForShell"], "sandbox"),
            B("mcpt", "Floor42 MCP tools", ["ask_director,", "call_resource (broker)"]),
        ],
        [["cc", "auth", "vio", "dec"], [None, "wait", None, "pol"], ["does", "does", "does", "does"]],
        [E("cc", "auth"), E("auth", "vio"), E("vio", "dec"), E("dec", "pol"), E("pol", "wait", "ASK"),
         E("pol", "does", "allow"), E("wait", "does", "allowed")],
    )
    p3 = Page(
        "3 What Floor42 records", "What the session did is recorded by Floor42, never by the agent",
        "The process's stdout is parsed into runtime events, then journaled (Origin REAL for a live session).",
        [
            B("cc", "Claude Code stdout", ["stream-json lines"], "agent"),
            B("p", "StreamJsonParser", ["ToolCompleted, AgentText,", "SessionEnded, PlanUsageReported"]),
            B("s", "SessionStream", ["governor.observed: the result", "of each authorized call"]),
            B("ev", "Journaled events", ["AgentSessionStarted, ActionExecuted,", "ActionFailed, AgentRemarked, ..."], "store"),
            B("mask", "SecretMasker", ["parsed stream lines masked", "with one pattern set"]),
        ],
        [["cc", "p", "s", "ev"], [None, "mask", None, None]],
        [E("cc", "p"), E("p", "s"), E("s", "ev"), E("p", "mask", "masks")],
    )
    fc = [
        ("No sandbox, real mission", ["SessionTally.startRefusal: the session", "fails before any process (ADR-0063)"]),
        ("No identity (IAM mode)", ["IdentityUnavailableException in", "SessionIdentity.mint: no session"]),
        ("Hook unreachable", ["settings.json onFailure block:", "Claude Code does not run the tool"]),
        ("Token refused", ["401 TOKEN_REFUSED with the reason;", "the hook blocks the call"]),
        ("Director too slow", ["awaitDecision returns PENDING:", "the hook answers deny"]),
        ("Tool ran unauthorized", ["IncidentRaised (CRITICAL)", "and MissionSuspended"]),
        ("call_resource, no hook", ["governor.authorized(toolUseId)", "false: NOT_AUTHORIZED"]),
        ("Reviewer shell unwrapped", ["SessionTally.updatedInput throws:", "never handed back unwrapped"]),
        ("Session silent too long", ["SessionProcesses.watch stops", "the whole process tree"]),
        ("Cost cap reached", ["Claude Code's own --max-budget-usd", "stops the session (ADR-0025)"]),
        ("Daemon restarted", ["ClaudeCodeRuntime.stopLeftovers", "stops the last daemon's processes"]),
        ("Journal write fails", ["JournalException: the Company is", "rebuilt from what is stored"]),
    ]
    items = [B(f"f{k}", t, s, "check") for k, (t, s) in enumerate(fc)]
    p4 = Page("4 Fail-closed paths", "When something is missing or goes wrong, the session stops or is refused",
              "Twelve paths; none of them falls back to allowing the call.",
              items, [[f"f{k}" for k in range(r * 4, r * 4 + 4)] for r in range(3)], gap=(40, 40))
    return [p1, p2, p3, p4]


# ---------------------------------------------------------------- 08 UI model

def ui():
    p1 = Page(
        "1 Screen", "The web app on screen (floor42-web/src/App.vue)",
        "The top bar opens panels over the 3D office; a panel shows a list and the selected item beside it.",
        [
            B("bar", "TopBar", ["buttons from ui/actions.ts:", "work, team, system"], "director"),
            G("hud", "HUD (div.hud), mounted once signed in", [["desk", "board", "work"], ["ids", "prog", "end"], ["other", "other", "around"]]),
            B("desk", "Desk: DirectorDesk", ["questions waiting: permissions,", "plans, escalations"], "director"),
            B("board", "Board: BoardPanel", ["tickets of the mission;", "task graph in graph mode"], "director"),
            B("work", "Workshop: WorkshopPanel", ["worktrees, diffs, checks,", "accept or reject a patch"], "director"),
            B("ids", "IdentitiesPanel", ["agent clients, delegations,", "tokens, refusals (IAM)"], "director"),
            B("prog", "ProgressPanel, EventLog", ["what happened, line by line,", "with its origin"], "director"),
            B("end", "MissionEnd, ProvePanel", ["acceptance, proof,", "objections, retrospective"], "director"),
            B("other", "Other panels", ["ui/panelMounts.ts: ResumePanel, LibraryPanel,", "CostsPanel, SettingsPanel, ApiConsole, ..."], "director"),
            B("around", "Around the panels", ["ToastStack, CommandPalette,", "ModalDialogs, banners"], "director"),
            B("scene", "3D office <canvas>", ["scene/OfficeScene.ts (three.js), built", "by ui/useOfficeScene.ts; no Vue"]),
        ],
        [["bar", "scene"], ["hud", "hud"]],
        [E("bar", "hud", "opens a panel")],
    )
    p2 = Page(
        "2 State and data flow", "How the UI reads the API and the event stream",
        "A missed message is not a lost fact: on every connection the store reloads its state through REST.",
        [
            B("comp", "components/*.vue", ["read the stores, call", "their commands"], "director"),
            B("office", "state/office.ts", ["reactive store: missions, agents,", "questions, log; reload(), command()"]),
            B("ctx", "Other stores", ["state/context.ts, drafts.ts,", "viewer.ts, ..."]),
            B("client", "api/client.ts, request.ts", ["fetch, same-origin cookie,", "X-Floor42-Request: 1"]),
            B("es", "api/eventStream.ts", ["WebSocket, reconnect with", "capped backoff"]),
            B("rest", "REST /api/*", ["MissionView, TicketView, ..."], "external"),
            B("ws", "WebSockets", ["/ws/events, /ws/context"], "external"),
            B("scn", "ui/useOfficeScene.ts", ["onLiveEvent: live events", "move the figures"]),
            B("scene", "scene/OfficeScene.ts", ["draws what it is told;", "imports no Vue"]),
            B("types", "api/types*.ts, contract.ts", ["checked against openapi.json", "at compile time"], "check"),
        ],
        [["comp", "office", "client", "rest"], ["ctx", "es", None, "ws"], ["scn", "scene", "types", None]],
        [
            E("comp", "office", "read, commands"), E("office", "client", "GET, POST"), E("client", "rest", "HTTP"),
            E("office", "es", "start()"), E("es", "ws", "connect"), E("ws", "es", "EventView", "flow"),
            E("es", "office", "reload()", "flow"), E("office", "scn", "onLiveEvent"), E("scn", "scene"),
            E("ctx", "es", "/ws/context"), E("client", "types", "", "flow"),
        ],
    )
    return [p1, p2]


# ---------------------------------------------------------------- 09 API map

WRITE_ROUTES = [re.compile(r) for r in (
    r"/api/missions",
    r"/api/missions/[^/]+/(approve|reject|nudge|tickets/approve|workshop/directive)",
    r"/api/questions/[^/]+/answer",
)]


def scope_of(method, path):
    """As LocalAccessGuard.allowedByScope: read for GET/HEAD/OPTIONS, missions:write for MISSION_WRITES, else all."""
    if method.upper() in ("GET", "HEAD", "OPTIONS"):
        return "r"
    concrete = re.sub(r"\{[^}]+\}", "x", path)
    return "w" if any(r.fullmatch(concrete) for r in WRITE_ROUTES) else "a"


def api(root):
    spec = json.load(open(os.path.join(root, "floor42-web/src/api/openapi.json"), encoding="utf-8"))
    tags = {}
    for path, ops in spec["paths"].items():
        for m, op in ops.items():
            for t in op.get("tags", ["(none)"]):
                tags.setdefault(t, []).append((path, m.upper()))
    director = {t: v for t, v in tags.items() if t not in ("Hook", "Status")}
    nd = sum(len(v) for v in director.values())
    hook = tags.get("Hook", [])
    p1 = Page(
        "1 Who may call what", "API map: who may call what",
        "Routes from floor42-web/src/api/openapi.json, read when the diagrams are built; LocalAccessGuard decides.",
        [
            B("dir", "Director", ["cookie floor42_session,", "or a script token (Bearer)"], "director"),
            B("ag", "Agent session", ["its own token (Bearer)"], "agent"),
            B("any", "Anyone on the machine", ["no credential"], "external"),
            B("guard", "LocalAccessGuard", ["transport checks on every route;", "/api and /ws need a director credential"], "check"),
            B("api", f"Director routes /api/* ({nd})", [f"{len(director)} tags; scopes on the next pages:", "[r] read, [w] missions:write, [a] all"]),
            B("ws", "WebSockets /ws/*", ["/ws/events, /ws/context, /ws/api-calls;", "/ws/terminal/{id}: scope all + ticket"]),
            B("hook", f"Hook ({len(hook)})", ["POST /hooks/pre-tool-use,", "GET and POST /mcp; never /api"], "check"),
            B("st", "GET /api/status", ["open: the mode banner", "shows before sign-in"]),
            B("auth", "Sign-in routes /auth/*", ["/auth, /auth/open, /auth/keycloak,", "/auth/callback: transport checks only"]),
        ],
        [["dir", "guard", "api"], ["ag", "hook", "ws"], ["any", "st", "auth"]],
        [E("dir", "guard"), E("guard", "api"), E("guard", "ws"), E("ag", "hook", "Bearer"), E("ag", "guard", "403 on /api", "refuse"),
         E("any", "st"), E("any", "auth")],
    )
    cards = []
    for t in sorted(director, key=lambda t: (-len(director[t]), t)):
        lines = [f"[{scope_of(m, p)}] {m:<6} {p}" for p, m in sorted(director[t], key=lambda pm: (pm[0], pm[1]))]
        cards.append(CARD(t.replace(" ", "-"), f"{t} ({len(lines)})", lines, "floor42", mono=True))
    pages = [p1]
    budget = 640
    cur, col, h = [], [], 0
    batches = []
    for c in cards:
        ch = 30 + 13 * len(c.sub)
        if ch > budget:  # a long tag gets a page of its own, split into columns
            if col:
                cur.append(col)
            if cur:
                batches.append(cur)
            per = (budget - 30) // 13
            n = -(-len(c.sub) // per)
            per = -(-len(c.sub) // n)
            parts = [c.sub[k:k + per] for k in range(0, len(c.sub), per)]
            batches.append([[CARD(f"{c.id}{k}", c.title + (f", part {k + 1}" if len(parts) > 1 else ""), part, "floor42", True)]
                            for k, part in enumerate(parts)])
            cur, col, h = [], [], 0
            continue
        if h + ch > budget:
            cur.append(col)
            col, h = [], 0
            if len(cur) == 3:
                batches.append(cur)
                cur = []
        col.append(c)
        h += ch + 30
    if col:
        cur.append(col)
    if cur:
        batches.append(cur)
    for k, cols in enumerate(batches, 2):
        names = ", ".join(c.title.split(" (")[0] for col in cols for c in col if "part" not in c.title or c.title.endswith("part 1"))
        pages.append(Table(f"{k} Director routes {k - 1}", f"Director routes, part {k - 1} of {len(batches)}",
                           f"{names}. [r] scope read, [w] missions:write, [a] all only.",
                           cols))
    return pages


def files(root):
    return [
        ("10-agent-security-iam", security()),
        ("01-system-context", context()),
        ("02-modules", modules(root)),
        ("03-event-model", events(root)),
        ("04-authentication", authentication()),
        ("05-iam-delegation-and-broker", sequences()),
        ("06-mission-lifecycle", lifecycle()),
        ("07-agent-session-runtime", runtime()),
        ("08-ui-model", ui()),
        ("09-api-map", api(root)),
    ]
