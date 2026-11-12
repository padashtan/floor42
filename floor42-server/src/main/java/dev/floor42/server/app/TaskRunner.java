package dev.floor42.server.app;

import dev.floor42.domain.MissionId;

/** What one kind of {@link Work} does once the dispatcher started it: one strategy per session kind. */
interface TaskRunner {

    Work work();

    /** Runs the task on the dispatcher's thread for it; a failure is the caller's to report. */
    void run(MissionId m, Task task);
}
