package io.github.org4j.keycloakadmin;

import io.github.org4j.error.StatusThrowable;

public enum KeycloakExceptionStatus implements StatusThrowable {
  KEYCLOAK_CONFLICT,
  KEYCLOAK_REJECTED,
  KEYCLOAK_UNAVAILABLE;

  @Override
  public String getType() {
    return name();
  }
}
