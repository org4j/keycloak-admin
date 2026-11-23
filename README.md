# keycloak-admin

Keycloak's admin API for what services need: users, their realm roles, and machine-to-machine
clients. The backend logs in as its own confidential client with client credentials; Spring
Security keeps and refreshes the token.

```groovy
implementation libs.keycloak.admin
```

```java
var id = keycloak.createUser(email, email, password, false, List.of(KeycloakConstants.CONFIGURE_TOTP));
var invited = keycloak.inviteUser(email, firstName, List.of(), "merchant-operator");
keycloak.grantRealmRole(id, "partner");
var client = keycloak.createConfidentialClient("shop-1", "merchant");
var secret = keycloak.rotateSecret(client.id());
```

## What it does

| Method | In Keycloak |
|---|---|
| `createUser` | an enabled user with a permanent password and the required actions asked, e.g. `CONFIGURE_TOTP` |
| `inviteUser` | an enabled user named by its e-mail, no password, its realm roles granted, then e-mailed a link to choose a password (`UPDATE_PASSWORD`) and do the other required actions |
| `markEmailVerified`, `enableUser` | changes that one field, nothing else |
| `disableUser` | disables the user and ends its sessions; access tokens already issued last until they expire |
| `logoutUser`, `deleteUser` | ends the user's sessions; removes the user |
| `grantRealmRole`, `revokeRealmRole` | a realm role, by name |
| `createConfidentialClient` | a client with only the client-credentials grant, its service account holding the realm roles given; returns its id and secret |
| `rotateSecret` | a new secret, taking the client's id (not its name); the old one stops working at once |
| `ping` | reads one role: the health check |

An invitation or a client that fails halfway (an unknown role, the e-mail not sent) is removed
again, so the same call can be made once more.

A token Keycloak refuses (`invalid_token`: a restart, a rotated secret) is dropped and the call
tried once more. The HTTP client is Boot's (`spring.http.clients.*`: SSL bundle, redirects) with the
library's own timeouts.

## Properties

| Property | Default | What |
|---|---|---|
| `org4j.keycloak-admin.url` | none | Keycloak's base URL; setting it turns the library on |
| `org4j.keycloak-admin.realm` | none, required | the realm managed |
| `org4j.keycloak-admin.client-id` | none, required | the backend's confidential client, allowed to use the admin API |
| `org4j.keycloak-admin.client-secret` | none, required | that client's secret, from Vault |
| `org4j.keycloak-admin.connect-timeout` | `5s` | for every call, the token request included |
| `org4j.keycloak-admin.read-timeout` | `10s` | for every call, the token request included |

## Errors

| Code | Status | When |
|---|---|---|
| `KEYCLOAK_CONFLICT` | 409 | what was created already exists; the parameter names it |
| `KEYCLOAK_REJECTED` | 400 | Keycloak refused the input: a 4xx other than 401/403, e.g. its password policy or an unknown role |
| `KEYCLOAK_UNAVAILABLE` | 503 | a 5xx, a refused login (401/403), a timeout, no token, or a broken answer |

Keycloak's own message is a sub-error. A failure without an answer names only its kind, never
Keycloak's URL.

## Health

With the actuator, `keycloak`: UP while the backend can log in and read the admin API; DOWN names
the error code only. Without the actuator there is no indicator, and the rest works the same.

## Beans you can replace

| Bean | |
|---|---|
| `KeycloakAdmin` | any bean of the type |
| `keycloakHealthIndicator` | a bean of that name |

## Tests

- `./gradlew test`: `KeycloakAdminTest` is the specification, one nested group per subject: users,
  invitations, roles, clients and failures. `KeycloakAutoConfigurationTest` covers the properties,
  an app without the actuator, a refused token and the read timeout; `RetryOnceOnUnauthorizedTest`
  says which refusals are tried again.
- Test fixtures: `demo-realm.json`, the one realm (`demo`) every integration test imports, here, in
  security and in the app (`testFixtures(libs.keycloak.admin)`).
- `./gradlew integrationTest`: a real Keycloak with that realm and Mailpit: users, roles, logins,
  invitations e-mailed, secrets that rotate, and Keycloak stopped mid-life.
