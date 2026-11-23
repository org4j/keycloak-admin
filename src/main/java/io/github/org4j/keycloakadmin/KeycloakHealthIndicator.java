package io.github.org4j.keycloakadmin;

import io.github.org4j.error.GeneralException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

class KeycloakHealthIndicator implements HealthIndicator {

  private final KeycloakAdmin admin;

  KeycloakHealthIndicator(KeycloakAdmin admin) {
    this.admin = admin;
  }

  @Override
  public Health health() {
    try {
      admin.ping();
      return Health.up().build();
    } catch (GeneralException e) {
      return Health.down()
          .withDetail(KeycloakConstants.HEALTH_ERROR_DETAIL, e.getStatus().getType())
          .build();
    }
  }
}
