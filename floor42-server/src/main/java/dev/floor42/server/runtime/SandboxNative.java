package dev.floor42.server.runtime;

/**
 * The sandbox script on native Linux (ADR-0028, amended 2026-11-09). On WSL the host's own files are few and a list of
 * masks hides them; on a real Linux host everything not named stays visible, so the script there starts from nothing:
 * a new root of its own (a tmpfs) into which only what a shell needs is bound read-only, a minimal {@code /etc}, a
 * fresh {@code /proc}, {@code /dev}, empty {@code /tmp}, and the worktree and git mounts the script already makes under
 * {@code /media}; then {@code pivot_root}, and the old root is detached. Nothing else of the host is in the new
 * mount namespace: no {@code /srv}, {@code /opt}, {@code /var/lib}, {@code /var/log}, no data disk, no home folder.
 *
 * <p>Network on means a network namespace of its own with user-mode networking ({@code pasta}), never the host's:
 * the host's loopback services and abstract unix sockets stay out of reach. Without {@code pasta} the script refuses
 * and says which package to install. The network-off case is a new, empty network namespace, as on WSL.
 *
 * <p>The text is the WSL script ({@link Sandbox#RUN}) with two pieces replaced, so every other mount and check stays
 * one code path. The blocks are inside the script's single-quoted inner shell: no single quote in them.
 */
final class SandboxNative {

    private SandboxNative() {}

    private static final String MASKS_FROM = "D=/media/.dev\n";
    private static final String MASKS_TO = "export HOME=/tmp";
    private static final String NETWORK_FROM = "[ \"$3\" = \"on\" ] && NET=\n";
    private static final String PATH_FROM =
            "export HOME=/tmp PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final String EXEC_FROM = "exec unshare -Urm -C -i -u $NET";

    /** Refusal and wrapper for network on: pasta gives the sandbox its own network, the host's stays out of reach. */
    private static final String NETWORK = """
            [ "$3" = "on" ] && NET=
            export F42_NET="$3"
            PRE=
            if [ "$3" = on ]; then
              command -v pasta >/dev/null || { echo "Floor42 sandbox: network access on Linux needs user-mode networking (pasta). Run: sudo apt install passt" >&2; exit 126; }
              PRE="pasta --config-net --no-map-gw --dns-forward 10.0.2.3 -t none -u none -T none -U none --"
            fi
            """;

