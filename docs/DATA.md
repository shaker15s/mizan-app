# Data

## Database

Room database `mizan.db`, version 2, schema export enabled (`room.schemaLocation`). `MizanDatabase.create` adds `MIGRATION_1_2` and does not call `fallbackToDestructiveMigration()`.

Version 1 tables (`execution_records`, `trust_receipts`, `audit_records`, `reconciliation_items`, `erp_orders`, `erp_customers`, `erp_stocks`) are copied and then dropped. Legacy money with no currency is stored as ISO `XXX`, not as USD. Legacy rows are `LEGACY_LOCAL`. Unknown tool names map to `ToolName.UNKNOWN`, which cannot be executed.

Orders, customers, and stock use a composite primary key of business id plus tenant, so two workspaces can cache the same ERP id.

## Tenant scope

Every store method that a screen can reach takes a `TenantId`. Search requires at least two characters and stays inside that tenant. There is no default unscoped list.

## Money

`Money` is minor units plus an ISO-4217 code. Formatting is presentation. Policy compares minor units only inside one currency.

## Sync

`SyncStore` can record a snapshot. Nothing writes a successful sync, because no sync protocol is implemented. The connection screen says the saved copy has not been checked. That is the truth.

## Session

`mizan_session` holds one token. `mizan_prefs` holds language, theme, reduced motion, onboarding, an optional service URL override, and a demo-only “next write is uncertain” flag. The override is not a secret. The token file is excluded from backup.

## Migration risk

`MIGRATION_1_2` assumes the version-1 table names and columns from the prototype. It has not been executed against a real version-1 file in this environment. A device that never installed version 1 starts at version 2 and does not run the migration.
