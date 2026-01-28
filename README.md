# CLM Service

Contract Lifecycle Management Service built with Spring Boot and Apache Camel.

## Features

- **Contract Management** - Full CRUD operations with state machine validation
- **Customer Management** - Customer data with validation (CPF/CNPJ)
- **ETL Pipeline** - Staging, transformation, and loading via Apache Camel
- **EIP Patterns** - Content-based routing, message aggregation, idempotency
- **Oracle Integration** - Native stored procedure calls via Oracle Wallet

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         REST API Layer                          │
│              ContractController, CustomerController             │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Service Layer                             │
│         ContractService, CustomerService, EtlService            │
│                  (Functional programming style)                 │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Apache Camel Routes                         │
│         ContractEtlRoute, CustomerEtlRoute, IntegrationRoute    │
│              (EIP: Router, Splitter, Aggregator)                │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Repository Layer                            │
│         OracleContractRepository, OracleCustomerRepository      │
│              (Stored procedure calls via JDBC)                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Oracle Database                          │
│    contract_pkg, customer_pkg, etl_pkg, integration_pkg         │
└─────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Java 21+
- Maven 3.9+
- Oracle Database with wallet configured
- Oracle Wallet files (cwallet.sso, tnsnames.ora, sqlnet.ora)

## Configuration

### Oracle Wallet Setup

1. Place wallet files in a directory (e.g., `/opt/oracle/wallet`)
2. Set environment variables:
   ```bash
   export ORACLE_WALLET_LOCATION=/opt/oracle/wallet
   export ORACLE_TNS_NAME=clm_db
   ```

3. Ensure `tnsnames.ora` contains your connection:
   ```
   clm_db = 
     (DESCRIPTION = 
       (ADDRESS = (PROTOCOL = TCP)(HOST = your-host)(PORT = 1521))
       (CONNECT_DATA = (SERVICE_NAME = clmdb))
     )
   ```

### Application Properties

See `src/main/resources/application.yml` for configuration options.

## Building

```bash
mvn clean package
```

## Running

```bash
# With wallet location
java -jar target/clm-service-1.0.0-SNAPSHOT.jar \
  --oracle.wallet.walletLocation=/opt/oracle/wallet \
  --oracle.wallet.tnsName=clm_db
```

## API Endpoints

### Contracts

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/contracts` | Create contract |
| GET | `/api/v1/contracts/{id}` | Get by ID |
| GET | `/api/v1/contracts` | List contracts |
| PUT | `/api/v1/contracts/{id}` | Update contract |
| PATCH | `/api/v1/contracts/{id}/status` | Update status |
| DELETE | `/api/v1/contracts/{id}` | Soft delete |

### Customers

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/customers` | Create customer |
| GET | `/api/v1/customers/{id}` | Get by ID |
| GET | `/api/v1/customers` | List customers |
| PUT | `/api/v1/customers/{id}` | Update customer |
| POST | `/api/v1/customers/{id}/activate` | Activate |
| POST | `/api/v1/customers/{id}/deactivate` | Deactivate |

### ETL

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/etl/contracts/ingest` | Ingest contracts |
| POST | `/api/v1/etl/customers/ingest` | Ingest customers |
| GET | `/api/v1/etl/sessions/{id}` | Get session status |

## Camel Routes

### Contract ETL Pipeline

```
direct:contract-ingest
    → direct:contract-stage
    → direct:contract-transform
    → direct:contract-validate
    → direct:contract-promote
    → direct:contract-complete
```

### Integration Message Router

```
direct:route-message
    → Idempotent check
    → Content-based routing
    → Event handlers
    → Mark processed
```

## Functional Programming Patterns

This service uses functional programming patterns:

- **Immutable domain records** - All domain objects are Java records
- **Optional for nullable** - No nulls, use Optional<T>
- **Either for errors** - `Either<Error, Success>` instead of exceptions
- **Try for failures** - `Try<T>` for operations that may fail
- **Stream for collections** - Lazy evaluation with Stream<T>
- **Function composition** - Chain operations with map/flatMap

Example:
```java
// Find contract and update status, or return error
contractService.findById(tenantId, id)
    .filter(Contract::isModifiable)
    .map(c -> c.withStatus("ACTIVE"))
    .map(c -> contractService.update(c, user))
    .orElse(Either.left(ValidationResult.error("NOT_MODIFIABLE", "...")));
```

## Database Schema

Run migrations in order:
1. `oracle/migrations/001_create_eip_types.sql`
2. `oracle/migrations/002_create_eip_tables.sql`

Install packages:
- `oracle/packages/contract_pkg.sql`
- `oracle/packages/customer_pkg.sql`
- `oracle/packages/etl_pkg.sql`
- `oracle/packages/integration_pkg.sql`

## Testing

```bash
# Unit tests
mvn test

# Integration tests (requires Oracle)
mvn verify -Pit
```

## License

Proprietary - GprintEx Team
