# Oracle Schema Migrations

This directory contains the Oracle database schema migrations for the CLM Service integration management portal.

## Migration Order

Run migrations in numerical order:

| Migration | Purpose |
|-----------|---------|
| `000_drop_all.sql` | **⚠️ DESTRUCTIVE** - Nuclear drop of ALL schema objects |
| `001_create_types.sql` | Creates Oracle object types (customer_t, contract_t, etc.) |
| `002_create_tables.sql` | Creates all tables with enhanced EIP support |
| `003_create_views.sql` | Compatibility views for legacy package support |

## Running Migrations

### Fresh Install (New Environment)

```sql
-- Run in SQL Developer or SQLcl
@001_create_types.sql
@002_create_tables.sql
@003_create_views.sql
```

### Clean Rebuild (⚠️ DESTRUCTIVE - Drops All Data!)

> **CRITICAL**: The `000_drop_all.sql` script is **DESTRUCTIVE** and permanently deletes all schema objects and data. Follow these mandatory steps before execution:

#### Pre-Flight Checklist

1. **Verify Non-Production Environment**
   ```sql
   -- Confirm you are NOT connected to production
   SELECT SYS_CONTEXT('USERENV', 'DB_NAME') AS database_name,
          SYS_CONTEXT('USERENV', 'SERVICE_NAME') AS service_name,
          SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS current_schema
   FROM DUAL;
   
   -- STOP if the output shows a production database!
   ```

2. **Create Backup Using Data Pump**
   ```bash
   # Export the schema before any destructive operations
   expdp USERNAME/PASSWORD@TNS_ALIAS \
       schemas=YOUR_SCHEMA \
       directory=DATA_PUMP_DIR \
       dumpfile=clm_backup_%U.dmp \
       logfile=clm_backup.log \
       parallel=4
   
   # Verify backup file exists and has reasonable size
   ls -la /path/to/datapump/clm_backup_*.dmp
   ```

3. **Execute Clean Rebuild (only after backup verification)**
   ```sql
   -- WARNING: This destroys all data! Only run after backup!
   @000_drop_all.sql
   @001_create_types.sql
   @002_create_tables.sql
   @003_create_views.sql
   ```

### After Migrations - Recompile Packages

```sql
-- Recompile packages after schema is ready
@../packages/customer_pkg.sql
@../packages/contract_pkg.sql
@../packages/etl_pkg.sql
@../packages/integration_pkg.sql
```

## Schema Overview

### Core Message Infrastructure
- `messages` - Message envelope with metadata and lifecycle
- `message_payloads` - Separated payload storage
- `message_state_transitions` - Complete audit trail

### Routing and Transformation
- `routing_configs` - Versioned routing rules
- `routing_decisions` - Routing decision audit
- `transformation_templates` - Reusable transformation definitions
- `transformation_executions` - Execution tracking

### Orchestration and Workflows
- `process_definitions` - Workflow templates
- `process_instances` - Active workflow executions
- `process_steps` - Granular step tracking

### Aggregation and Correlation
- `aggregation_definitions` - Aggregation pattern definitions
- `aggregation_instances` - Active aggregations
- `aggregation_members` - Message membership

### Monitoring and Metrics
- `endpoint_metrics` - Time-series performance data
- `message_processing_metrics` - Per-message telemetry
- `system_health_metrics` - System-wide health
- `alert_definitions` / `alert_instances` - Alerting

### Data Governance
- `message_lineage` - Data lineage tracking
- `trace_spans` - Distributed tracing
- `message_deduplication` - Deduplication registry
- `idempotency_tokens` - API idempotency

### ETL Support
- `etl_sessions` - ETL session tracking
- `etl_staging` - Staging area for incoming data
- `etl_error_log` - Error logging

### Domain Tables
- `customers` - Customer master data
- `contracts` - Contract master data
- `contract_items` - Contract line items

### Configuration
- `integration_endpoints` - Endpoint registry
- `retry_policies` - Retry configurations
- `data_quality_rules` / `data_quality_violations` - Data quality
- `retention_policies` - Data retention
- `archive_metadata` - Archive tracking

### Reference Data
- `error_catalog` - Standardized error codes
- `integration_partners` - Partner registry
- `schema_versions` - Migration tracking

## Required Grants

For DBMS_CRYPTO (used in packages):
```sql
-- Run as ADMIN/DBA
GRANT EXECUTE ON DBMS_CRYPTO TO your_schema;
```

## Version History

| Version | Date | Description |
|---------|------|-------------|
| 2.0.0 | 2026-01-28 | Enhanced integration schema with comprehensive EIP support |
