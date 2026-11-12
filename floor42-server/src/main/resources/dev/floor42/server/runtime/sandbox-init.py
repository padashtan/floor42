# Floor42's sandbox init (ADR-0028, amended 2026-11-03): the first process of the command's PID namespace.
# Arguments: wall time in seconds, then the command. It forbids, by a seccomp filter every process of the sandbox
# inherits, the sockets that reach past the namespaces (AF_VSOCK reaches the Windows host's 9P file server; only
# AF_UNIX, AF_INET, AF_INET6 and AF_NETLINK stay, inside the sandbox's own network namespace) and io_uring (whose
# socket operation the filter would not see), and the calls that would let the command stop or read this process
# (ptrace, process_vm_readv and writev, kcmp, pidfd_getfd). It holds no file descriptor but 0, 1 and 2. Then it runs the command, reaps every orphan, and at the wall time, or as soon as nobody reads its output (the Windows side was stopped), kills
# every process of the namespace. Standard library only.
import ctypes
import os
import select
import signal
import struct
import sys

AUDIT_ARCH_X86_64 = 0xC000003E
ALLOW = 0x7FFF0000
KILL = 0x80000000
EPERM, ENOSYS, EAFNOSUPPORT = 1, 38, 97
SOCKET, SOCKETPAIR = 41, 53
IO_URING = (425, 426, 427)
PTRACE = (101, 310, 311, 312, 438)  # ptrace, process_vm_readv, process_vm_writev, kcmp, pidfd_getfd
FAMILIES = (1, 2, 10, 16)  # AF_UNIX, AF_INET, AF_INET6, AF_NETLINK


def errno(e):
    return 0x00050000 | e


def ld(offset):
    return (0x20, 0, 0, offset)  # BPF_LD | BPF_W | BPF_ABS


def ret(k):
    return (0x06, 0, 0, k)


def when(k, then, ge=False):
    """If the accumulator equals k (or is at least k), return 'then'; else go on."""
    return [((0x35 if ge else 0x15), 0, 1, k), ret(then)]


def families(allowed):
    block = [ld(16)]  # args[0], low 32 bits: the address family
    for f in allowed:
        block += when(f, ALLOW)
    return block + [ret(errno(EAFNOSUPPORT))]


def program():
    p = [ld(4)] + [(0x15, 1, 0, AUDIT_ARCH_X86_64), ret(KILL)]
    p += [ld(0)] + when(0x40000000, errno(EPERM), ge=True)
    for nr in IO_URING:
        p += when(nr, errno(ENOSYS))
    for nr in PTRACE:
        p += when(nr, errno(EPERM))
    for nr, allowed in ((SOCKET, FAMILIES), (SOCKETPAIR, (1,))):
        block = families(allowed)
        p += [(0x15, 0, len(block), nr)] + block + [ld(0)]
    return p + [ret(ALLOW)]


def install():
    libc = ctypes.CDLL(None, use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
    if os.uname().machine != "x86_64":
        sys.exit("Floor42's sandbox runs on x86_64 only")
    code = b"".join(struct.pack("HBBI", *i) for i in program())
    buf = ctypes.create_string_buffer(code)
    prog = struct.pack("HxxxxxxQ", len(code) // 8, ctypes.addressof(buf))
    pbuf = ctypes.create_string_buffer(prog)
    if libc.prctl(4, 0, None, 0, 0) != 0:  # PR_SET_DUMPABLE 0: the command cannot open this process's memory
        sys.exit("Floor42's sandbox could not make its init undumpable")
    if libc.prctl(38, 1, None, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
        sys.exit("Floor42's sandbox could not set no_new_privs")
    if libc.prctl(22, 2, ctypes.addressof(pbuf), 0, 0) != 0:  # PR_SET_SECCOMP, SECCOMP_MODE_FILTER
        sys.exit("Floor42's sandbox could not install its seccomp filter: errno %d" % ctypes.get_errno())


def host_gone(poller):
    """True once nobody reads our stdout or stderr: wsl.exe (the Windows side) ended, so the command must not go on.
    Only the output is looked at: a caller may close stdin at once, and that is no reason to stop."""
    return any(ev & (select.POLLERR | select.POLLHUP | select.POLLNVAL) for _, ev in poller.poll(0))


def main():
    seconds = int(sys.argv[1])
    command = sys.argv[2:]
    os.closerange(3, os.sysconf("SC_OPEN_MAX"))
    install()
    signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGCHLD])
    child = os.fork()
    if child == 0:
        signal.pthread_sigmask(signal.SIG_SETMASK, [])
        os.setsid()  # no controlling terminal: wsl.exe's is the director's
        os.execv(command[0], command)
    deadline = os.times().elapsed + seconds
    poller = select.poll()
    for fd in (1, 2):
        poller.register(fd, 0)  # only the error and hang-up conditions, which poll always reports
    while True:
        if host_gone(poller):
            os.kill(-1, signal.SIGKILL)
            os._exit(137)
        while True:
            try:
                pid, status = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                os._exit(0)
            if pid == 0:
                break
            if pid == child:
                code = os.waitstatus_to_exitcode(status)
                os._exit(code if code >= 0 else 128 - code)
        left = deadline - os.times().elapsed
        if left <= 0:
            sys.stderr.write("\n[Floor42 stopped the command after %d s]\n" % seconds)
            sys.stderr.flush()
            os.kill(-1, signal.SIGKILL)
            os._exit(137)
        signal.sigtimedwait([signal.SIGCHLD], min(left, 1))


main()
