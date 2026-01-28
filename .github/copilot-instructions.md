# Copilot instructions for CLM Service

## Big picture architecture
- Spring Boot 3.2 + Apache Camel 4: REST controllers call services, services delegate to Camel routes and Oracle repositories.
- Service boundaries:
  - API layer: controllers in `src/main/java/com/gprintex/clm/api`.
  - Service layer: `ContractService`, `CustomerService`, `EtlService` under `src/main/java/com/gprintex/clm/service`.
  - Camel routes: `ContractEtlRoute`, `CustomerEtlRoute`, `IntegrationRoute` in `src/main/java/com/gprintex/clm/camel`.
  - Repositories: Oracle stored procedure adapters in `src/main/java/com/gprintex/clm/repository` (e.g., `OracleContractRepository`).
- Data flow: API → Service → Camel route (direct/seda endpoints) → Repository → Oracle packages (`oracle/packages/*.sql`).

## Project-specific patterns
- Functional error handling with Vavr: repositories/services return `Either`, `Try`, and `Optional` rather than throwing.
- Domain model uses Java records (immutable) and validation is returned as `ValidationResult` lists.
- Repository implementations use `SimpleJdbcCall` and Oracle types (STRUCT/ARRAY) for package calls.
- Camel routes standardize on `direct:` for pipeline steps and `seda:` for async/queue stages; error handling uses dead-letter channels.

## Critical workflows
- Build: `mvn clean package`.
- Unit tests: `mvn test`.
- Integration tests (Oracle required): `mvn verify -Pit` (profile `it`).
- Run with wallet settings:
  - `ORACLE_WALLET_LOCATION` and `ORACLE_TNS_NAME` env vars, or pass
    `--oracle.wallet.walletLocation=... --oracle.wallet.tnsName=...`.

## Configuration & integration points
- Runtime config in `src/main/resources/application.yml` (datasource via wallet, Camel config, tenant defaults).
- Wallet and TNS templates in `src/main/resources/wallet`.
- Oracle schema and packages in `oracle/migrations` and `oracle/packages` (stored procedures are the source of truth for repository calls).

## Where to look for examples
- Functional service patterns: `src/main/java/com/gprintex/clm/service/ContractService.java`.
- Camel ETL pipeline: `src/main/java/com/gprintex/clm/camel/ContractEtlRoute.java`.
- Oracle stored procedure integration: `src/main/java/com/gprintex/clm/repository/OracleContractRepository.java`.
- Domain records and validation: `src/main/java/com/gprintex/clm/domain`.
