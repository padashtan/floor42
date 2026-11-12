import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A stand-in for an interactive program in a terminal (never Claude Code, never a model): it says whether it sees a
 * terminal, which environment names of Floor42's it was given, and its arguments; then answers each line typed. Lines:
 * {@code /cost} prints a cost line as Claude Code would, {@code child} starts a child process that sleeps, {@code exit}
 * ends with code 3, anything else is echoed back. With {@code --claude-prompt} it also draws an input line as Claude
 * Code does ({@code > } between two rules, then its "? for shortcuts" footer) after each answer, and {@code ask} shows a question with numbered options in its
 * place, as the folder trust screen does, until the next line answers it.
 */
public class FakeTty {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--sleep")) {
            Thread.sleep(600_000);
            return;
        }
        var console = System.console();
        boolean tty = console != null && console.isTerminal();
        TreeSet<String> floor42 = new TreeSet<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey().toUpperCase();
            if (k.startsWith("FLOOR42") || k.startsWith("OFFICE_") || k.equals("CLAUDECODE")) floor42.add(e.getKey());
        }
        say("FAKE-TTY ready tty=" + tty + " term=" + System.getenv("TERM") + " floor42-env=" + floor42 + " args="
                + List.of(args) + " cwd=" + System.getProperty("user.dir"));
        boolean prompt = List.of(args).contains("--claude-prompt");
        boolean asking = false;
        if (prompt) prompt();
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            String l = line.strip();
            if (asking) {
                asking = false;
                say("answered: " + l);
            } else if (prompt && l.equals("ask")) {
                asking = true;
                say("Do you trust the files in this folder?");
                say("");
                say("> 1. Yes, proceed");
                say("  2. No, exit");
                continue;
            } else if (l.equals("/cost")) {
                say("Total cost:            $0.0000 (fake)");
            } else if (l.equals("child")) {
                boolean windows = System.getProperty("os.name").startsWith("Windows");
                Process p = new ProcessBuilder(
                                windows ? List.of("ping", "-n", "600", "127.0.0.1") : List.of("sleep", "600"))
                        .redirectErrorStream(true)
                        .start();
                say("child " + p.pid());
            } else if (l.equals("exit")) {
                say("bye");
                System.exit(3);
            } else {
                say("echo: " + l);
            }
            if (prompt) prompt();
        }
    }

    private static void prompt() {
        // Claude Code 2.x's empty input box and its footer; what is typed then echoes after the footer.
        String rule = "─".repeat(40);
        System.out.print(rule + "\r\n> \r\n" + rule + "\r\n  ? for shortcuts");
        System.out.flush();
    }

    private static void say(String s) {
        System.out.print(s + "\r\n");
        System.out.flush();
    }
}
