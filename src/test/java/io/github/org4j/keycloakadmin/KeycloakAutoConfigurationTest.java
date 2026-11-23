package io.github.org4j.keycloakadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.org4j.error.GeneralException;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KeycloakAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(KeycloakAutoConfiguration.class));

  private static final String[] SETTINGS = {
    "org4j.keycloak-admin.realm=demo",
    "org4j.keycloak-admin.client-id=backend",
    "org4j.keycloak-admin.client-secret=local-only"
  };

  @Nested
  class Properties {

    @Test
    void withoutAUrlTheLibraryStaysOff() {
      runner.run(context -> assertThat(context).doesNotHaveBean(KeycloakAdmin.class));
    }

    @Test
    void aCompleteConfigurationGivesTheAdminAndItsHealth() {
      runner
          .withPropertyValues(SETTINGS)
          .withPropertyValues("org4j.keycloak-admin.url=http://localhost:8180")
          .run(
              context -> {
                assertThat(context).hasSingleBean(KeycloakAdmin.class);
                assertThat(context).hasBean("keycloakHealthIndicator");
              });
    }

    @Test
    void aMissingRealmStopsStartup() {
      runner
          .withPropertyValues(
              "org4j.keycloak-admin.url=http://localhost:8180",
              "org4j.keycloak-admin.client-id=backend",
              "org4j.keycloak-admin.client-secret=local-only")
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
          .withPropertyValues(
              "org4j.keycloak-admin.url=http://localhost:8180",
              "org4j.keycloak-admin.realm=demo",
              "org4j.keycloak-admin.client-id=backend")
          .run(
              context ->
                  assertThat(context)
                      .getFailure()
                      .rootCause()
                      .hasMessageContaining("org4j.keycloak-admin")
                      .hasMessageContaining("clientSecret"));
    }

    @Test
    void anAppWithoutTheActuatorStarts() throws Exception {
      var urls =
          Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator, -1))
              .filter(entry -> !entry.contains("spring-boot-health"))
              .map(KeycloakAutoConfigurationTest::url)
              .toArray(URL[]::new);
      try (var withoutActuator = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
        assertThat(
                withoutActuator.getResource(
                    HealthIndicator.class.getName().replace('.', '/') + ".class"))
            .isNull();
        var thread = Thread.currentThread();
        var original = thread.getContextClassLoader();
        thread.setContextClassLoader(withoutActuator);
        try {
          var started =
              withoutActuator
                  .loadClass(WithoutTheActuator.class.getName())
                  .getMethod("start")
                  .invoke(null);
          assertThat(started).isEqualTo("keycloakAdmin");
        } finally {
          thread.setContextClassLoader(original);
        }
      }
    }
  }

  @Nested
  class Login {

    @Test
    void aRefusedTokenIsDroppedAndTheCallTriedOnceMore() throws IOException {
      var tokens = new AtomicInteger();
      var users = new AtomicInteger();
      var keycloak =
          keycloak(
              tokens,
              exchange -> {
                if (users.incrementAndGet() == 1) {
                  exchange
                      .getResponseHeaders()
                      .add("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                  exchange.sendResponseHeaders(401, -1);
                } else {
                  exchange.getResponseHeaders().add("Location", "http://keycloak/users/u-1");
                  exchange.sendResponseHeaders(201, -1);
                }
                exchange.close();
              });
      try {
        runner
            .withPropertyValues(SETTINGS)
            .withPropertyValues("org4j.keycloak-admin.url=" + url(keycloak))
            .run(
                context -> {
                  var id =
                      context
                          .getBean(KeycloakAdmin.class)
                          .createUser("aziz", "aziz@example.com", "pw", false, List.of());
                  assertThat(id).isEqualTo("u-1");
                  assertThat(tokens).hasValue(2);
                  assertThat(users).hasValue(2);
                });
      } finally {
        keycloak.stop(0);
      }
    }

    @Test
    void theReadTimeoutIsApplied() throws IOException {
      try (var silent = new ServerSocket(0)) {
        runner
            .withPropertyValues(SETTINGS)
            .withPropertyValues(
                "org4j.keycloak-admin.url=http://localhost:" + silent.getLocalPort(),
                "org4j.keycloak-admin.read-timeout=300ms")
            .run(
                context -> {
                  var started = System.nanoTime();
                  assertThatThrownBy(() -> context.getBean(KeycloakAdmin.class).rotateSecret("c-1"))
                      .isInstanceOfSatisfying(
                          GeneralException.class,
                          e ->
                              assertThat(e.getStatus())
                                  .isEqualTo(KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE));
                  assertThat(Duration.ofNanos(System.nanoTime() - started))
                      .isLessThan(Duration.ofSeconds(5));
                });
      }
    }
  }

  /** A Keycloak that issues a new token on every login and answers the realm's users with these. */
  private static HttpServer keycloak(AtomicInteger tokens, HttpHandler users) throws IOException {
    var keycloak = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    keycloak.createContext(
        "/realms/demo/protocol/openid-connect/token",
        exchange -> {
          var token =
              "{\"access_token\":\"t%d\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                  .formatted(tokens.incrementAndGet())
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, token.length);
          exchange.getResponseBody().write(token);
          exchange.close();
        });
    keycloak.createContext("/admin/realms/demo/users", users);
    keycloak.start();
    return keycloak;
  }

  private static String url(HttpServer keycloak) {
    return "http://localhost:" + keycloak.getAddress().getPort();
  }

  private static URL url(String classpathEntry) {
    try {
      return Path.of(classpathEntry).toUri().toURL();
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(classpathEntry, e);
    }
  }
}
