package dev.floor42.server.runtime;

import dev.floor42.server.runtime.MachineChecklist.Checklist;
import dev.floor42.server.runtime.MachineChecklist.ChecklistRow;
import dev.floor42.server.runtime.MachineChecklist.ChecklistState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the native Linux sandbox needs of the host, read only (ADR-0028, amended 2026-11-09): user namespaces for
 * unprivileged users, and {@code pasta} for network access. Nothing is changed or installed: the checklist names the
 * command and the director runs it.
 */
public final class NativeChecks {

    private NativeChecks() {}

    /**
     * @param applies this machine runs the native backend (Linux)
     * @param pasta {@code pasta} is on the PATH
     * @param usernsRestriction why unprivileged user namespaces are refused here, null when nothing says so
     * @param hiddenTools the tools the daemon finds that the sandbox will not show, each with the reason
     */
    public record Facts(boolean applies, boolean pasta, String usernsRestriction, Map<String, String> hiddenTools) {}

    static final String SYSCTL = "sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0";

    /** The narrow fix first (one profile for unshare only), the machine-wide sysctl as a weaker alternative. */
    static final String APPARMOR_PROFILE =
            "echo 'profile floor42-unshare /usr/bin/unshare flags=(unconfined) { userns, }'"
                    + " | sudo tee /etc/apparmor.d/floor42-unshare"
                    + " && sudo apparmor_parser -r /etc/apparmor.d/floor42-unshare";

    public static Facts measure() {
        Facts f = measure(SandboxHost.linux(SandboxHost.current()), System.getenv("PATH"), Path.of("/proc/sys"));
        if (!f.applies()) return f;
        Map<String, String> hidden = ToolchainMounts.resolve(
                        System.getProperty("user.home"),
                        System.getenv(),
                        ToolchainMounts.VISIBLE,
                        ToolchainMounts.UNAVAILABLE)
                .hidden();
        return new Facts(true, f.pasta(), f.usernsRestriction(), hidden);
    }

    static Facts measure(boolean linux, String path, Path sys) {
        if (!linux) return new Facts(false, false, null, Map.of());
        boolean pasta = false;
        if (path != null) {
            for (String d : path.split(java.io.File.pathSeparator)) {
                if (!d.isBlank() && Files.isExecutable(Path.of(d).resolve("pasta"))) pasta = true;
            }
        }
        String why = null;
        if ("1".equals(read(sys.resolve("kernel/apparmor_restrict_unprivileged_userns")))) {
            why = "AppArmor refuses unprivileged user namespaces here (kernel.apparmor_restrict_unprivileged_userns=1,"
                    + " the Ubuntu 23.10+ default): allow unshare in a dedicated AppArmor profile (the command below), or, weaker, set the sysctl to 0";
        } else if ("0".equals(read(sys.resolve("kernel/unprivileged_userns_clone")))) {
            why = "the kernel refuses unprivileged user namespaces (kernel.unprivileged_userns_clone=0)";
        } else if ("0".equals(read(sys.resolve("user/max_user_namespaces")))) {
            why = "user namespaces are disabled (user.max_user_namespaces=0)";
        }
        return new Facts(true, pasta, why, Map.of());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            // Handled: the setting does not exist on this kernel
            return null;
        }
    }

    /** The checklist with the native rows: a pasta row after the isolation row, and the isolation row's remedy. */
    public static Checklist apply(Checklist c, Facts f) {
        if (!f.applies()) return c;
        List<ChecklistRow> rows = new ArrayList<>();
        for (ChecklistRow r : c.rows()) {
            rows.add(r.id().equals("isolation") ? withRemedy(r, f) : r);
            if (r.id().equals("isolation")) rows.add(pastaRow(f));
        }
        f.hiddenTools().forEach((tool, why) -> rows.add(hiddenRow(tool, why)));
        boolean ready = rows.stream().filter(ChecklistRow::required).allMatch(r -> r.state() == ChecklistState.PRESENT);
        return new Checklist(c.windows(), c.distro(), c.measuredAt(), ready, List.copyOf(rows));
    }

    /** A failed isolation row with the reason the kernel gives and the command that lifts it. */
    private static ChecklistRow withRemedy(ChecklistRow r, Facts f) {
        // "only on Windows" is wrong here: the sandbox runs on this machine, it has not been measured yet.
        if ("notWindows".equals(r.note())) {
            r = new ChecklistRow(
                    r.id(), false, ChecklistState.UNKNOWN, true, null, "notMeasured", null, null, r.measuredAt());
        }
        if (f.usernsRestriction() == null || r.state() == ChecklistState.PRESENT) return r;
        return new ChecklistRow(
                "isolation",
                false,
                ChecklistState.MISSING,
                true,
                r.version(),
                "userns",
                f.usernsRestriction(),
                APPARMOR_PROFILE + "  # weaker alternative, for the whole machine: " + SYSCTL
                        + " (persist it in /etc/sysctl.d/)",
                r.measuredAt());
    }

    /** A tool found on the PATH that the sandbox does not show: one row with its name, so it is not a mystery. */
    private static ChecklistRow hiddenRow(String tool, String why) {
        return new ChecklistRow("hidden." + tool, false, ChecklistState.OPTIONAL, false, null, "hiddenTool", why, null);
    }

    private static ChecklistRow pastaRow(Facts f) {
        return new ChecklistRow(
                "pasta",
                false,
                f.pasta() ? ChecklistState.PRESENT : ChecklistState.OPTIONAL,
                false,
                null,
                null,
                f.pasta() ? null : "without it a command cannot be given network access: it is refused",
                f.pasta() ? null : "sudo apt install passt");
    }
}