    /** Builds the new root and pivots into it, in place of the denylist of the WSL script. */
    private static final String ROOT = """
            # Native Linux: an allowlist root (SandboxNative). Staging in the old /tmp, which the new root replaces.
            PATH=/usr/sbin:/sbin:/usr/bin:/bin
            mount -t tmpfs tmpfs /tmp
            NR=/tmp/r
            mkdir "$NR"
            mount --bind "$NR" "$NR"
            for d in usr bin sbin lib lib32 lib64 libx32; do
              if [ -L "/$d" ]; then ln -s "$(readlink "/$d")" "$NR/$d"
              elif [ -d "/$d" ]; then mkdir "$NR/$d"; mount --rbind "/$d" "$NR/$d"; rr "$NR/$d" nosuid,nodev
                python3 -I /media/.mounts.py ro "$NR/$d"; fi
            done
            # A minimal /etc: users, groups and hosts written here, and a few read-only binds a shell and its tools read.
            mkdir "$NR/etc"
            printf "root:x:0:0:root:/tmp:/bin/sh\\nnobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin\\n" > "$NR/etc/passwd"
            printf "root:x:0:\\nnogroup:x:65534:\\n" > "$NR/etc/group"
            printf "127.0.0.1 localhost\\n::1 localhost\\n" > "$NR/etc/hosts"
            printf "passwd: files\\ngroup: files\\nhosts: files dns\\n" > "$NR/etc/nsswitch.conf"
            if [ "$F42_NET" = on ]; then printf "nameserver 10.0.2.3\\n" > "$NR/etc/resolv.conf"; fi
            for p in /etc/alternatives /etc/ssl/certs /etc/ssl/openssl.cnf /etc/ca-certificates /etc/pki /etc/fonts /etc/java-* \\
              /etc/ld.so.cache /etc/ld.so.conf /etc/ld.so.conf.d /etc/localtime /etc/mime.types /etc/gai.conf /etc/protocols \\
              /etc/services /etc/os-release /etc/debian_version /etc/maven /etc/mavenrc /etc/xml /etc/sgml \\
              /etc/texmf /etc/magic /etc/chromium /etc/libreoffice; do
              [ -e "$p" ] || continue
              if [ -d "$p" ]; then mkdir -p "$NR$p"; else mkdir -p "$NR${p%/*}"; : > "$NR$p"; fi
              mount --bind "$p" "$NR$p"; rr "$NR$p" nosuid,nodev
              # The Debian mvn reads /etc/maven/m2.conf, but its settings may hold passwords: masked by an empty
              # read-only file, as sandbox-mounts.py masks (/dev/null would be a writable device in their place).
              if [ "$p" = /etc/maven ]; then : >> /tmp/.empty; for f in settings.xml settings-security.xml; do
                if [ -f "$NR$p/$f" ]; then mount --bind /tmp/.empty "$NR$p/$f"; rr "$NR$p/$f"; fi; done; fi
            done
            # Toolchains and caches named by ToolchainMounts, read-only, at the same path.
            python3 -I /media/.mounts.py tools /media/.tools "$NR" /tmp
            XP=
            while read -r k p; do if [ "$k" = bin ]; then XP="$XP:$p"; fi; done < /media/.tools
            D="$NR/dev"
            mkdir "$D"
            mount -t tmpfs -o mode=755,nosuid,noexec tmpfs "$D"
            for n in null zero full random urandom tty; do : > "$D/$n"; mount --bind "/dev/$n" "$D/$n"; done
            mkdir "$D/pts" "$D/shm"
            mount -t devpts -o newinstance,ptmxmode=0666,mode=620 devpts "$D/pts"
            mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$D/shm"
            ln -s pts/ptmx "$D/ptmx"
            ln -s /proc/self/fd "$D/fd"
            ln -s /proc/self/fd/0 "$D/stdin"
            ln -s /proc/self/fd/1 "$D/stdout"
            ln -s /proc/self/fd/2 "$D/stderr"
            mkdir -p "$NR/proc" "$NR/sys" "$NR/tmp" "$NR/var" "$NR/var/tmp" "$NR/run" "$NR/media" "$NR/old"
            mount -t proc -o nosuid,nodev,noexec proc "$NR/proc"
            mount -t tmpfs -o ro,nosuid,nodev,noexec,mode=555 tmpfs "$NR/sys"
            mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$NR/tmp"
            mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "$NR/var/tmp"
            mount -n --move /media "$NR/media"
            cd "$NR"
            pivot_root . old
            cd /
            umount -l /old
            rmdir /old
            rr /
            unset F42_NET
            """;

    /** The script for native Linux, from the WSL one. */
    static String script(String run) {
        int from = run.indexOf(MASKS_FROM);
        int to = run.indexOf(MASKS_TO);
        if (from < 0
                || to < from
                || !run.contains(NETWORK_FROM)
                || !run.contains(EXEC_FROM)
                || !run.contains(PATH_FROM)) {
            throw new IllegalStateException("The sandbox script changed: SandboxNative no longer matches it");
        }
        return (run.substring(0, from) + ROOT + "unset WSL_INTEROP WSLENV\n" + run.substring(to))
                .replace(NETWORK_FROM, NETWORK)
                .replace(PATH_FROM, PATH_FROM + "$XP")
                .replace(EXEC_FROM, "exec $PRE unshare -Urm -C -i -u $NET");
    }
}
