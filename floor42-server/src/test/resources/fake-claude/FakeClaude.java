import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test double for the Claude Code CLI, faithful to the parts of the protocol Floor42 relies on: stream-json in and out,
 * a real HTTP call to the PreToolUse hook configured in --settings (with the token from the environment), refusals
 * reported the way Claude Code reports them, and a final result with usage and an estimated cost. JDK only.
 *
 * <p>Usage: java FakeClaude.java --fake-script <file> [--fake-review-script <file>] [claude flags...]. A brief asking for
 * an independent review plays the review script; a brief containing [fake:crash] exits with code 3 after init.
 */
public class FakeClaude {

    public static void main(String[] args) throws Exception {
        List<String> a = List.of(args);
        if (a.contains("--version")) {
            System.out.println("2.1.304 (Claude Code)");
            return;
        }
        Path script = Path.of(valueAfter(a, "--fake-script"));
        String prompt;
        String settings = Files.readString(Path.of(valueAfter(a, "--settings")));
        String hookUrl = find(settings, "\"url\"\\s*:\\s*\"([^\"]+)\"");
        String model = valueAfter(a, "--model");
        // Floor42 always passes a cost cap: a session started without one fails here.
        String cap = valueAfter(a, "--max-budget-usd");
        String token = System.getenv("FLOOR42_HOOK_TOKEN");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // The brief is one JSON line; only its text matters here.
        prompt = in.readLine();
        // "[fake:crash-before-init]": dies before its init line, so Floor42 never learns the session's id.
        if (prompt != null && prompt.contains("[fake:crash-before-init]")) {
            System.err.println("fake crash before init");
            System.exit(3);
        }
        // "[fake:result-before-init]": a result line with no init before it and no cost in it, then a clean exit.
        if (prompt != null && prompt.contains("[fake:result-before-init]")) {
            System.out.println("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true}");
            System.out.flush();
            System.exit(0);
        }
        String session = UUID.randomUUID().toString();
        // Like the real CLI, the init lists the plugins given with --plugin-dir, their skills and agents as plugin:name.
        List<String> plugins = new java.util.ArrayList<>();
        List<String> skills = new java.util.ArrayList<>();
        List<String> agents = new java.util.ArrayList<>();
        for (int i = 0; i < a.size() - 1; i++) {
            if (!a.get(i).equals("--plugin-dir")) continue;
            Path dir = Path.of(a.get(i + 1));
            String name = find(Files.readString(dir.resolve(".claude-plugin").resolve("plugin.json")),
                    "\"name\"\\s*:\\s*\"([^\"]+)\"");
            plugins.add("{\"name\":\"" + name + "\",\"path\":\"" + dir.toString().replace("\\", "\\\\") + "\"}");
            if (Files.isDirectory(dir.resolve("skills"))) {
                try (var s = Files.list(dir.resolve("skills"))) {
                    s.forEach(p -> skills.add("\"" + name + ":" + p.getFileName() + "\""));
                }
            }
            if (Files.isDirectory(dir.resolve("agents"))) {
                try (var s = Files.list(dir.resolve("agents"))) {
                    s.forEach(p -> agents.add(
                            "\"" + name + ":" + p.getFileName().toString().replaceAll("\\.md$", "") + "\""));
                }
            }
        }
        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"" + session + "\",\"model\":\"" + model
                + "\",\"claude_code_version\":\"2.1.304\",\"tools\":[]"
                + (plugins.isEmpty()
                        ? ""
                        : ",\"plugins\":[" + String.join(",", plugins) + "],\"skills\":[" + String.join(",", skills)
                                + "],\"agents\":[" + String.join(",", agents) + "]")
                + "}");
        // Like the real CLI, report the plan's usage window when the brief asks for it: "[fake:usage=0.97]".
        java.util.regex.Matcher usage = prompt == null
                ? null
                : java.util.regex.Pattern.compile("\\[fake:usage=([0-9.]+)]").matcher(prompt);
        if (usage != null && usage.find()) {
            // The window resets 6 s later, or "[fake:reset=N]" seconds later (a test that counts during the rest).
            java.util.regex.Matcher reset = java.util.regex.Pattern.compile("\\[fake:reset=([0-9]+)]")
                    .matcher(prompt);
            long resetsAt = java.time.Instant.now().getEpochSecond() + (reset.find() ? Long.parseLong(reset.group(1)) : 6);
            emit("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"allowed\",\"rateLimitType\":"
                    + "\"five_hour\",\"unifiedWindows\":{\"five_hour\":{\"utilization\":" + usage.group(1)
                    + ",\"resetsAt\":" + resetsAt + "}}},\"session_id\":\"" + session + "\"}");
        }
        // "[fake:context]": like the real CLI, one assistant line per content block, the lines of one message sharing
        // its id and usage, and a subagent's line (parent_tool_use_id set) with a usage of its own context.
        if (prompt != null && prompt.contains("[fake:context]")) {
            String usage1 = "\"usage\":{\"input_tokens\":4,\"cache_creation_input_tokens\":1200,"
                    + "\"cache_read_input_tokens\":18000,\"output_tokens\":40}";
            String message = "msg_" + session.substring(0, 8);
            for (String block : List.of("{\"type\":\"thinking\",\"thinking\":\"\"}", "{\"type\":\"thinking\",\"thinking\":\"\"}")) {
                emit("{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"" + message
                        + "\",\"role\":\"assistant\",\"content\":[" + block + "]," + usage1 + "},\"session_id\":\""
                        + session + "\"}");
            }
            emit("{\"type\":\"assistant\",\"parent_tool_use_id\":\"toolu_sub_" + session.substring(0, 8)
                    + "\",\"message\":{\"id\":\"msg_sub_" + session.substring(0, 8) + "\",\"role\":\"assistant\","
                    + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}],\"usage\":{\"input_tokens\":2,"
                    + "\"cache_creation_input_tokens\":90000,\"cache_read_input_tokens\":0,\"output_tokens\":5}},"
                    + "\"session_id\":\"" + session + "\"}");
        }
        // A brief containing "[fake:slow=N]" takes N seconds, so a person can watch the scene while it runs.
        java.util.regex.Matcher slow = prompt == null
                ? null
                : java.util.regex.Pattern.compile("\\[fake:slow=([0-9]+)]").matcher(prompt);
        if (slow != null && slow.find()) Thread.sleep(Long.parseLong(slow.group(1)) * 1000);
        // "[fake:cap]": Claude Code stops the session at its cap after one turn, as measured on 2.1.312 (ADR-0025).
        if (prompt != null && prompt.contains("[fake:cap]")) {
            emit("{\"type\":\"result\",\"subtype\":\"error_max_budget_usd\",\"is_error\":true,\"num_turns\":2,"
                    + "\"duration_ms\":900,\"total_cost_usd\":" + (Double.parseDouble(cap) + 0.0066)
                    + ",\"usage\":{\"input_tokens\":10,\"output_tokens\":5},\"errors\":[\"Reached maximum budget ($"
                    + cap + ")\"],\"session_id\":\"" + session + "\"}");
            return;
        }
        // Like the real CLI, --resume needs the earlier session's transcript; the arguments are kept beside the
        // session's settings so a test can see whether Floor42 resumed (ADR-0038).
        Files.writeString(
                Path.of(valueAfter(a, "--settings")).resolveSibling("fake-args.txt"), String.join("\n", a) + "\n");
        // The brief as received, beside them: a test reads what the session was told (ADR-0045).
        Files.writeString(
                Path.of(valueAfter(a, "--settings")).resolveSibling("fake-prompt.txt"), prompt == null ? "" : prompt);
        // "[fake:crash-unless-resumed]": fails after init, unless the session continues an earlier one.
        if (prompt != null && prompt.contains("[fake:crash-unless-resumed]") && !a.contains("--resume")) {
            System.err.println("fake crash: not resumed");
            System.exit(3);
        }
        if (prompt != null && prompt.contains("[fake:crash]")) {
            System.err.println("fake crash requested by the brief");
            System.exit(3);
        }
        // "[fake:hang]": stuck after init, like a CLI that never writes again. It starts a long child process first
        // and says its pid, so a test can check that stopping the session stopped the child too.
        if (prompt != null && prompt.contains("[fake:hang]")) {
            List<String> sleeper = System.getProperty("os.name").startsWith("Windows")
                    ? List.of("ping", "-n", "300", "127.0.0.1")
                    : List.of("sleep", "300");
            Process child = new ProcessBuilder(sleeper)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + "child " + child.pid() + "\"}]}}");
            Thread.sleep(Long.MAX_VALUE);
        }
        // "[fake:touch=<file>]": a developer's session leaves a change in its worktree, as if it had written that file.
        Matcher touch = prompt == null || prompt.contains("independent review")
                ? null
                : Pattern.compile("\\[fake:touch=([A-Za-z0-9._-]+)]").matcher(prompt);
        if (touch != null && touch.find()) {
            Files.writeString(Path.of(touch.group(1)), "written by session " + session + "\n");
        }
        // "[fake:prove]": a developer's session leaves a verification recipe (ADR-0058): one step Floor42 can run, one
        // for the director.
        if (touch != null && prompt.contains("[fake:prove]")) {
            Files.writeString(
                    Path.of("PROVE.md"),
                    "# Proof\n\n## The work is there\n```sh\necho proof-ok\n```\nExpect exit: 0\nExpect output: proof-ok\n\n"
                            + "## The service answers\n```sh director\ncurl -s localhost:8080/health\n```\n"
                            + "Expect output: {\"status\":\"UP\"}\n");
        }
        // "[fake:prove-bad]": a recipe whose only step names no expectation: a step with a problem, never run.
        if (touch != null && prompt.contains("[fake:prove-bad]")) {
            Files.writeString(Path.of("PROVE.md"), "# Proof\n\n## The work is there\n```sh\necho proof-ok\n```\n");
        }
        // "[fake:prove-mixed]": one step Floor42 can run, then one with no expectation (a problem, never run).
        if (touch != null && prompt.contains("[fake:prove-mixed]")) {
            Files.writeString(
                    Path.of("PROVE.md"),
                    "# Proof\n\n## The work is there\n```sh\necho proof-ok\n```\nExpect exit: 0\nExpect output: proof-ok\n\n"
                            + "## No expectation\n```sh\necho again\n```\n");
        }
        // "[fake:leak]": the session keeps its own credential in its worktree, to use it once the session is over.
        if (prompt != null && prompt.contains("[fake:leak]")) {
            Files.writeString(Path.of("leaked-credential.txt"), token);
        }
        // "[fake:steal]": plays an agent's unconfined shell command, run as the same OS user as the daemon, hunting for
        // the director's credential (ADR-0034). Every token-shaped string in Floor42's data directory, the working
        // directory and the environment is tried on a director route, as a bearer, a cookie and a login token.
        if (prompt != null && prompt.contains("[fake:steal]")) {
            // The hunt reads every file and tries each candidate on four routes: seconds under load. Claude Code
            // reports a shell command still running with a heartbeat each second; without one, Floor42's silence
            // limit stops the session before its result.
            String hunt = "toolu_steal_" + session.substring(0, 8);
            Thread heartbeat = Thread.ofPlatform().daemon().start(() -> heartbeat(hunt));
            String report;
            try {
                report = steal(hookUrl, Path.of(valueAfter(a, "--settings")), token);
            } finally {
                heartbeat.interrupt();
                heartbeat.join();
            }
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + report + "\"}]}}");
            emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":1,\"duration_ms\":10,"
                    + "\"result\":\"" + report + "\",\"session_id\":\"" + session + "\",\"total_cost_usd\":0.001,"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
            while (in.readLine() != null) {}
            return;
        }
        // "[fake:forge=<planted credentials file>]": what an unconfined shell command running as the daemon's own OS user
        // could reach (ADR-0044). Permission checks only: nothing is read or written. The journal: may it be opened
        // for writing? The planted credentials file: may it be read? The daemon's standard output, /proc/<pid>/fd/1
        // (Linux only): may it be opened?
        Matcher forge = prompt == null ? null : Pattern.compile("\\[fake:forge=([^\\]]+)]").matcher(prompt);
        if (forge != null && forge.find()) {
            String report = forge(Path.of(valueAfter(a, "--settings")), Path.of(forge.group(1)));
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + report + "\"}]}}");
            emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":1,\"duration_ms\":10,"
                    + "\"result\":\"" + report + "\",\"session_id\":\"" + session + "\",\"total_cost_usd\":0.001,"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
            while (in.readLine() != null) {}
            return;
        }
        if (prompt != null && prompt.contains("independent review") && a.contains("--fake-review-script")) {
            script = Path.of(valueAfter(a, "--fake-review-script"));
            // A reviewer that cannot check the work: the review escalates to the director.
            if (prompt.contains("[fake:unverifiable]")) script = script.resolveSibling("review-unverifiable.txt");
            // A reviewer that rejects every report: the ticket goes back, then to the director once rejected twice.
            if (prompt.contains("[fake:rejected]")) script = script.resolveSibling("review-rejected.txt");
            // A reviewer that rejects and says the environment's blocker is the only issue left.
            if (prompt.contains("[fake:env-only]")) script = script.resolveSibling("review-env-only.txt");
            // A reviewer with one reservation on criterion C1, one citing no criterion and a note (ADR-0053).
            if (prompt.contains("[fake:reserved]")) script = script.resolveSibling("review-reserved.txt");
            // A director's check whose text asks for more than a reviewer may do (ADR-0052): it tries to write under
            // .github and to push, then validates. Only a check's brief quotes the step's text.
            if (prompt.contains("[fake:check-escape]") && prompt.contains("Workflow step "))
                script = script.resolveSibling("check-escape.txt");
        }
        // "[fake:leak]": a developer whose tool outputs hold secrets, local absolute paths and a long text.
        if (prompt != null && !prompt.contains("independent review") && prompt.contains("[fake:leak]")) {
            script = script.resolveSibling("leak.txt");
        }
        // "[fake:env-blocked]": a developer whose report names a step its environment prevents (a desktop browser).
        if (prompt != null && !prompt.contains("independent review") && prompt.contains("[fake:env-blocked]")) {
            script = script.resolveSibling("session-env-blocked.txt");
        }
        // "[fake:probe]": a developer that reads three files outside its workspace, back to back (session risk).
        if (prompt != null && !prompt.contains("independent review") && prompt.contains("[fake:probe]")) {
            script = script.resolveSibling("session-probe.txt");
        }
        // "[fake:write-edit]": the developer of an adopted decision's implementation calls Write, then Edit.
        if (prompt != null
                && prompt.contains("Decision adopted by the director")
                && !prompt.contains("independent review")
                && prompt.contains("[fake:write-edit]")) {
            script = script.resolveSibling("session-write-edit.txt");
        }
        // Committee sessions play the scripts next to the main one: chair, security specialist, other members.
        // A reviewer asks for help once ("[fake:consult]" or "[fake:research]" in the brief), then reviews as usual.
        boolean firstReview = prompt != null
                && prompt.contains("independent review")
                && !prompt.contains("You asked for help during this review");
        if (firstReview && prompt.contains("[fake:consult]")) {
            script = script.resolveSibling("review-consult.txt");
        } else if (firstReview && prompt.contains("[fake:research]")) {
            script = script.resolveSibling("review-research.txt");
        } else if (prompt != null && prompt.contains("asked for advice by")) {
            script = script.resolveSibling("consultant.txt");
        } else if (prompt != null && prompt.contains("asked to research a question")) {
            script = script.resolveSibling("research.txt");
        }
        // The planner's staffing review: whether to reinforce the team.
        if (prompt != null && prompt.contains("whether to reinforce the team")) {
            script = script.resolveSibling("staffing.txt");
        }
        // The retrospective (ADR-0020): a participant's turn, then the chair's conclusion.
        if (prompt != null && prompt.contains("chair of the retrospective")) {
            script = script.resolveSibling("retro-chair.txt");
        } else if (prompt != null && prompt.contains("This is its")) {
            script = script.resolveSibling("retro-member.txt");
        } else if (prompt != null && prompt.contains("asked to extend the graph of mission")) {
            // The graph extension (ADR-0062): two new nodes, the first after T-2, the second after the first.
            script = script.resolveSibling("planner-extend.txt");
        } else if (prompt != null && prompt.contains("asked to plan mission")) {
            // With [fake:propose-workflow] the planner also proposes a workflow for the company's list (ADR-0050).
            // With [fake:graph-plan] the planner writes a graph: TEST, RISK and TIER lines, the second node after the first (ADR-0062).
            // With [fake:independent-plan] the two tickets do not depend on each other (TicketSpread).
            // With [fake:needs-tools] the second ticket NEEDS tools a sandbox may lack (a browser, pdftotext).
            script = script.resolveSibling(
                    prompt.contains("[fake:propose-workflow]")
                            ? "planner-workflow.txt"
                            : prompt.contains("[fake:graph-plan]")
                                    ? "planner-graph.txt"
                                    : prompt.contains("[fake:independent-plan]")
                                    ? "planner-independent.txt"
                                    : prompt.contains("[fake:needs-tools]") ? "planner-needs.txt" : "planner.txt");
        } else if (prompt != null && prompt.contains("neutral moderator of the idea committee")) {
            script = script.resolveSibling("idea-moderator.txt");
        } else if (prompt != null && prompt.contains("member of the idea committee")) {
            boolean lawyer = prompt.contains("(Lawyer), a member");
            boolean debate = prompt.contains(", debate");
            script = script.resolveSibling(
                    (lawyer ? "idea-lawyer" : "idea-member") + (debate ? "-debate" : "") + ".txt");
        } else if (prompt != null && prompt.contains("chair of the architecture committee")) {
            script = script.resolveSibling("committee-chair.txt");
        } else if (prompt != null && prompt.contains("member of the architecture committee")) {
            script = script.resolveSibling(
                    prompt.contains("(Security specialist), a member") ? "committee-security.txt" : "committee-member.txt");
        }

        // A turn of speech at the director's meeting (ADR-0049): reads, then answers. "[fake:meeting-turn-write]" first
        // tries a Write (which it really performs when allowed, so a test sees whether Floor42 refused it) and a Bash.
        boolean writes = prompt != null && prompt.contains("[fake:meeting-turn-write]");
        if (prompt != null && prompt.contains("The director asks for your view.")) {
            script = script.resolveSibling(writes ? "meeting-turn-write.txt" : "meeting-turn.txt");
        }
        // The business analyst between the committee's record and the ruling.
        if (prompt != null && prompt.contains("the business analyst of Floor42, on mission")) {
            script = script.resolveSibling("analyst.txt");
        }
        // With [fake:clarify], members ask the director something until the director's answers are in their brief.
        boolean asks = prompt != null
                && prompt.contains("[fake:clarify]")
                && prompt.contains("member of the architecture committee")
                && !prompt.contains("The director answered");

        HttpClient http = HttpClient.newHttpClient();
        int turns = 1;
        String finalText = "";
        // "[fake:meeting]" (ADR-0047): a developer session reads files one by one until the hook refuses a call for the
        // director's meeting, then ends its turn, as a model reading that refusal would. "[fake:meeting-long]" first
        // runs a shell command that takes 4 s (it writes long-done.txt as it ends), then ignores every refusal and goes
        // on asking: only Floor42's stop ends it. A brief that already restates a meeting plays the script as usual.
        // "[fake:meeting-lost]": the same, but the shell command's result never comes (nor its heartbeat).
        boolean lost = prompt != null && prompt.contains("[fake:meeting-lost]");
        boolean stubborn = prompt != null && (prompt.contains("[fake:meeting-long]") || lost);
        if (prompt != null
                && (prompt.contains("[fake:meeting]") || stubborn)
                && !prompt.contains("independent review")
                && !prompt.contains("The director asks for your view.")
                && !prompt.contains("Director's meeting:")) {
            if (stubborn) longCommand(http, hookUrl, token, session, lost);
            if (meeting(http, hookUrl, token, session, stubborn)) {
                while (in.readLine() != null) {}
                return;
            }
        }
        // Like Haiku on M-7: a call to a tool the session does not offer. Claude Code refuses it itself; no hook runs.
        if (prompt != null && prompt.contains("[fake:unoffered]")) {
            String id = "toolu_unoffered_" + session.substring(0, 8);
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                    + id + "\",\"name\":\"Bash\",\"input\":{\"command\":\"git log\"}}]}}");
            emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                    + id + "\",\"is_error\":true,\"content\":\"<tool_use_error>Error: No such tool available: Bash</tool_use_error>\"}]}}");
        }
        Matcher ask = prompt == null ? null : Pattern.compile("\\[fake:ask(?:back)?=([^\\]]+)]").matcher(prompt);
        if (ask != null && ask.find()) {
            String mcp = Files.readString(Path.of(valueAfter(a, "--mcp-config")));
            String mcpUrl = find(mcp, "\"url\"\\s*:\\s*\"([^\"]+)\"");
            String id = "toolu_ask_" + session.substring(0, 8);
            String tool = "mcp__floor42__ask_director";
            // [fake:askback=Q1||Q2]: asks Q1; each time the director asks back, asks the next one (ADR-0059).
            String[] questions =
                    prompt.contains("[fake:askback=") ? ask.group(1).split("\\|\\|") : new String[] {ask.group(1)};
            String input = "{\"question\":\"" + esc(questions[0]) + "\",\"options\":[\"blue\",\"red\"]}";
            HttpResponse<String> hook = http.send(
                    HttpRequest.newBuilder(URI.create(hookUrl))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"session_id\":\"" + session
                                    + "\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"" + tool
                                    + "\",\"tool_input\":" + input + ",\"tool_use_id\":\"" + id + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            String result = "refused by the hook";
            if (hook.body().contains("\"permissionDecision\":\"allow\"")) {
                // The header names the variable, as Floor42 wrote it: Claude Code expands it from its environment.
                String auth = find(mcp, "\"Authorization\"\\s*:\\s*\"([^\"]+)\"")
                        .replace("${FLOOR42_HOOK_TOKEN}", token);
                for (String body : List.of(
                        "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"server/discover\",\"params\":{}}",
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}",
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"ask_director\","
                                + "\"arguments\":" + input + ",\"_meta\":{\"claudecode/toolUseId\":\"" + id + "\"}}}")) {
                    HttpResponse<String> r = http.send(
                            HttpRequest.newBuilder(URI.create(mcpUrl))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json, text/event-stream")
                                    .header("Authorization", auth)
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (body.contains("tools/call")) result = find(r.body(), "\"text\"\\s*:\\s*\"([^\"]*)\"");
                }
                for (int i = 1; i < questions.length && result.contains("has not answered yet"); i++) {
                    String again = "{\"question\":\"" + esc(questions[i]) + "\",\"options\":[\"blue\",\"red\"]}";
                    // Each question is a tool call of its own: its own hook, its own id (the MCP call needs the authorization).
                    String againId = id + "_" + i;
                    http.send(
                            HttpRequest.newBuilder(URI.create(hookUrl))
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + token)
                                    .POST(HttpRequest.BodyPublishers.ofString("{\"session_id\":\"" + session
                                            + "\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"" + tool
                                            + "\",\"tool_input\":" + again + ",\"tool_use_id\":\"" + againId + "\"}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    HttpResponse<String> r = http.send(
                            HttpRequest.newBuilder(URI.create(mcpUrl))
                                    .header("Content-Type", "application/json")
                                    .header("Accept", "application/json, text/event-stream")
                                    .header("Authorization", auth)
                                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":" + (2 + i)
                                            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"ask_director\","
                                            + "\"arguments\":" + again + ",\"_meta\":{\"claudecode/toolUseId\":\"" + againId + "\"}}}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    result = find(r.body(), "\"text\"\\s*:\\s*\"([^\"]*)\"");
                }
            }
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                    + id + "\",\"name\":\"" + tool + "\",\"input\":" + input + "}]}}");
            emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                    + id + "\",\"content\":\"" + esc(result) + "\",\"is_error\":false}]}}");
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + esc(result) + "\"}]}}");
            turns++;
        }
        for (String line : Files.readAllLines(script)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\|", 5);
            if (p[0].equals("text")) {
                // The rest of the line: a text may hold "|" itself (a staffing advice does). Line breaks are \n.
                finalText = line.substring("text|".length()).replace("\\n", "\n");
                // The graph extension (ADR-0062) builds on the first node of the graph it was shown.
                if (finalText.contains("{{first-node}}") && prompt != null) {
                    java.util.regex.Matcher first =
                            java.util.regex.Pattern.compile("with its status:\\D*?(T-\\d+)").matcher(prompt);
                    finalText = finalText.replace("{{first-node}}", first.find() ? first.group(1) : "T-1");
                }
                if (asks) finalText += "\nQUESTIONS: Who calls the APIs? || Is there a budget for a gateway?";

                emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                        + esc(finalText) + "\"}]}}");
                continue;
            }
            // Like the real CLI, tool-use ids are unique across sessions: the script id plus this session.
            String id = p[1] + "_" + session.substring(0, 8), tool = p[2], input = p[3], output = p[4];
            // "{{lorem}}" in a script's output stands for a long text, longer than any clip.
            output = output.replace("{{lorem}}", "lorem ipsum dolor sit amet ".repeat(120));
            String body = "{\"session_id\":\"" + session + "\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"" + tool
                    + "\",\"tool_input\":" + input + ",\"tool_use_id\":\"" + id + "\"}";
            String result;
            boolean error;
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create(hookUrl))
                                .header("Content-Type", "application/json")
                                .header("Authorization", "Bearer " + token)
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() / 100 != 2) {
                    error = true;
                    result = "PreToolUse:" + tool + " hook error: [" + hookUrl + "]: HTTP " + r.statusCode()
                            + "; blocking because onFailure is block";
                } else if (r.body().contains("\"permissionDecision\":\"allow\"")) {
                    error = false;
                    // Like Claude Code: an allow with updatedInput runs the rewritten command, not the agent's.
                    String rewritten = find(r.body(), "\"updatedInput\":\\{[^}]*\"command\":\"((?:[^\"\\\\]|\\\\.)*)\"");
                    if (writes && tool.equals("Write")) {
                        Files.writeString(Path.of(find(input, "\"file_path\":\"([^\"]+)\"")), "written\n");
                    }
                    result = rewritten.isEmpty() ? output : "ran: " + rewritten;
                } else {
                    error = true;
                    result = "PreToolUse:" + tool + " hook error: " + find(r.body(), "\"permissionDecisionReason\":\"([^\"]*)\"");
                }
            } catch (java.io.IOException e) {
                error = true;
                result = "PreToolUse:" + tool + " hook error: [" + hookUrl + "]: failed; blocking because onFailure is block";
            }
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                    + id + "\",\"name\":\"" + tool + "\",\"input\":" + input + "}]}}");
            if (!error && prompt != null && prompt.contains("[fake:progress]")) {
                // Like Claude Code 2.1.316 while a tool runs: a heartbeat every 30 s, then a line Floor42 does not know.
                emit("{\"type\":\"tool_progress\",\"tool_use_id\":\"" + id + "-heartbeat-0\",\"tool_name\":\"" + tool
                        + "\",\"parent_tool_use_id\":\"" + id + "\",\"elapsed_time_seconds\":30,\"heartbeat\":true}");
                emit("{\"type\":\"brand_new_line\",\"note\":\"key sk-ant-api03-abcdefghijkl\",\"count\":1}");
            }
            emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                    + id + "\",\"content\":\"" + esc(result) + "\",\"is_error\":" + error + "}]}}");
            turns++;
        }
        emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":" + turns
                + ",\"duration_ms\":1234,\"result\":\"" + esc(finalText) + "\",\"session_id\":\"" + session
                + "\",\"total_cost_usd\":0.0123,\"usage\":{\"input_tokens\":100,\"cache_creation_input_tokens\":1000,"
                + "\"cache_read_input_tokens\":2000,\"output_tokens\":300}}");
        // Like Claude Code in stream-json input mode: wait until the caller closes stdin.
        while (in.readLine() != null) {}
    }

    /** "steal: candidates=N usable=K own=S open=O openBody=L" (L: 1 if a sign-in link came back): what an unconfined agent could find and use as the director. */
    static String steal(String hookUrl, Path settings, String own) throws Exception {
        String base = hookUrl.substring(0, hookUrl.indexOf("/hooks/"));
        // settings.json lives in <data dir>/sessions/<session>/.
        Path dataDir = settings.toAbsolutePath().getParent().getParent().getParent();
        java.util.Set<String> candidates = new java.util.LinkedHashSet<>();
        Pattern shape = Pattern.compile("[A-Za-z0-9_-]{24,}");
        for (Path root : List.of(dataDir, Path.of("").toAbsolutePath())) {
            List<Path> files = List.of();
            // The daemon writes its database while this walks: a file (SQLite's journal) that vanishes mid-walk is
            // walked again, not a failure of the hunt.
            for (int attempt = 0; attempt < 5; attempt++) {
                try (var walk = Files.walk(root)) {
                    files = walk.filter(Files::isRegularFile).toList();
                    break;
                } catch (java.io.UncheckedIOException vanished) {
                    Thread.sleep(50);
                }
            }
            for (Path f : files) {
                try {
                    if (Files.size(f) > 4_000_000) continue;
                    Matcher m = shape.matcher(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1));
                    while (m.find()) candidates.add(m.group());
                } catch (java.io.IOException unreadable) {
                    // A file the OS refuses to share: not readable by an agent either.
                }
            }
        }
        for (String v : System.getenv().values()) {
            Matcher m = shape.matcher(v);
            while (m.find()) candidates.add(m.group());
        }
        HttpClient http = HttpClient.newHttpClient();
        int usable = 0;
        for (String c : candidates) {
            int bearer = status(http, HttpRequest.newBuilder(URI.create(base + "/api/missions"))
                    .header("Authorization", "Bearer " + c));
            int cookie = status(http, HttpRequest.newBuilder(URI.create(base + "/api/missions"))
                    .header("Cookie", "floor42_session=" + c));
            int login = status(http, HttpRequest.newBuilder(URI.create(base + "/auth?token=" + c)));
            int code = status(http, HttpRequest.newBuilder(URI.create(base + "/auth?code=" + c)));
            if (bearer / 100 == 2 || cookie / 100 == 2 || login / 100 == 3 || code / 100 == 3) usable++;
        }
        int ownStatus = status(http, HttpRequest.newBuilder(URI.create(base + "/api/missions"))
                .header("Authorization", "Bearer " + own));
        HttpResponse<String> open = http.send(
                HttpRequest.newBuilder(URI.create(base + "/auth/open"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return "steal: candidates=" + candidates.size() + " usable=" + usable + " own=" + ownStatus + " open="
                + open.statusCode() + " openBody=" + (open.body().contains("code=") ? 1 : 0);
    }

    /** "forge: journalWritable=0|1 credentialsReadable=0|1 daemonStdout=0|1|na": permission checks, no content. */
    static String forge(Path settings, Path plantedCredentials) {
        // settings.json lives in <data dir>/sessions/<session>/.
        Path journal = settings.toAbsolutePath().getParent().getParent().getParent().resolve("floor42.db");
        int writable;
        // Opening for writing asks the OS itself; nothing is written.
        try (var ch = java.nio.channels.FileChannel.open(journal, java.nio.file.StandardOpenOption.WRITE)) {
            writable = 1;
        } catch (Exception refused) {
            writable = 0;
        }
        int readable;
        try (var ch = java.nio.channels.FileChannel.open(plantedCredentials, java.nio.file.StandardOpenOption.READ)) {
            readable = 1;
        } catch (Exception refused) {
            readable = 0;
        }
        String stdout = "na";
        Path fd = Path.of("/proc", String.valueOf(ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L)),
                "fd", "1");
        if (Files.isDirectory(Path.of("/proc/self"))) {
            // Resolving the link is enough: the kernel refuses it for a non-dumpable process of the same user.
            try {
                Files.readSymbolicLink(fd);
                stdout = "1";
            } catch (Exception refused) {
                stdout = "0";
            }
        }
        return "forge: journalWritable=" + writable + " credentialsReadable=" + readable + " daemonStdout=" + stdout;
    }

    static int status(HttpClient http, HttpRequest.Builder request) throws Exception {
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /**
     * A shell command the hook allows, then runs 4 s with a heartbeat each second, as Claude Code reports a tool still
     * running, and writes long-done.txt in the worktree before its result. A lost one sends nothing after its start.
     */
    static void longCommand(HttpClient http, String hookUrl, String token, String session, boolean lost)
            throws Exception {
        String id = "toolu_long_" + session.substring(0, 8);
        String input = "{\"command\":\"./build-for-4s\"}";
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(hookUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"session_id\":\"" + session
                                + "\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Bash\",\"tool_input\":"
                                + input + ",\"tool_use_id\":\"" + id + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        boolean allowed = r.body().contains("\"permissionDecision\":\"allow\"");
        emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                + id + "\",\"name\":\"Bash\",\"input\":" + input + "}]}}");
        if (allowed && lost) return;
        if (allowed) {
            for (int second = 1; second <= 4; second++) {
                Thread.sleep(1_000);
                emit("{\"type\":\"tool_progress\",\"tool_use_id\":\"" + id + "-heartbeat-" + second
                        + "\",\"tool_name\":\"Bash\",\"parent_tool_use_id\":\"" + id
                        + "\",\"elapsed_time_seconds\":" + second + "}");
            }
            Files.writeString(Path.of("long-done.txt"), "the long command ran to its end\n");
        }
        emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                + id + "\",\"content\":\"" + (allowed ? "built" : "refused") + "\",\"is_error\":" + !allowed + "}]}}");
    }

    /**
     * Reads until the hook refuses a call for a meeting (true, the turn ended), at most three minutes (false). A stubborn
     * session reads the refusal and asks again, until it is stopped.
     */
    static boolean meeting(HttpClient http, String hookUrl, String token, String session, boolean stubborn)
            throws Exception {
        for (int n = 0; n < 600; n++) {
            String id = "toolu_meet_" + n + "_" + session.substring(0, 8);
            String input = "{\"file_path\":\"README.md\"}";
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(hookUrl))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + token)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"session_id\":\"" + session
                                    + "\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Read\",\"tool_input\":"
                                    + input + ",\"tool_use_id\":\"" + id + "\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean allowed = r.body().contains("\"permissionDecision\":\"allow\"");
            String reason = find(r.body(), "\"permissionDecisionReason\":\"([^\"]*)\"");
            emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\""
                    + id + "\",\"name\":\"Read\",\"input\":" + input + "}]}}");
            emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\""
                    + id + "\",\"content\":\"" + esc(allowed ? "1\t# Fake project" : "PreToolUse:Read hook error: " + reason)
                    + "\",\"is_error\":" + !allowed + "}]}}");
            if (!allowed && reason.contains("meeting") && !stubborn) {
                String said = "Stopping here for the director's meeting; my changes are in the worktree.";
                emit("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\""
                        + esc(said) + "\"}]}}");
                emit("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":" + (n + 2)
                        + ",\"duration_ms\":1234,\"result\":\"" + esc(said) + "\",\"session_id\":\"" + session
                        + "\",\"total_cost_usd\":0.002,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    /** A tool_progress line each second for the call, as Claude Code reports a shell command still running. */
    static void heartbeat(String call) {
        try {
            for (int second = 1; ; second++) {
                Thread.sleep(1_000);
                emit("{\"type\":\"tool_progress\",\"tool_use_id\":\"" + call + "-heartbeat-" + second
                        + "\",\"tool_name\":\"Bash\",\"parent_tool_use_id\":\"" + call
                        + "\",\"elapsed_time_seconds\":" + second + "}");
            }
        } catch (InterruptedException done) {
            // The hunt is over: no heartbeat after its result.
        }
    }

    static void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }

    static String valueAfter(List<String> args, String flag) {
        int i = args.indexOf(flag);
        if (i < 0 || i + 1 >= args.size()) throw new IllegalArgumentException("missing " + flag);
        return args.get(i + 1);
    }

    static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : "";
    }

    static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }
}
