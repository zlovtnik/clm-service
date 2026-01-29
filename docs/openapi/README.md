# ORDS OpenAPI Specifications

This directory contains OpenAPI 3.0 specifications for the Oracle REST Data Services (ORDS) APIs exposed by the CLM database packages.

## Files

| File | Package | Description |
|------|---------|-------------|
| [integration_pkg.json](integration_pkg.json) | `INTEGRATION_PKG` | Message routing, transformation, aggregation, and deduplication |
| [etl_pkg.json](etl_pkg.json) | `ETL_PKG` | ETL operations, staging, validation, and data promotion |
| [customer_pkg.json](customer_pkg.json) | `CUSTOMER_PKG` | Customer CRUD, search, and validation |
| [contract_pkg.json](contract_pkg.json) | `CONTRACT_PKG` | Contract lifecycle, auto-renewal, and statistics |

## Server URL

The specifications use a placeholder `${ORDS_BASE_URL}` for the server URL. Replace this with your actual ORDS endpoint:

```
https://<database-id>.adb.<region>.oraclecloudapps.com/ords/<schema>
```

## Usage

### Import into Postman

1. Open Postman
2. Click **Import**
3. Select the desired JSON file
4. Configure the environment variable `ORDS_BASE_URL`

### Generate Client Code

```bash
# Using OpenAPI Generator
openapi-generator generate \
  -i docs/openapi/contract_pkg.json \
  -g java \
  -o target/generated-sources/openapi/contract
```

## Authentication

ORDS supports multiple authentication methods (configured in `application.yml`):

- **BASIC**: HTTP Basic Auth with username/password
- **OAUTH2**: OAuth2 client credentials flow
- **JWT_PASSTHROUGH**: Forward Keycloak JWT to ORDS
- **NONE**: No authentication (development only)

See [OrdsProperties.java](../../src/main/java/com/gprintex/clm/config/OrdsProperties.java) for configuration details.
