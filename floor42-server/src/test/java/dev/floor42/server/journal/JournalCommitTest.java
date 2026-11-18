package dev.floor42.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * sqlite-jdbc's commit() runs {@code commit;} then {@code begin immediate;}: when that BEGIN is busy the call throws
 * although the rows are durable. A committed append must never be reported as failed (a retry would duplicate it).
 */
class JournalCommitTest {

    @TempDir
    Path dir;

    @Test
    void anAppendCommittedBeforeTheDriverFailsIsNotReportedAsFailed() {
        SqliteJournal journal = TestJournals.openWal(dir.resolve("floor42.db"));
        SQLiteDataSource real = (SQLiteDataSource) journal.dataSource;
        journal.dataSource = new DelegatingDataSource(real) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection c = real.getConnection();
                return (Connection) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                            try {
                                Object result = m.invoke(c, args);
                                if (m.getName().equals("commit")) {
                                    throw new SQLiteException(
                                            "[SQLITE_BUSY] begin immediate after commit", SQLiteErrorCode.SQLITE_BUSY);
                                }
                                return result;
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
        };

        journal.append(List.of(TestJournals.created("M-1"))); // must not throw

        assertThat(TestJournals.open(dir.resolve("floor42.db")).readAll()).hasSize(1);
        assertThat(journal.locked()).as("nothing to report as locked").isEmpty();
    }
}
