package io.github.org4j.keycloakadmin;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * An app started in a class loader that has no actuator, by {@link KeycloakAutoConfigurationTest}.
 */
public final class WithoutTheActuator {

  @ImportAutoConfiguration(KeycloakAutoConfiguration.class)
  static class App {}

  private WithoutTheActuator() {}

  /** Starts the app and names its {@link KeycloakAdmin} beans. */
  public static String start() {
    try (var context =
        new SpringApplicationBuilder(App.class)
            .web(WebApplicationType.NONE)
            .properties(
                "org4j.keycloak-admin.url=http://localhost:8180",
                "org4j.keycloak-admin.realm=demo",
                "org4j.keycloak-admin.client-id=backend",
                "org4j.keycloak-admin.client-secret=local-only")
            .run()) {
      return String.join(",", context.getBeanNamesForType(KeycloakAdmin.class));
    }
  }
}
