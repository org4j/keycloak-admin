package io.github.org4j.keycloakadmin;

import io.github.org4j.error.StatusThrowable;

/** The codes of the {@code GeneralException}s {@link KeycloakAdmin} throws. */
public enum KeycloakExceptionStatus implements StatusThrowable {
  /** 409: what was to be created already exists; the parameter names it. */
  KEYCLOAK_CONFLICT,
  /** 400: Keycloak refused the input, e.g. its password policy or an unknown role. */
  KEYCLOAK_REJECTED,
  /** 503: Keycloak failed, didn't answer, refused the backend's login, or answered broken. */
  KEYCLOAK_UNAVAILABLE;

  @Override
  public String getType() {
    return name();
  }
}
