package com.douyin.mixcut.acceptance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opt-in MySQL acceptance for the durable job checkpoint contract.
 * The normal test suite skips this class unless ACCEPTANCE_DB_RUN=true.
 */
class JobServiceRecoveryDatabaseAcceptanceTest {
    private final String jobName = "p3-4-" + UUID.randomUUID();
    private Long jobId;

    @AfterEach
    void cleanupRows() throws Exception {
        if (jobId == null) return;
        try (Connection connection = connection();
             PreparedStatement repair = connection.prepareStatement("DELETE FROM output_repair WHERE job_id=?");
             PreparedStatement versions = connection.prepareStatement("DELETE FROM output_version WHERE job_id=?");
             PreparedStatement outputs = connection.prepareStatement("DELETE FROM job_output WHERE job_id=?");
             PreparedStatement jobs = connection.prepareStatement("DELETE FROM job WHERE id=?")) {
            for (PreparedStatement statement : new PreparedStatement[]{repair, versions, outputs, jobs}) {
                statement.setLong(1, jobId);
                statement.executeUpdate();
            }
        }
    }

    @Test
    void uniqueCheckpointAndStaleRecoverySemanticsHoldInIsolatedMysql() throws Exception {
        connection();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertJob = connection.prepareStatement(
                    "INSERT INTO job(name,status,progress,current,total,timeout_sec,stale_after_sec,last_activity_at) VALUES(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insertJob.setString(1, jobName);
                insertJob.setString(2, "running");
                insertJob.setInt(3, 50);
                insertJob.setInt(4, 1);
                insertJob.setInt(5, 2);
                insertJob.setInt(6, 1800);
                insertJob.setInt(7, 600);
                insertJob.setObject(8, LocalDateTime.now().minusMinutes(20));
                insertJob.executeUpdate();
                try (ResultSet keys = insertJob.getGeneratedKeys()) {
                    keys.next();
                    jobId = keys.getLong(1);
                }
            }
            try (PreparedStatement checkpoint = connection.prepareStatement(
                    "INSERT INTO job_output(job_id,idx,file_path,duration_sec,qc_status,segment_keys) VALUES(?,?,?,?,?,?)")) {
                checkpoint.setLong(1, jobId);
                checkpoint.setInt(2, 1);
                checkpoint.setString(3, "C:/p3-4/accepted.mp4");
                checkpoint.setDouble(4, 2.0);
                checkpoint.setString(5, "pass");
                checkpoint.setString(6, "[\"fixture@0+2\"]");
                checkpoint.executeUpdate();
            }
            connection.commit();
        }

        try (Connection connection = connection()) {
            assertEquals(1, countSuccessfulCheckpoints(connection, jobId));
            assertThrows(SQLException.class, () -> insertDuplicateCheckpoint(connection, jobId));
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE job SET status='pending', current=1, progress=50, error=NULL WHERE id=? AND status='running'")) {
                update.setLong(1, jobId);
                assertEquals(1, update.executeUpdate());
            }
            assertEquals("pending", readStatus(connection, jobId));
            assertEquals(1, countSuccessfulCheckpoints(connection, jobId));
        }
    }

    @Test
    void qcFailureAndMissingPathAreNotSuccessfulCheckpoints() throws Exception {
        try (Connection connection = connection()) {
            insertJobRow(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO job_output(job_id,idx,file_path,qc_status) VALUES(?,?,?,?)")) {
                statement.setLong(1, jobId);
                statement.setInt(2, 1);
                statement.setNull(3, java.sql.Types.VARCHAR);
                statement.setString(4, "fail");
                statement.executeUpdate();
            }
            assertEquals(0, countSuccessfulCheckpoints(connection, jobId));
        }
    }

    private void insertJobRow(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO job(name,status,progress,current,total,timeout_sec,stale_after_sec,last_activity_at) VALUES(?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, jobName);
            statement.setString(2, "running");
            statement.setInt(3, 0);
            statement.setInt(4, 0);
            statement.setInt(5, 1);
            statement.setInt(6, 1800);
            statement.setInt(7, 600);
            statement.setObject(8, LocalDateTime.now());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                jobId = keys.getLong(1);
            }
        }
    }

    private void insertDuplicateCheckpoint(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO job_output(job_id,idx,file_path,qc_status) VALUES(?,?,?,?)")) {
            statement.setLong(1, id);
            statement.setInt(2, 1);
            statement.setString(3, "C:/p3-4/duplicate.mp4");
            statement.setString(4, "pass");
            statement.executeUpdate();
        }
    }

    private int countSuccessfulCheckpoints(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM job_output WHERE job_id=? AND qc_status<> 'fail' AND file_path IS NOT NULL AND file_path<>''")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private String readStatus(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT status FROM job WHERE id=?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        String url = AcceptanceDatabaseGate.url();
        return DriverManager.getConnection(url, System.getenv("ACCEPTANCE_DB_USERNAME"),
                System.getenv("ACCEPTANCE_DB_PASSWORD"));
    }
}
