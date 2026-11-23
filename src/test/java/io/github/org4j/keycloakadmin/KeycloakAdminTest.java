package io.github.org4j.keycloakadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.org4j.error.GeneralException;
import io.github.org4j.error.StatusThrowable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

class KeycloakAdminTest {

  private final RestClient.Builder builder = RestClient.builder().baseUrl("http://keycloak");
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final KeycloakAdmin admin = new KeycloakAdmin(builder.build());

  @Nested
  class Users {

    @Test
    void aCreatedUserCarriesItsPasswordAndTheRequiredActionsAsked() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              content()
                  .json(
                      """
                      {"username":"aziz","email":"aziz@example.com","enabled":true,
                       "emailVerified":false,"requiredActions":["CONFIGURE_TOTP"],
                       "credentials":[{"type":"password","value":"pw","temporary":false}]}
                      """,
                      JsonCompareMode.STRICT))
          .andRespond(created("users/user-1"));
      assertThat(
              admin.createUser(
                  "aziz",
                  "aziz@example.com",
                  "pw",
                  false,
                  List.of(KeycloakConstants.CONFIGURE_TOTP)))
          .isEqualTo("user-1");
      server.verify();
    }

    @Test
    void anExistingUserIsAConflictNamingIt() {
      server.expect(requestTo("http://keycloak/users")).andRespond(withStatus(HttpStatus.CONFLICT));
      assertThatThrownBy(() -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(KeycloakExceptionStatus.KEYCLOAK_CONFLICT);
                assertThat(e.getParams()).containsExactly("aziz");
              });
    }

    @Test
    void aCreateAnsweredWithoutALocationIsUnavailable() {
      server.expect(requestTo("http://keycloak/users")).andRespond(withStatus(HttpStatus.CREATED));
      assertFails(
          () -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()),
          KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
    }

    @Test
    void markingTheEmailVerifiedChangesOnlyThatField() {
      server
          .expect(requestTo("http://keycloak/users/user-1"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(content().json("{\"emailVerified\":true}", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.markEmailVerified("user-1");
      server.verify();
    }

    @Test
    void disablingChangesOnlyEnabledThenEndsTheSessions() {
      server
          .expect(requestTo("http://keycloak/users/user-1"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(content().json("{\"enabled\":false}", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      server
          .expect(requestTo("http://keycloak/users/user-1/logout"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.disableUser("user-1");
      server.verify();
    }

    @Test
    void enablingChangesOnlyEnabled() {
      server
          .expect(requestTo("http://keycloak/users/user-1"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(content().json("{\"enabled\":true}", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.enableUser("user-1");
      server.verify();
    }

    @Test
    void anUnknownUserIsRejected() {
      server
          .expect(requestTo("http://keycloak/users/user-1"))
          .andExpect(method(HttpMethod.DELETE))
          .andRespond(withStatus(HttpStatus.NOT_FOUND));
      assertFails(() -> admin.deleteUser("user-1"), KeycloakExceptionStatus.KEYCLOAK_REJECTED);
    }
  }

  @Nested
  class Invitations {

    @Test
    void anInviteIsNamedByItsEmailVerifiedAndEmailedEveryRequiredAction() {
      var actions = "[\"UPDATE_PASSWORD\",\"CONFIGURE_TOTP\"]";
      server
          .expect(requestTo("http://keycloak/users"))
          .andExpect(
              content()
                  .json(
                      """
                      {"username":"aziz@example.com","email":"aziz@example.com",
                       "firstName":"Aziza","enabled":true,"emailVerified":true,
                       "requiredActions":%s}
                      """
                          .formatted(actions),
                      JsonCompareMode.STRICT))
          .andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/users/user-1/execute-actions-email"))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(content().json(actions, JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      assertThat(
              admin.inviteUser(
                  "aziz@example.com", "Aziza", List.of(KeycloakConstants.CONFIGURE_TOTP)))
          .isEqualTo("user-1");
      server.verify();
    }

    @Test
    void aPasswordIsAskedForOnceEvenIfTheCallerAsksToo() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andExpect(
              content()
                  .json("{\"requiredActions\":[\"UPDATE_PASSWORD\"]}", JsonCompareMode.LENIENT))
          .andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/users/user-1/execute-actions-email"))
          .andExpect(content().json("[\"UPDATE_PASSWORD\"]", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.inviteUser("aziz@example.com", "Aziza", List.of(KeycloakConstants.UPDATE_PASSWORD));
      server.verify();
    }

    @Test
    void theRolesAreGrantedBeforeTheEmailIsSent() {
      server.expect(requestTo("http://keycloak/users")).andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/roles/operator"))
          .andRespond(role("r-1", "operator"));
      server
          .expect(requestTo("http://keycloak/users/user-1/role-mappings/realm"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      server
          .expect(requestTo("http://keycloak/users/user-1/execute-actions-email"))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.inviteUser("aziz@example.com", "Aziza", List.of(), "operator");
      server.verify();
    }

    @Test
    void anInviteWhoseEmailFailsRemovesTheUserAgain() {
      server.expect(requestTo("http://keycloak/users")).andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/users/user-1/execute-actions-email"))
          .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
      expectRemoved("users/user-1");
      assertFails(
          () -> admin.inviteUser("aziz@example.com", "Aziza", List.of()),
          KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
      server.verify();
    }

    @Test
    void anInviteWithAnUnknownRoleRemovesTheUserAgain() {
      server.expect(requestTo("http://keycloak/users")).andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/roles/no-such-role"))
          .andRespond(withStatus(HttpStatus.NOT_FOUND));
      expectRemoved("users/user-1");
      assertFails(
          () -> admin.inviteUser("aziz@example.com", "Aziza", List.of(), "no-such-role"),
          KeycloakExceptionStatus.KEYCLOAK_REJECTED);
      server.verify();
    }

    @Test
    void anInviteWhoseRoleComesBackBrokenRemovesTheUserAgain() {
      server.expect(requestTo("http://keycloak/users")).andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/roles/operator"))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
      expectRemoved("users/user-1");
      assertFails(
          () -> admin.inviteUser("aziz@example.com", "Aziza", List.of(), "operator"),
          KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
      server.verify();
    }

    @Test
    void theFailureIsWhatTheCallerLearnsEvenIfTheRemovalFailsToo() {
      server.expect(requestTo("http://keycloak/users")).andRespond(created("users/user-1"));
      server
          .expect(requestTo("http://keycloak/users/user-1/execute-actions-email"))
          .andRespond(withStatus(HttpStatus.BAD_REQUEST));
      server
          .expect(requestTo("http://keycloak/users/user-1"))
          .andExpect(method(HttpMethod.DELETE))
          .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
      assertFails(
          () -> admin.inviteUser("aziz@example.com", "Aziza", List.of()),
          KeycloakExceptionStatus.KEYCLOAK_REJECTED);
      server.verify();
    }
  }

  @Nested
  class Roles {

    @Test
    void aRoleIsGrantedAsKeycloakNamesIt() {
      server.expect(requestTo("http://keycloak/roles/partner")).andRespond(role("r-1", "partner"));
      server
          .expect(requestTo("http://keycloak/users/user-1/role-mappings/realm"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              content().json("[{\"id\":\"r-1\",\"name\":\"partner\"}]", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.grantRealmRole("user-1", "partner");
      server.verify();
    }

    @Test
    void aRoleIsRevokedAsKeycloakNamesIt() {
      server.expect(requestTo("http://keycloak/roles/partner")).andRespond(role("r-1", "partner"));
      server
          .expect(requestTo("http://keycloak/users/user-1/role-mappings/realm"))
          .andExpect(method(HttpMethod.DELETE))
          .andExpect(
              content().json("[{\"id\":\"r-1\",\"name\":\"partner\"}]", JsonCompareMode.STRICT))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      admin.revokeRealmRole("user-1", "partner");
      server.verify();
    }

    @Test
    void anUnknownRoleIsRejected() {
      server
          .expect(requestTo("http://keycloak/roles/no-such-role"))
          .andRespond(withStatus(HttpStatus.NOT_FOUND));
      assertFails(
          () -> admin.grantRealmRole("user-1", "no-such-role"),
          KeycloakExceptionStatus.KEYCLOAK_REJECTED);
    }
  }

  @Nested
  class Clients {

    @Test
    void aClientGetsOnlyTheClientCredentialsGrantItsRolesAndASecret() {
      server
          .expect(requestTo("http://keycloak/clients"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(
              content()
                  .json(
                      """
                      {"clientId":"shop-1","enabled":true,"publicClient":false,
                       "serviceAccountsEnabled":true,"standardFlowEnabled":false,
                       "directAccessGrantsEnabled":false}
                      """,
                      JsonCompareMode.STRICT))
          .andRespond(created("clients/c-1"));
      server
          .expect(requestTo("http://keycloak/clients/c-1/service-account-user"))
          .andRespond(withSuccess("{\"id\":\"sa-1\"}", MediaType.APPLICATION_JSON));
      server
          .expect(requestTo("http://keycloak/roles/merchant"))
          .andRespond(role("r-1", "merchant"));
      server
          .expect(requestTo("http://keycloak/users/sa-1/role-mappings/realm"))
          .andRespond(withStatus(HttpStatus.NO_CONTENT));
      server
          .expect(requestTo("http://keycloak/clients/c-1/client-secret"))
          .andExpect(method(HttpMethod.GET))
          .andRespond(withSuccess("{\"value\":\"s3cret\"}", MediaType.APPLICATION_JSON));
      assertThat(admin.createConfidentialClient("shop-1", "merchant"))
          .isEqualTo(new KeycloakAdmin.IssuedClient("c-1", "s3cret"));
      server.verify();
    }

    @Test
    void aClientWithoutRolesSkipsItsServiceAccount() {
      server.expect(requestTo("http://keycloak/clients")).andRespond(created("clients/c-1"));
      server
          .expect(requestTo("http://keycloak/clients/c-1/client-secret"))
          .andRespond(withSuccess("{\"value\":\"s3cret\"}", MediaType.APPLICATION_JSON));
      assertThat(admin.createConfidentialClient("shop-1").secret()).isEqualTo("s3cret");
      server.verify();
    }

    @Test
    void anExistingClientIsAConflictNamingIt() {
      server
          .expect(requestTo("http://keycloak/clients"))
          .andRespond(withStatus(HttpStatus.CONFLICT));
      assertThatThrownBy(() -> admin.createConfidentialClient("shop-1"))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(KeycloakExceptionStatus.KEYCLOAK_CONFLICT);
                assertThat(e.getParams()).containsExactly("shop-1");
              });
    }

    @Test
    void aClientWhoseRoleFailsIsRemovedAgainSoItCanBeCreatedOnceMore() {
      server.expect(requestTo("http://keycloak/clients")).andRespond(created("clients/c-1"));
      server
          .expect(requestTo("http://keycloak/clients/c-1/service-account-user"))
          .andRespond(withSuccess("{\"id\":\"sa-1\"}", MediaType.APPLICATION_JSON));
      server
          .expect(requestTo("http://keycloak/roles/no-such-role"))
          .andRespond(withStatus(HttpStatus.NOT_FOUND));
      expectRemoved("clients/c-1");
      assertFails(
          () -> admin.createConfidentialClient("shop-1", "no-such-role"),
          KeycloakExceptionStatus.KEYCLOAK_REJECTED);
      server.verify();
    }

    @Test
    void aClientWhoseSecretCannotBeReadIsRemovedAgain() {
      server.expect(requestTo("http://keycloak/clients")).andRespond(created("clients/c-1"));
      server
          .expect(requestTo("http://keycloak/clients/c-1/client-secret"))
          .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
      expectRemoved("clients/c-1");
      assertFails(
          () -> admin.createConfidentialClient("shop-1"),
          KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
      server.verify();
    }

    @Test
    void aRotatedSecretIsTheNewOne() {
      server
          .expect(requestTo("http://keycloak/clients/c-1/client-secret"))
          .andExpect(method(HttpMethod.POST))
          .andRespond(withSuccess("{\"value\":\"n3w\"}", MediaType.APPLICATION_JSON));
      assertThat(admin.rotateSecret("c-1")).isEqualTo("n3w");
    }
  }

  @Nested
  class Failures {

    @Test
    void refusedInputIsRejectedWithKeycloaksMessage() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andRespond(
              withStatus(HttpStatus.BAD_REQUEST)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"errorMessage\":\"invalidPasswordMinLengthMessage\"}"));
      assertThatThrownBy(() -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(KeycloakExceptionStatus.KEYCLOAK_REJECTED);
                assertThat(e.getSubErrors())
                    .containsExactly(
                        new GeneralException.SubError("400", "invalidPasswordMinLengthMessage"));
              });
    }

    @Test
    void anOAuthStyleErrorBodyIsReadToo() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andRespond(
              withStatus(HttpStatus.BAD_REQUEST)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"error\":\"invalid_request\"}"));
      assertThatThrownBy(() -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e ->
                  assertThat(e.getSubErrors())
                      .containsExactly(new GeneralException.SubError("400", "invalid_request")));
    }

    @Test
    void aBodyThatIsNotJsonGivesTheStatusText() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>proxy</html>"));
      assertThatThrownBy(() -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e ->
                  assertThat(e.getSubErrors())
                      .containsExactly(new GeneralException.SubError("502", "Bad Gateway")));
    }

    @Test
    void aServerErrorIsUnavailableWithKeycloaksMessage() {
      server
          .expect(requestTo("http://keycloak/roles/partner"))
          .andRespond(
              withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                  .contentType(MediaType.APPLICATION_JSON)
                  .body("{\"errorMessage\":\"unknown_error\"}"));
      assertThatThrownBy(() -> admin.grantRealmRole("user-1", "partner"))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
                assertThat(e.getSubErrors())
                    .containsExactly(new GeneralException.SubError("500", "unknown_error"));
              });
    }

    @Test
    void aBackendNotAllowedTheAdminApiIsUnavailableNotRejected() {
      server
          .expect(requestTo("http://keycloak/users"))
          .andRespond(withStatus(HttpStatus.FORBIDDEN));
      assertFails(
          () -> admin.createUser("aziz", "aziz@example.com", "pw", false, List.of()),
          KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
    }

    @Test
    void noAnswerIsUnavailableAndNamesOnlyTheKindOfFailure() {
      server
          .expect(requestTo("http://keycloak/clients/c-1/client-secret"))
          .andRespond(withException(new IOException("Connection refused: http://keycloak")));
      assertThatThrownBy(() -> admin.rotateSecret("c-1"))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus()).isEqualTo(KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE);
                assertThat(e.getSubErrors())
                    .containsExactly(
                        new GeneralException.SubError("ResourceAccessException", null));
              });
    }
  }

  private static ResponseCreator created(String path) {
    return withStatus(HttpStatus.CREATED).location(URI.create("http://keycloak/" + path));
  }

  private static ResponseCreator role(String id, String name) {
    return withSuccess(
        "{\"id\":\"%s\",\"name\":\"%s\",\"composite\":false}".formatted(id, name),
        MediaType.APPLICATION_JSON);
  }

  private void expectRemoved(String path) {
    server
        .expect(requestTo("http://keycloak/" + path))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));
  }

  private static void assertFails(ThrowingCallable call, StatusThrowable status) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            GeneralException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
  }
}
