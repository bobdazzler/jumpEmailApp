package jump.email.app.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Service for distributed locking using database.
 * Ensures only one node processes an account at a time in multi-node deployments.
 */
@Slf4j
@Service
public class DistributedLockService {
    private final JdbcTemplate jdbcTemplate;
    private static final String LOCK_TABLE = "account_processing_locks";
    private static final int LOCK_TIMEOUT_MINUTES = 10; // Lock expires after 10 minutes
    
    public DistributedLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initializeLockTable();
    }
    
    /**
     * Initialize the lock table if it doesn't exist.
     */
    private void initializeLockTable() {
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS " + LOCK_TABLE + " (" +
                "account_id VARCHAR(255) PRIMARY KEY, " +
                "locked_by VARCHAR(255) NOT NULL, " +
                "locked_at TIMESTAMP NOT NULL, " +
                "expires_at TIMESTAMP NOT NULL" +
                ")"
            );
            log.debug("Lock table initialized");
        } catch (Exception e) {
            log.warn("Could not initialize lock table (may already exist): {}", e.getMessage());
        }
    }
    
    /**
     * Attempts to acquire a lock for an account.
     * @param accountId The account ID to lock
     * @param nodeId Unique identifier for this node (e.g., hostname or instance ID)
     * @return true if lock was acquired, false otherwise
     */
    public boolean tryLock(String accountId, String nodeId) {
        try {
            // Clean up expired locks first
            cleanupExpiredLocks();
            
            Instant now = Instant.now();
            Instant expiresAt = now.plusSeconds(TimeUnit.MINUTES.toSeconds(LOCK_TIMEOUT_MINUTES));
            
            // Try to insert lock (will fail if account_id already exists)
            // PostgreSQL uses ON CONFLICT, but we'll handle it with a try-catch approach for compatibility
            try {
                int rows = jdbcTemplate.update(
                    "INSERT INTO " + LOCK_TABLE + " (account_id, locked_by, locked_at, expires_at) " +
                    "VALUES (?, ?, ?, ?)",
                    accountId, nodeId, now, expiresAt
                );
                
                if (rows > 0) {
                    log.debug("Acquired lock for account: {}", accountId);
                    return true;
                }
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // Lock already exists, check if expired
                log.debug("Lock already exists for account: {}, checking if expired", accountId);
            }
            
            // Check if existing lock is expired
            Integer expiredCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + LOCK_TABLE + 
                " WHERE account_id = ? AND expires_at < ?",
                Integer.class,
                accountId, now
            );
            
            if (expiredCount != null && expiredCount > 0) {
                // Remove expired lock and try again
                jdbcTemplate.update(
                    "DELETE FROM " + LOCK_TABLE + " WHERE account_id = ? AND expires_at < ?",
                    accountId, now
                );
                
                // Try to acquire lock again
                try {
                    int retryRows = jdbcTemplate.update(
                        "INSERT INTO " + LOCK_TABLE + " (account_id, locked_by, locked_at, expires_at) " +
                        "VALUES (?, ?, ?, ?)",
                        accountId, nodeId, now, expiresAt
                    );
                    
                    if (retryRows > 0) {
                        log.debug("Acquired lock for account {} after removing expired lock", accountId);
                        return true;
                    }
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // Another node acquired it first
                    log.debug("Another node acquired lock for account {} before we could", accountId);
                }
            }
            
            log.debug("Could not acquire lock for account: {} (already locked by another node)", accountId);
            return false;
        } catch (Exception e) {
            log.error("Error acquiring lock for account {}: {}", accountId, e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Releases a lock for an account.
     * @param accountId The account ID to unlock
     * @param nodeId The node ID that holds the lock
     */
    public void releaseLock(String accountId, String nodeId) {
        try {
            int rows = jdbcTemplate.update(
                "DELETE FROM " + LOCK_TABLE + 
                " WHERE account_id = ? AND locked_by = ?",
                accountId, nodeId
            );
            
            if (rows > 0) {
                log.debug("Released lock for account: {}", accountId);
            } else {
                log.warn("Attempted to release lock for account {} but lock not found or owned by different node", accountId);
            }
        } catch (Exception e) {
            log.error("Error releasing lock for account {}: {}", accountId, e.getMessage(), e);
        }
    }
    
    /**
     * Cleans up expired locks.
     */
    private void cleanupExpiredLocks() {
        try {
            int deleted = jdbcTemplate.update(
                "DELETE FROM " + LOCK_TABLE + " WHERE expires_at < ?",
                Instant.now()
            );
            if (deleted > 0) {
                log.debug("Cleaned up {} expired locks", deleted);
            }
        } catch (Exception e) {
            log.warn("Error cleaning up expired locks: {}", e.getMessage());
        }
    }
    
    /**
     * Gets the node ID for this instance (hostname or environment variable).
     */
    public String getNodeId() {
        String nodeId = System.getenv("FLY_APP_INSTANCE_ID");
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = System.getenv("HOSTNAME");
        }
        if (nodeId == null || nodeId.isEmpty()) {
            nodeId = System.getProperty("user.name") + "-" + System.getProperty("java.vm.name");
        }
        return nodeId;
    }
}
