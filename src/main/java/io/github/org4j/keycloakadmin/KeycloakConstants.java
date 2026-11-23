package io.github.org4j.keycloakadmin;

/** The properties' prefix, and the required actions a service asks of a user. */
public final class KeycloakConstants {

  /** Every property of this library starts with it. */
  public static final String PROPERTIES_PREFIX = "org4j.keycloak-admin";

  /** Has the user choose a password; every invitation asks it. */
  public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

  /** Has the user set up a one-time-password app. */
  public static final String CONFIGURE_TOTP = "CONFIGURE_TOTP";

  static final String REGISTRATION_ID = "keycloak-admin";
  static final String ADMIN_PATH = "/admin/realms/%s";
  static final String TOKEN_PATH = "/realms/%s/protocol/openid-connect/token";

  static final String USERS = "/users";
  static final String USER = "/users/{id}";
  static final String USER_REALM_ROLES = "/users/{id}/role-mappings/realm";
  static final String USER_ACTIONS_EMAIL = "/users/{id}/execute-actions-email";
  static final String USER_LOGOUT = "/users/{id}/logout";
  static final String ROLE = "/roles/{name}";
  static final String ROLES_ONE = "/roles?max=1";
  static final String CLIENTS = "/clients";
  static final String CLIENT = "/clients/{id}";
  static final String CLIENT_SECRET = "/clients/{id}/client-secret";
  static final String CLIENT_SERVICE_ACCOUNT = "/clients/{id}/service-account-user";

  static final String PASSWORD = "password";
  static final String ID = "id";
  static final String VALUE = "value";
  static final String INVALID_TOKEN = "error=\"invalid_token\"";
  static final String MISSING_FIELD = "Keycloak answered without %s";
  static final String MISSING_LOCATION = "Keycloak answered a create without a Location";

  static final String HEALTH_INDICATOR_BEAN = "keycloakHealthIndicator";
  static final String HEALTH_ERROR_DETAIL = "error";

  static final String DEFAULT_CONNECT_TIMEOUT = "5s";
  static final String DEFAULT_READ_TIMEOUT = "10s";

  private KeycloakConstants() {}
}
