"""Mounts for Floor42's shell sandbox that must not follow a link (ADR-0028). Run by run.sh inside the sandbox's
namespaces, before the command, with the standard library only.

A path checked by the daemon some time before the command starts can be swapped for a symbolic link in between, and
a mount follows links. So every source and every target is opened here, one component at a time, with
O_PATH|O_NOFOLLOW from a folder already held open, each component must be a plain folder, and the mount is made
from /proc/self/fd/N: what was opened is what is mounted.

A read-only remount must keep the flags the mount already has (nosuid, nodev, noexec, ...): in a user namespace the
kernel refuses a remount that drops one the host's mount set ("locked" flags), which would stop every command on a
home folder that is mounted noexec. The flags are read from the mount itself (statvfs).

  deps SOURCE LIST WORK STAGE   node_modules folders of SOURCE (relative, ':'-joined in LIST) into WORK, as overlays
  tools LIST ROOT STAGE         the folders named in the file LIST at the same path in ROOT, read-only; "dir" roots have
                                the files that usually hold credentials masked, "cache" folders are shown as they are
  ro DIR                        every mount below DIR made read-only; one that cannot be is detached, and if that fails
                                the command is not started
  loopback                      lo up in the (new, empty) network namespace of the sandbox
  flags PATH                 "ro" and the flags of the mount of PATH, as a mount -o list (for the shell script)
"""

import ctypes
import fnmatch
import os
import stat
import sys

libc = ctypes.CDLL(None, use_errno=True)
libc.mount.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_ulong, ctypes.c_char_p]
libc.umount2.argtypes = [ctypes.c_char_p, ctypes.c_int]
MS_RDONLY, MS_REMOUNT, MS_BIND, MS_REC = 1, 32, 4096, 16384
MS_NOSUID, MS_NODEV, MS_NOEXEC = 2, 4, 8
MS_NOATIME, MS_NODIRATIME, MS_RELATIME = 1024, 2048, 1 << 21
MS_STRICTATIME = 1 << 24
ST_RELATIME = 4096  # statvfs spells relatime differently from mount(2)
MNT_DETACH = 2
O_PATH = getattr(os, "O_PATH", 0o10000000)

# Credential and configuration files masked inside a toolchain root: defence in depth, not a promise.
# Matched without regard to case, and also with a backup suffix (npmrc.bak, settings.xml~, id_rsa.old).
SECRET_NAMES = {
    "npmrc", ".npmrc", "settings.xml", "settings-security.xml", "toolchains.xml", "pip.conf", "pip.ini", ".pypirc",
    ".netrc", "_netrc", "credentials", ".git-credentials", "gradle.properties", ".pgpass", ".env", "auth.json",
    ".yarnrc.yml", ".condarc",
}
SECRET_GLOBS = (
    "*.pem", "*.key", "*.p12", "*.pfx", "*.ppk", "*.jks", "*.keystore", "id_rsa*", "id_ed25519*", "id_ecdsa*",
    "id_dsa*", ".env.*",
)
BACKUP_SUFFIXES = (".bak", ".old", "~")
MAX_DEPTH = 16
# Test-only: lowers the cap so the cap path can be exercised. The sandbox's own environment is scrubbed, so a command
# cannot set it.
MAX_ENTRIES = int(os.environ.get("F42_MASK_CAP", "200000"))


class NotShown(Exception):
    """A toolchain root that cannot be checked for credentials: it is not shown, with this reason."""


def die(message):
    sys.exit("Floor42 sandbox: " + message)


def mount(source, target, fstype, flags, data=None):
    if libc.mount(source, target, fstype, flags, data) != 0:
        e = ctypes.get_errno()
        raise OSError(e, os.strerror(e) + " (mounting " + target.decode(errors="replace") + ")")


