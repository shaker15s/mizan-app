package app.mizan.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Preserves v1 rows. Does not drop history to "start clean".
 * Legacy money had no currency, so it is stored as ISO XXX rather than
 * pretending it was USD. Legacy ERP rows are marked LEGACY_LOCAL.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `executions` (
                `executionId` TEXT NOT NULL,
                `traceId` TEXT NOT NULL,
                `proposalId` TEXT NOT NULL,
                `tenantId` TEXT NOT NULL,
                `initiatorId` TEXT NOT NULL,
                `toolName` TEXT NOT NULL,
                `toolVersion` TEXT NOT NULL,
                `intent` TEXT NOT NULL,
                `phase` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `canonicalArgs` TEXT NOT NULL,
                `amountMinor` INTEGER,
                `currency` TEXT,
                `approval` TEXT NOT NULL,
                `riskTier` TEXT NOT NULL,
                `policyRuleId` TEXT NOT NULL,
                `approverIds` TEXT NOT NULL,
                `erpRecordId` TEXT,
                `erpModel` TEXT,
                `dispatch` TEXT NOT NULL,
                `leaseExpiresAt` INTEGER,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `errorCode` TEXT,
                `origin` TEXT NOT NULL,
                PRIMARY KEY(`executionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO executions (
                executionId, traceId, proposalId, tenantId, initiatorId, toolName, toolVersion,
                intent, phase, idempotencyKey, canonicalArgs, amountMinor, currency, approval,
                riskTier, policyRuleId, approverIds, erpRecordId, erpModel, dispatch,
                leaseExpiresAt, createdAt, updatedAt, errorCode, origin
            )
            SELECT
                executionId, traceId, 'legacy-' || executionId, tenantId, initiatorId, toolName,
                'unknown', rawIntent,
                CASE currentState WHEN 'FAILED' THEN 'ERP_FAILURE' ELSE currentState END,
                idempotencyKey, '{}',
                CAST(ROUND(financialAmount * 100) AS INTEGER),
                'XXX',
                approvalLevel, riskTier, 'LEGACY',
                COALESCE(approverId, ''),
                erpRecordId, NULL, 'UNKNOWN', leaseExpiresAt, timestamp, timestamp, errorMessage,
                'LEGACY_LOCAL'
            FROM execution_records
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `receipts` (
                `receiptId` TEXT NOT NULL, `traceId` TEXT NOT NULL, `executionId` TEXT NOT NULL,
                `tenantId` TEXT NOT NULL, `tenantLabel` TEXT NOT NULL, `initiatorId` TEXT NOT NULL,
                `initiatorLabel` TEXT NOT NULL, `approverIds` TEXT NOT NULL, `approverLabels` TEXT NOT NULL,
                `toolName` TEXT NOT NULL, `toolVersion` TEXT NOT NULL, `policyRuleId` TEXT NOT NULL,
                `approval` TEXT NOT NULL, `riskTier` TEXT NOT NULL, `canonicalArgs` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL, `erpRecordId` TEXT, `erpModel` TEXT,
                `verification` TEXT NOT NULL, `integrityClass` TEXT NOT NULL, `origin` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL, `auditChainIndex` INTEGER, PRIMARY KEY(`receiptId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO receipts (
                receiptId, traceId, executionId, tenantId, tenantLabel, initiatorId, initiatorLabel,
                approverIds, approverLabels, toolName, toolVersion, policyRuleId, approval, riskTier,
                canonicalArgs, idempotencyKey, erpRecordId, erpModel, verification, integrityClass,
                origin, createdAt, auditChainIndex
            )
            SELECT
                receiptId, traceId, executionId, tenantId, tenantName, initiatorId, initiatorName,
                approverId, approverName, toolName, toolVersion, policyRuleId, approvalLevel, riskTier,
                canonicalArgumentsJson, idempotencyKey, erpRecordId, erpModel, 'NOT_VERIFIED',
                'LOCAL_ONLY', 'LEGACY_LOCAL', timestamp, auditChainIndex
            FROM trust_receipts
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `audit_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `chainIndex` INTEGER NOT NULL, `timestampMillis` INTEGER NOT NULL,
                `traceId` TEXT NOT NULL, `tenantId` TEXT NOT NULL, `actorId` TEXT NOT NULL,
                `action` TEXT NOT NULL, `stateBefore` TEXT NOT NULL, `stateAfter` TEXT NOT NULL,
                `details` TEXT NOT NULL, `previousHash` TEXT NOT NULL, `currentHash` TEXT NOT NULL,
                `integrityClass` TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO audit_events (
                chainIndex, timestampMillis, traceId, tenantId, actorId, action, stateBefore,
                stateAfter, details, previousHash, currentHash, integrityClass
            )
            SELECT chainIndex, timestamp, traceId, tenantId, actorId, action, stateBefore,
                stateAfter, detailsJson, previousHash, currentHash, 'LOCAL_ONLY'
            FROM audit_records
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `reconciliation_cases` (
                `caseId` TEXT NOT NULL, `executionId` TEXT NOT NULL, `traceId` TEXT NOT NULL,
                `tenantId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `intent` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL, `candidateRecordIds` TEXT NOT NULL,
                `status` TEXT NOT NULL, `notes` TEXT, `openedAt` INTEGER NOT NULL,
                PRIMARY KEY(`caseId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO reconciliation_cases (
                caseId, executionId, traceId, tenantId, toolName, intent, idempotencyKey,
                candidateRecordIds, status, notes, openedAt
            )
            SELECT reconciliationId, executionId, traceId, tenantId, toolName, intent, idempotencyKey,
                candidateErpIds,
                CASE resolutionStatus
                    WHEN 'MATCHED_AND_CLOSED' THEN 'LINKED'
                    WHEN 'ABANDONED' THEN 'CLOSED_WITHOUT_LINK'
                    ELSE 'OPEN'
                END,
                resolutionNotes, timestamp
            FROM reconciliation_items
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_orders` (
                `orderId` TEXT NOT NULL, `tenantId` TEXT NOT NULL, `customerName` TEXT NOT NULL,
                `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL, `origin` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`orderId`, `tenantId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO cached_orders (
                orderId, tenantId, customerName, amountMinor, currency, status, summary, origin, updatedAt
            )
            SELECT orderId, tenantId, customerName, CAST(ROUND(totalAmount * 100) AS INTEGER),
                CASE WHEN currency IS NULL OR currency = '' THEN 'XXX' ELSE currency END,
                status, itemsSummary, 'LEGACY_LOCAL', COALESCE(verifiedAt, dateCreated)
            FROM erp_orders
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_customers` (
                `customerId` TEXT NOT NULL, `tenantId` TEXT NOT NULL, `name` TEXT NOT NULL,
                `creditMinor` INTEGER, `balanceMinor` INTEGER, `currency` TEXT, `status` TEXT NOT NULL,
                `origin` TEXT NOT NULL, PRIMARY KEY(`customerId`, `tenantId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO cached_customers (
                customerId, tenantId, name, creditMinor, balanceMinor, currency, status, origin
            )
            SELECT customerId, tenantId, nameEn,
                CAST(ROUND(creditLimit * 100) AS INTEGER),
                CAST(ROUND(currentBalance * 100) AS INTEGER),
                'XXX', status, 'LEGACY_LOCAL'
            FROM erp_customers
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `cached_stock` (
                `sku` TEXT NOT NULL, `tenantId` TEXT NOT NULL, `name` TEXT NOT NULL,
                `availableQty` INTEGER NOT NULL, `reservedQty` INTEGER NOT NULL,
                `priceMinor` INTEGER, `currency` TEXT, `location` TEXT NOT NULL, `origin` TEXT NOT NULL,
                PRIMARY KEY(`sku`, `tenantId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO cached_stock (
                sku, tenantId, name, availableQty, reservedQty, priceMinor, currency, location, origin
            )
            SELECT productSku, tenantId, productNameEn, availableQty, reservedQty,
                CAST(ROUND(unitPrice * 100) AS INTEGER), 'XXX', location, 'LEGACY_LOCAL'
            FROM erp_stocks
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sync_meta` (
                `tenantId` TEXT NOT NULL, `lastSuccessfulSync` INTEGER, `lastAttempt` INTEGER,
                `state` TEXT NOT NULL, `pendingChanges` INTEGER NOT NULL, `failedChanges` INTEGER NOT NULL,
                `conflicts` INTEGER NOT NULL, `serverCursor` TEXT, PRIMARY KEY(`tenantId`)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_executions_tenantId` ON `executions` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_executions_updatedAt` ON `executions` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_executions_phase` ON `executions` (`phase`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_executions_traceId` ON `executions` (`traceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_executions_idempotencyKey` ON `executions` (`idempotencyKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_tenantId` ON `receipts` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_traceId` ON `receipts` (`traceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_executionId` ON `receipts` (`executionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_createdAt` ON `receipts` (`createdAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_tenantId` ON `audit_events` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_timestampMillis` ON `audit_events` (`timestampMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_traceId` ON `audit_events` (`traceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_events_chainIndex` ON `audit_events` (`chainIndex`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reconciliation_cases_tenantId` ON `reconciliation_cases` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reconciliation_cases_status` ON `reconciliation_cases` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_reconciliation_cases_executionId` ON `reconciliation_cases` (`executionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_orders_tenantId` ON `cached_orders` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_orders_updatedAt` ON `cached_orders` (`updatedAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_customers_tenantId` ON `cached_customers` (`tenantId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_stock_tenantId` ON `cached_stock` (`tenantId`)")

        db.execSQL("DROP TABLE IF EXISTS execution_records")
        db.execSQL("DROP TABLE IF EXISTS trust_receipts")
        db.execSQL("DROP TABLE IF EXISTS audit_records")
        db.execSQL("DROP TABLE IF EXISTS reconciliation_items")
        db.execSQL("DROP TABLE IF EXISTS erp_orders")
        db.execSQL("DROP TABLE IF EXISTS erp_customers")
        db.execSQL("DROP TABLE IF EXISTS erp_stocks")
    }
}
