package dev.floor42.server.runtime.pty;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/** The kernel32 functions {@link WindowsPty} uses; {@link #load} gives null when this Windows has no ConPTY. */
record Kernel32(
        MethodHandle createPipe,
        MethodHandle createPseudoConsole,
        MethodHandle resizePseudoConsole,
        MethodHandle closePseudoConsole,
        MethodHandle initializeAttributes,
        MethodHandle updateAttribute,
        MethodHandle deleteAttributes,
        MethodHandle createProcess,
        MethodHandle readFile,
        MethodHandle writeFile,
        MethodHandle closeHandle,
        MethodHandle waitForSingleObject,
        MethodHandle getExitCodeProcess,
        MethodHandle terminateProcess,
        java.lang.foreign.StructLayout callState,
        VarHandle lastError) {

    static Kernel32 load() {
        if (!Pty.windows()) return null;
        try {
            SymbolLookup k = SymbolLookup.libraryLookup("kernel32", Arena.global());
            if (k.find("CreatePseudoConsole").isEmpty()) return null;
            java.lang.foreign.StructLayout state = Linker.Option.captureStateLayout();
            return new Kernel32(
                    fn(k, "CreatePipe", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT),
                    fn(k, "CreatePseudoConsole", JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS),
                    fn(k, "ResizePseudoConsole", JAVA_INT, ADDRESS, JAVA_INT),
                    fn(k, "ClosePseudoConsole", null, ADDRESS),
                    fn(k, "InitializeProcThreadAttributeList", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS),
                    fn(
                            k,
                            "UpdateProcThreadAttribute",
                            JAVA_INT,
                            ADDRESS,
                            JAVA_INT,
                            JAVA_LONG,
                            ADDRESS,
                            JAVA_LONG,
                            ADDRESS,
                            ADDRESS),
                    fn(k, "DeleteProcThreadAttributeList", null, ADDRESS),
                    createProcess(k),
                    fn(k, "ReadFile", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    fn(k, "WriteFile", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
                    fn(k, "CloseHandle", JAVA_INT, ADDRESS),
                    fn(k, "WaitForSingleObject", JAVA_INT, ADDRESS, JAVA_INT),
                    fn(k, "GetExitCodeProcess", JAVA_INT, ADDRESS, ADDRESS),
                    fn(k, "TerminateProcess", JAVA_INT, ADDRESS, JAVA_INT),
                    state,
                    state.varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError")));
        } catch (RuntimeException e) {
            // Handled: no ConPTY on this Windows: no terminal, the caller says so
            return null;
        }
    }

    /** CreateProcessW, with Windows' last error captured: the reason a start failed. */
    private static MethodHandle createProcess(SymbolLookup k) {
        return LINKER.downcallHandle(
                k.find("CreateProcessW").orElseThrow(),
                FunctionDescriptor.of(
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS,
                        ADDRESS),
                Linker.Option.captureCallState("GetLastError"));
    }

    /** A kernel32 function by name: its result (null for void), then its arguments. */
    private static MethodHandle fn(
            SymbolLookup lookup,
            String name,
            java.lang.foreign.MemoryLayout result,
            java.lang.foreign.MemoryLayout... args) {
        return h(lookup, name, result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args));
    }

    private static MethodHandle h(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    private static final Linker LINKER = Linker.nativeLinker();
}