def kept(path):
    """The flags of the mount at {path} that a remount must carry on."""
    f = os.statvfs(path).f_flag
    # The atime mode is always carried on (a locked one cannot change), and nodiratime only goes with one.
    atime = MS_NOATIME if f & 1024 else MS_RELATIME if f & ST_RELATIME else MS_STRICTATIME
    return (f & (MS_NOSUID | MS_NODEV | MS_NOEXEC | MS_NODIRATIME)) | atime


def read_only_flags(path):
    return MS_BIND | MS_REMOUNT | MS_RDONLY | MS_NODEV | MS_NOSUID | kept(path)


def fdpath(fd):
    return ("/proc/self/fd/%d" % fd).encode()


def real(fd):
    return os.readlink("/proc/self/fd/%d" % fd)


def walk(base, rel, create):
    """The folder {rel} below the open folder {base}: no link on the way, created when {create}."""
    fd = os.dup(base)
    for part in rel.strip("/").split("/"):
        if part in ("", ".", ".."):
            die("refused path " + rel)
        try:
            nxt = os.open(part, O_PATH | os.O_NOFOLLOW, dir_fd=fd)
        except FileNotFoundError:
            if not create:
                die(rel + " is missing")
            os.mkdir(part, 0o755, dir_fd=fd)
            nxt = os.open(part, O_PATH | os.O_NOFOLLOW, dir_fd=fd)
        os.close(fd)
        if not stat.S_ISDIR(os.fstat(nxt).st_mode):
            die(rel + " is a link or not a folder: refused")
        fd = nxt
    return fd


def deps(source, listing, work, stage):
    sfd = os.open(source, O_PATH | os.O_DIRECTORY)
    wfd = os.open(work, O_PATH | os.O_DIRECTORY)
    root = real(sfd)
    for i, rel in enumerate(r for r in listing.split(":") if r):
        src = walk(sfd, rel, False)
        if not real(src).startswith(root.rstrip("/") + "/"):
            die(rel + " leads outside the worktree: refused")
        dst = walk(wfd, rel, True)
        low = ("%s/.dep%d" % (stage, i + 1)).encode()
        over = "%s/.ov%d" % (stage, i + 1)
        for d in (low.decode(), over + "/u", over + "/w"):
            os.makedirs(d, exist_ok=True)
        # The folder as it was opened, read-only, is what the overlay reads: a later swap of the path changes nothing.
        mount(fdpath(src), low, None, MS_BIND | MS_REC)
        mount(None, low, None, read_only_flags(low.decode()))
        read_only(low.decode())
        try:
            options = ("lowerdir=%s,upperdir=%s/u,workdir=%s/w" % (low.decode(), over, over)).encode()
            mount(b"overlay", fdpath(dst), b"overlay", 0, options)
        except OSError:
            mount(low, fdpath(dst), None, MS_BIND | MS_REC)


def is_secret(path, name):
    lower = name.lower()
    names = [lower] + [lower[: -len(s)] for s in BACKUP_SUFFIXES if lower.endswith(s) and len(lower) > len(s)]
    return (
        any(n in SECRET_NAMES or any(fnmatch.fnmatchcase(n, g) for g in SECRET_GLOBS) for n in names)
        or path.lower().endswith("/.docker/config.json")
    )


def secrets(top):
    """The credential files and folders below {top}, by a bounded walk that follows no link; NotShown at a cap or at
    a folder that cannot be read."""
    found, count, stack = [], 0, [(top, 0)]
    while stack:
        here, depth = stack.pop()
        if depth > MAX_DEPTH:
            raise NotShown("too deep (more than %d levels, at %s)" % (MAX_DEPTH, here))
        try:
            with os.scandir(here) as entries:
                for e in entries:
                    count += 1
                    if count > MAX_ENTRIES:
                        raise NotShown("too many files (more than %d, at %s)" % (MAX_ENTRIES, here))
                    if e.is_symlink():
                        continue
                    folder = e.is_dir(follow_symlinks=False)
                    if is_secret(e.path, e.name):
                        found.append((e.path, folder))
                    elif folder:
                        stack.append((e.path, depth + 1))
        except OSError as err:
            raise NotShown("a folder cannot be read: %s" % err)
    return found


