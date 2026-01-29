# Oracle OAuth & Centralized User Management

This document explains the integration between the CLM Service, external Identity Providers (IdP), and Oracle Database for centralized user management.

## Architecture Overview

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌──────────────┐
│   Frontend  │────>│  CLM Service │────>│    ORDS     │────>│    Oracle    │
│   (React)   │     │ (Spring Boot)│     │  REST APIs  │     │   Database   │
└─────────────┘     └──────────────┘     └─────────────┘     └──────────────┘
       │                   │                                         │
       │    JWT Token      │                                         │
       v                   v                                         │
┌─────────────┐     ┌──────────────┐                                 │
│  Keycloak   │<───>│  JWT Verify  │                                 │
│  (or Azure  │     │   + Sync     │────────────────────────────────>│
│   AD/Okta)  │     └──────────────┘   User email/profile synced     │
└─────────────┘                         on first login               │
                                                                     │
                                        ┌────────────────────────────┘
                                        v
                               ┌──────────────────┐
                               │  user_accounts   │
                               │  user_sessions   │
                               │  user_audit_log  │
                               └──────────────────┘
```

## Integration Options

### Option 1: Keycloak + Oracle User Sync (Recommended)

This is the recommended approach that provides:
- **Keycloak as primary IdP**: Handles authentication, MFA, social login
- **Oracle as user store**: Stores user profiles, tenant access, audit logs
- **Automatic sync**: User data synced from JWT on each request

**Pros:**
- Keycloak handles complex auth scenarios (MFA, SSO, social login)
- Oracle has complete user records for SQL queries and reporting
- JWT passthrough to ORDS for API authorization
- Audit trail in Oracle for compliance

**Flow:**
1. User authenticates with Keycloak → receives JWT
2. Frontend sends JWT to CLM Service
3. `UserSyncFilter` extracts claims from JWT
4. `UserService.syncUserFromJwt()` syncs user to Oracle
5. Oracle `user_pkg.sync_user_from_idp()` creates/updates user record
6. Request continues with user context

### Option 2: Oracle ORDS OAuth as IdP

Use Oracle ORDS OAuth directly as the identity provider.

**Pros:**
- Single system for auth and data
- No external IdP dependency
- Native Oracle user management

**Cons:**
- Less feature-rich than dedicated IdPs
- No built-in MFA, social login support
- Tighter coupling to Oracle

**Setup:**
```sql
-- Register OAuth client in Oracle
BEGIN
  OAUTH.CREATE_CLIENT(
    p_name            => 'clm-frontend',
    p_grant_type      => 'authorization_code',
    p_owner           => 'CLM Admin',
    p_description     => 'CLM Frontend Application',
    p_redirect_uri    => 'https://your-app.com/callback',
    p_support_email   => 'support@example.com',
    p_privilege_names => 'clm_read,clm_write'
  );
END;
/
```

## Database Schema

### Tables Created by Migration `004_create_user_tables.sql`

| Table | Purpose |
|-------|---------|
| `user_accounts` | Core user profiles with email, name, status |
| `user_tenant_access` | Many-to-many user-tenant with roles |
| `user_sessions` | Active session tracking |
| `user_audit_log` | Security event log |
| `ords_oauth_clients` | OAuth client registration |

### User Roles Hierarchy

```
SUPERADMIN (5) > OWNER (4) > ADMIN (3) > USER (2) > VIEWER (1)
```

A user with ADMIN role can access resources requiring USER or VIEWER roles.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `AUTO_SYNC_USERS` | Enable automatic user sync from JWT | `true` |
| `JWT_TENANT_CLAIM` | JWT claim containing tenant ID | `tenant_id` |
| `JWT_USER_CLAIM` | JWT claim containing user ID | `sub` |

### application.yml

```yaml
security:
  api:
    auto-sync-users: true  # Enable user sync on requests
  jwt:
    tenant-claim: tenant_id
    user-claim: sub
```

## API Endpoints

### User Management (`/api/v1/users`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/me` | Get current user info |
| PUT | `/me/profile` | Update user profile |
| POST | `/me/sync` | Force re-sync from JWT |
| GET | `/{userId}` | Get user by ID (admin) |
| GET | `/{userId}/tenants/{tenantId}/access` | Check tenant access |
| POST | `/{userId}/tenants/{tenantId}/access` | Grant tenant access |
| DELETE | `/{userId}/tenants/{tenantId}/access` | Revoke tenant access |
| POST | `/{userId}/lock` | Lock user account |
| POST | `/{userId}/unlock` | Unlock user account |

