package dev.floor42.server.app;

import dev.floor42.server.config.DataFolders;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Makes one new, empty folder for the director inside the folder they are browsing in the mission form, checked by code.
 * The name is one plain folder name (no separator, no {@code ..}, no name Windows reserves, at most {@link #MAX_NAME}
 * characters). The parent is judged by its real path, links and junctions followed: never a disk root, the home folder
 * itself, a share, a folder that configures a tool (.git, .claude and the like, {@link DeliveryDestination#PROTECTED}),
 * Floor42's data folder or a system folder, nor anything inside them. Nothing else is written.
 */
public final class NewFolder {

    /** The longest name accepted: a folder name, not a path. */
    public static final int MAX_NAME = 80;

    /** The name is not one plain folder name. */
    public static final String NAME_INVALID = "NEW_FOLDER_NAME_INVALID";

    /** The parent is a share or a device path. */
    public static final String REMOTE = "NEW_FOLDER_REMOTE";

    /** The parent is not a folder that exists. */
    public static final String PARENT_MISSING = "NEW_FOLDER_PARENT_MISSING";

    /** The parent is, or lies in, a folder where Floor42 creates nothing. */
    public static final String PROTECTED = "NEW_FOLDER_PROTECTED";

    /** Something of that name is there already. */
    public static final String EXISTS = "NEW_FOLDER_EXISTS";

    /** The disk refused it. */
    public static final String FAILED = "NEW_FOLDER_FAILED";

    private static final Pattern RESERVED =
            Pattern.compile("(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[0-9¹²³]|lpt[0-9¹²³])");

    /** Folders Windows keeps for itself on every disk, compared without case. */
    private static final java.util.Set<String> WINDOWS_OWN =
            java.util.Set.of("$recycle.bin", "system volume information");

    private static final Pattern FORBIDDEN = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private NewFolder() {}

    /** Creates {@code name} in {@code parent} and returns its real path; refuses with one of the codes above. */
    public static Path create(String parent, String name, Path dataDir) {
        checkName(name);
        if (parent == null || parent.isBlank()) throw RefusedException.invalid(PARENT_MISSING, "parent is required");
        String p = parent.strip().replace('/', '\\');
        if (p.startsWith("\\\\")) throw RefusedException.invalid(REMOTE, parent + " is a network or device path");
        Path real = realParent(parent);
        checkParent(real, dataDir);
        Path target = real.resolve(name);
        if (!real.equals(target.getParent())) throw RefusedException.invalid(NAME_INVALID, name + " is not one name");
        try {
            Files.createDirectory(target);
            return confirm(target, real);
        } catch (FileAlreadyExistsException e) {
            throw RefusedException.conflict(EXISTS, target + " exists already", e);
        } catch (IOException | SecurityException e) {
            throw RefusedException.conflict(FAILED, "Floor42 could not create " + target + ": " + e.getMessage(), e);
        }
    }

    /**
     * The folder made, by its real path, checked again under the parent's real path: a link swapped in between the
     * check and the creation would have sent it elsewhere. Then it is removed and refused.
     */
    static Path confirm(Path target, Path realParent) throws IOException {
        Path made = target.toRealPath();
        if (realParent.equals(made.getParent())) return made;
        // The real path just checked, never the name again: removed only if empty (Files.delete is not recursive).
        Files.delete(made);
        throw protectedFolder(target + " was created at " + made + ", outside " + realParent + ": removed");
    }

    /** One plain folder name that every disk Floor42 runs on takes, and that the browser then lists. */
    static void checkName(String name) {
        if (name == null || name.isEmpty()) throw nameInvalid("a name is required");
        if (name.length() > MAX_NAME) throw nameInvalid("at most " + MAX_NAME + " characters");
        if (FORBIDDEN.matcher(name).find()) throw nameInvalid(name + " holds a separator or a reserved character");
        if (name.contains("..")) throw nameInvalid(name + " holds ..");
        if (!name.equals(name.strip()) || name.endsWith(".")) {
            throw nameInvalid(name + " starts or ends with a space, or ends with a dot");
        }
        // A hidden name would not show in the browser; '.git', '.claude' and the like start with a dot too.
        if (name.startsWith(".") || name.startsWith("$")) throw nameInvalid(name + " would be hidden");
        String base = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (RESERVED.matcher(base.strip()).matches()) throw nameInvalid(name + " is a name Windows reserves");
        if (DeliveryDestination.PROTECTED.contains(name.toLowerCase(Locale.ROOT))) {
            throw RefusedException.invalid(PROTECTED, name + " configures a tool: Floor42 does not create it");
        }
    }

    private static Path realParent(String parent) {
        try {
            Path asked = Path.of(parent.strip());
            if (!asked.isAbsolute() || !Files.isDirectory(asked)) {
                throw RefusedException.notFound(PARENT_MISSING, parent + " is not a folder");
            }
            return asked.toRealPath();
        } catch (InvalidPathException | IOException | SecurityException e) {
            throw new RefusedException(RefusedException.NOT_FOUND, PARENT_MISSING, parent + " is not a folder", e);
        }
    }

    /** The parent's real path is checked, never the path as typed: a junction leads where it leads. */
    static void checkParent(Path real, Path dataDir) {
        if (real.getParent() == null) throw protectedFolder(real + " is the root of a disk");
        if (real.toString().startsWith("\\\\")) throw RefusedException.invalid(REMOTE, real + " is a network path");
        Path home = DeliveryDestination.realOfNearest(
                Path.of(System.getProperty("user.home", "")).toAbsolutePath().normalize());
        if (real.equals(home)) throw protectedFolder(real + " is the home folder itself: open a folder inside it");
        // The folder of every account's home (C:\Users, /home): only the director's own home is open.
        Path homes = home.getParent();
        if (homes != null && homes.getParent() != null && real.startsWith(homes) && !real.startsWith(home)) {
            throw protectedFolder(real + " is another account's home, or the folder of the homes");
        }
        for (Path segment : real) {
            String s = segment.toString().toLowerCase(Locale.ROOT);
            if (DeliveryDestination.PROTECTED.contains(s) || WINDOWS_OWN.contains(s)) {
                throw protectedFolder(real + " is in " + segment + ", a folder of a tool or of Windows");
            }
        }
        for (Path guarded : DataFolders.guarded(dataDir)) {
            if (real.startsWith(
                    DeliveryDestination.realOfNearest(guarded.toAbsolutePath().normalize()))) {
                throw protectedFolder(real + " is inside Floor42's data folder");
            }
        }
        for (Path system : DeliveryDestination.systemFolders()) {
            if (real.startsWith(DeliveryDestination.realOfNearest(system)))
                throw protectedFolder(real + " is a system folder");
        }
    }

    private static RefusedException nameInvalid(String why) {
        return RefusedException.invalid(NAME_INVALID, why);
    }

    private static RefusedException protectedFolder(String why) {
        return RefusedException.invalid(PROTECTED, why);
    }
}
