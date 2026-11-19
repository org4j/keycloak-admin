package io.github.org4j.keycloakadmin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KeycloakPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(KeycloakAutoConfiguration.class))
          .withPropertyValues(
              "org4j.keycloak-admin.url=http://localhost:8180",
              "org4j.keycloak-admin.client-id=backend");

  @Test
  void aCompleteConfigurationStarts() {
    runner
        .withPropertyValues(
            "org4j.keycloak-admin.realm=demo", "org4j.keycloak-admin.client-secret=local-only")
        .run(context -> assertThat(context).hasSingleBean(KeycloakAdmin.class));
  }

  @Test
  void aMissingRealmStopsStartup() {
    runner
        .withPropertyValues("org4j.keycloak-admin.client-secret=local-only")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("org4j.keycloak-admin")
                    .hasMessageContaining("realm"));
  }

  @Test
  void aMissingSecretStopsStartup() {
    runner
        .withPropertyValues("org4j.keycloak-admin.realm=demo")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("org4j.keycloak-admin")
                    .hasMessageContaining("clientSecret"));
  }
}
