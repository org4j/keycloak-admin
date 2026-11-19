package uz.khakimjanovich.keycloakadmin4j;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KeycloakPropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(KeycloakAutoConfiguration.class))
          .withPropertyValues(
              "keycloak-admin4j.url=http://localhost:8180", "keycloak-admin4j.client-id=backend");

  @Test
  void aCompleteConfigurationStarts() {
    runner
        .withPropertyValues(
            "keycloak-admin4j.realm=demo", "keycloak-admin4j.client-secret=local-only")
        .run(context -> assertThat(context).hasSingleBean(KeycloakAdmin.class));
  }

  @Test
  void aMissingRealmStopsStartup() {
    runner
        .withPropertyValues("keycloak-admin4j.client-secret=local-only")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("keycloak-admin4j")
                    .hasMessageContaining("realm"));
  }

  @Test
  void aMissingSecretStopsStartup() {
    runner
        .withPropertyValues("keycloak-admin4j.realm=demo")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("keycloak-admin4j")
                    .hasMessageContaining("clientSecret"));
  }
}
