package io.github.org4j.keycloakadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class RetryOnceOnUnauthorizedTest {

  private final RestClient.Builder builder =
      RestClient.builder()
          .baseUrl("http://keycloak")
          .requestInterceptor(new RetryOnceOnUnauthorized());
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final RestClient client = builder.build();

  @Test
  void aRefusedTokenIsTriedOnceMore() {
    server
        .expect(once(), requestTo("http://keycloak/users"))
        .andRespond(refused("Bearer error=\"invalid_token\""));
    server.expect(once(), requestTo("http://keycloak/users")).andRespond(withStatus(HttpStatus.OK));
    assertThat(status()).isEqualTo(200);
    server.verify();
  }

  @Test
  void aRefusalWithoutAChallengeIsTriedOnceMore() {
    server
        .expect(once(), requestTo("http://keycloak/users"))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
    server.expect(once(), requestTo("http://keycloak/users")).andRespond(withStatus(HttpStatus.OK));
    assertThat(status()).isEqualTo(200);
    server.verify();
  }

  @Test
  void aSecondRefusalIsTheAnswer() {
    server
        .expect(twice(), requestTo("http://keycloak/users"))
        .andRespond(refused("Bearer error=\"invalid_token\""));
    assertThat(status()).isEqualTo(401);
    server.verify();
  }

  @Test
  void aRefusalForAnotherReasonIsTheAnswer() {
    server
        .expect(once(), requestTo("http://keycloak/users"))
        .andRespond(refused("Basic realm=\"keycloak\""));
    assertThat(status()).isEqualTo(401);
    server.verify();
  }

  @Test
  void anyOtherAnswerIsPassedOn() {
    server
        .expect(once(), requestTo("http://keycloak/users"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN));
    assertThat(status()).isEqualTo(403);
    server.verify();
  }

  private int status() {
    return client
        .get()
        .uri("/users")
        .exchange((request, response) -> response.getStatusCode().value());
  }

  private static ResponseCreator refused(String challenge) {
    return withStatus(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, challenge);
  }
}
