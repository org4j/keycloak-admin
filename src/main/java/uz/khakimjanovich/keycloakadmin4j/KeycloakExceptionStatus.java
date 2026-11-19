package uz.khakimjanovich.keycloakadmin4j;

import uz.khakimjanovich.error4j.StatusThrowable;

public enum KeycloakExceptionStatus implements StatusThrowable {
  KEYCLOAK_CONFLICT,
  KEYCLOAK_REJECTED,
  KEYCLOAK_UNAVAILABLE;

  @Override
  public String getType() {
    return name();
  }
}