### Example: Get Current User

```bash
curl -X GET https://api.example.com/api/v1/users/me \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "X-Tenant-Id: ACME"
```

Response:
```json
{
  "userId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "email": "john.doe@acme.com",
  "firstName": "John",
  "lastName": "Doe",
  "displayName": "John Doe",
  "status": "ACTIVE",
  "idpSource": "KEYCLOAK",
  "tenantId": "ACME",
  "lastLoginAt": "2024-01-15T10:30:00Z"
}
```

## PL/SQL Package: `user_pkg`

### Key Procedures/Functions

```sql
-- Sync user from IdP (called by Java on each authenticated request)
FUNCTION sync_user_from_idp(
  p_user_id    VARCHAR2,
  p_email      VARCHAR2,
  p_first_name VARCHAR2 DEFAULT NULL,
  p_last_name  VARCHAR2 DEFAULT NULL,
  p_username   VARCHAR2 DEFAULT NULL,
  p_tenant_id  VARCHAR2 DEFAULT NULL,
  p_idp_source VARCHAR2 DEFAULT 'KEYCLOAK',
  p_claims     CLOB DEFAULT NULL
) RETURN VARCHAR2;

-- Check tenant access with role hierarchy
FUNCTION has_tenant_access(
  p_user_id       VARCHAR2,
  p_tenant_id     VARCHAR2,
  p_required_role VARCHAR2 DEFAULT 'VIEWER'
) RETURN NUMBER;  -- 1=yes, 0=no

-- Security audit logging (autonomous transaction)
PROCEDURE log_security_event(
  p_user_id       VARCHAR2,
  p_event_type    VARCHAR2,
  p_tenant_id     VARCHAR2 DEFAULT NULL,
  p_event_details CLOB DEFAULT NULL,
  ...
);
```

## Security Considerations

1. **Email from JWT Only**: User email is extracted from the validated JWT, not user input
2. **Automatic Deactivation**: Users not seen for 90+ days can be auto-deactivated
3. **Session Tracking**: All logins/logouts are recorded with IP and user agent
4. **Audit Trail**: Security events logged with autonomous transaction (always commits)
5. **PII Protection**: Emails are masked in logs (`j***n@acme.com`)

## Running the Migration

```bash
# Apply user management tables
cd oracle/migrations
sqlplus username/password@tns_name @004_create_user_tables.sql

# Install the user package
cd ../packages
sqlplus username/password@tns_name @user_pkg.sql
```

## Keycloak Configuration

### Required JWT Claims

Configure Keycloak to include these claims in tokens:

| Claim | Mapper Type | Source |
|-------|-------------|--------|
| `email` | User Attribute | email |
| `given_name` | User Attribute | firstName |
| `family_name` | User Attribute | lastName |
| `preferred_username` | User Attribute | username |
| `tenant_id` | User Attribute | tenantId (custom) |

### Client Scope

Create a `clm` client scope with the above mappers and assign to your client.

## Troubleshooting

### User Not Syncing

1. Check `AUTO_SYNC_USERS` is `true`
2. Verify JWT contains `email` claim
3. Check ORDS connectivity: `curl -X GET $ORDS_BASE_URL/ords/$SCHEMA/user_pkg/`
4. Review logs for `UserSyncFilter` or `UserService` errors

### Tenant Access Denied

1. Check `user_tenant_access` table for user's roles
2. Verify role hierarchy (VIEWER < USER < ADMIN < OWNER < SUPERADMIN)
3. Use `has_tenant_access` function to debug:
   ```sql
   SELECT user_pkg.has_tenant_access('user-id', 'ACME', 'ADMIN') FROM DUAL;
   ```

### Session Issues

```sql
-- View active sessions
SELECT * FROM user_sessions WHERE ended_at IS NULL ORDER BY started_at DESC;

-- End orphaned sessions
UPDATE user_sessions SET ended_at = SYSDATE WHERE ended_at IS NULL AND started_at < SYSDATE - 1;
```