def mask(top, stage):
    """An empty read-only file over each credential file, an empty read-only tmpfs over each such folder."""
    empty = os.path.join(stage, ".empty").encode()
    if not os.path.exists(empty):
        open(empty, "wb").close()
    for path, folder in secrets(top):
        target = path.encode()
        if folder:
            mount(b"tmpfs", target, b"tmpfs", MS_RDONLY | MS_NOSUID | MS_NODEV | MS_NOEXEC, b"size=4k")
        else:
            mount(empty, target, None, MS_BIND)
            mount(None, target, None, read_only_flags(path))


def tools(listing, root, stage):
    hfd = os.open("/", O_PATH | os.O_DIRECTORY)
    rfd = os.open(root, O_PATH | os.O_DIRECTORY)
    with open(listing, encoding="utf-8") as f:
        entries = [line.rstrip("\n").split(" ", 1) for line in f if line.startswith(("dir ", "cache "))]
    for i, (kind, path) in enumerate(entries):
        if not path.startswith("/") or ".." in path.split("/"):
            continue
        try:
            src = walk(hfd, path, False)
        except SystemExit:
            continue  # absent here, or a link on the way: not mounted
        low = ("%s/.tool%d" % (stage, i + 1)).encode()
        os.makedirs(low.decode(), exist_ok=True)
        # Read-only at its own mount first; a bind of a read-only mount is read-only (a remount cannot name the target).
        mount(fdpath(src), low, None, MS_BIND | MS_REC)
        mount(None, low, None, read_only_flags(low.decode()))
        read_only(low.decode())
        if kind == "dir":
            try:
                mask(low.decode(), stage)
            except (NotShown, OSError) as e:
                libc.umount2(low, MNT_DETACH)
                sys.stderr.write("Floor42 sandbox: %s not shown, its credentials cannot be masked: %s\n" % (path, e))
                continue
        mount(low, fdpath(walk(rfd, path, True)), None, MS_BIND | MS_REC)


def loopback():
    """Brings lo up in the network namespace the sandbox just made (it is created down): a private loopback, so a test
    may talk to a server it starts itself. The host's loopback is another namespace and stays unreachable."""
    import fcntl
    import socket
    import struct

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    request = struct.pack("16sH14x", b"lo", 0)
    flags_now = struct.unpack("16sH14x", fcntl.ioctl(s, 0x8913, request))[1]  # SIOCGIFFLAGS
    fcntl.ioctl(s, 0x8914, struct.pack("16sH14x", b"lo", flags_now | 1))  # SIOCSIFFLAGS, IFF_UP


def unescape(field):
    return field.replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")


def read_only(top):
    top = os.path.realpath(top).rstrip("/") + "/"
    with open("/proc/self/mountinfo", encoding="utf-8", errors="replace") as f:
        points = [unescape(line.split()[4]) for line in f]
    for point in sorted((p for p in points if p.startswith(top)), key=len, reverse=True):
        encoded = point.encode()
        try:
            mount(None, encoded, None, read_only_flags(point))
        except OSError:
            if libc.umount2(encoded, MNT_DETACH) != 0:
                die("cannot make %s read-only or detach it: the command is not started" % point)


def flags(path):
    names = [(MS_NOSUID, "nosuid"), (MS_NODEV, "nodev"), (MS_NOEXEC, "noexec"), (MS_NOATIME, "noatime"),
             (MS_NODIRATIME, "nodiratime"), (MS_RELATIME, "relatime"), (MS_STRICTATIME, "strictatime")]
    k = kept(path)
    print(",".join(["ro"] + [n for bit, n in names if k & bit]))


if __name__ == "__main__":
    mode, args = sys.argv[1], sys.argv[2:]
    try:
        {"deps": deps, "tools": tools, "ro": read_only, "flags": flags, "loopback": loopback}[mode](*args)
    except OSError as e:
        die(str(e))
