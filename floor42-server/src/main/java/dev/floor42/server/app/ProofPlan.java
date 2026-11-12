package dev.floor42.server.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Who runs each step of a recipe (ADR-0058): Floor42 in the sandbox, or the director by hand. A step needing a Docker
 * daemon or a desktop, one the producer marked, and every step after such a step go to the director; when the sandbox
 * does not work, every step does. Pure: nothing runs here.
 */
final class ProofPlan {

    static final String FLOOR42 = "FLOOR42";
    static final String DIRECTOR = "DIRECTOR";

    /** Commands with no daemon in the sandbox. */
    static final Set<String> DOCKER = Set.of("docker", "docker-compose", "podman", "kubectl");

    /** Commands that open a window on a desktop. */
    static final Set<String> DESKTOP = Set.of("open", "xdg-open", "start", "explorer", "explorer.exe");

    /** One step and who runs it; {@code reason} says why the director does, null for Floor42's steps. */
    record Assigned(ProofRecipe.Step step, String runner, String reason) {}

    private ProofPlan() {}

    static List<Assigned> assign(List<ProofRecipe.Step> steps, boolean sandboxWorks) {
        List<Assigned> out = new ArrayList<>();
        boolean afterDirector = false;
        for (ProofRecipe.Step s : steps) {
            String reason =
                    !sandboxWorks ? "SANDBOX_UNAVAILABLE" : s.director() ? "MARKED_FOR_DIRECTOR" : needs(s.command());
            if (reason == null && afterDirector) reason = "AFTER_DIRECTOR_STEP";
            if (reason != null) afterDirector = true;
            out.add(new Assigned(s, reason == null ? FLOOR42 : DIRECTOR, reason));
        }
        return List.copyOf(out);
    }

    /** What the sandbox lacks for this command, read from the first word of each of its lines; null when nothing. */
    static String needs(String command) {
        for (String line : command.split("\n")) {
            for (String part : line.split("&&|\\|\\||;|\\|")) {
                String word = commandWord(part.strip().split("\\s+"));
                if (DOCKER.contains(word)) return "NEEDS_DOCKER";
                if (DESKTOP.contains(word)) return "NEEDS_DESKTOP";
            }
        }
        return null;
    }

    /** The command a part runs: past {@code sudo}, {@code env}, their options and {@code VAR=x}; without its path. */
    private static String commandWord(String[] words) {
        int i = 0;
        while (i < words.length - 1 && isPrefix(words[i])) i++;
        String w = words[i].toLowerCase(Locale.ROOT);
        return w.substring(Math.max(w.lastIndexOf('/'), w.lastIndexOf('\\')) + 1);
    }

    private static boolean isPrefix(String w) {
        return w.equals("sudo") || w.equals("env") || w.startsWith("-") || w.contains("=");
    }
}
