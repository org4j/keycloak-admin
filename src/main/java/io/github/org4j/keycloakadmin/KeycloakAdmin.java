package io.github.org4j.keycloakadmin;

import io.github.org4j.error.GeneralException;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Client;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Credential;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.EmailVerified;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Enabled;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Failure;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Identified;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Role;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.Secret;
import io.github.org4j.keycloakadmin.KeycloakRepresentations.User;
import java.net.URI;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Keycloak's admin API for one realm: its users, their realm roles, and confidential clients.
 *
 * <p>Every failure is a {@link GeneralException} with a {@link KeycloakExceptionStatus}. Keycloak's
 * own message is a sub-error; its URL never is.
 */
public class KeycloakAdmin {

  private final RestClient keycloak;

  /**
   * @param keycloak based at the realm's admin API ({@code <url>/admin/realms/<realm>}), and
   *     logging in by itself
   */
  public KeycloakAdmin(RestClient keycloak) {
    this.keycloak = keycloak;
  }

  /**
   * A confidential client just created.
   *
   * @param id Keycloak's own id of the client, the one {@link #rotateSecret} takes
   * @param secret its secret
   */
  public record IssuedClient(String id, String secret) {}

  /** Reads one realm role: fails unless the backend can log in and read the admin API. */
  public void ping() {
    call(() -> keycloak.get().uri(KeycloakConstants.ROLES_ONE).retrieve().toBodilessEntity());
  }

  /**
   * Creates an enabled user with a permanent password.
   *
   * @param requiredActions what Keycloak asks of the user at the first login, e.g. {@link
   *     KeycloakConstants#CONFIGURE_TOTP}
   * @return the user's id
   */
  public String createUser(
      String username,
      String email,
      String password,
      boolean emailVerified,
      List<String> requiredActions) {
    var credential = new Credential(KeycloakConstants.PASSWORD, password, false);
    var user =
        new User(username, email, null, true, emailVerified, requiredActions, List.of(credential));
    return call(() -> created(KeycloakConstants.USERS, user, username));
  }

  /**
   * Creates an enabled user named by its e-mail, without a password, and e-mails it a link to
   * choose one and to do the other required actions. If a role or the e-mail fails, the user is
   * removed again, so the invitation can be sent once more.
   *
   * @param requiredActions asked besides {@link KeycloakConstants#UPDATE_PASSWORD}
   * @param realmRoles granted before the e-mail is sent
   * @return the user's id
   */
  public String inviteUser(
      String email, String firstName, List<String> requiredActions, String... realmRoles) {
    var actions =
        Stream.concat(Stream.of(KeycloakConstants.UPDATE_PASSWORD), requiredActions.stream())
            .distinct()
            .toList();
    // E-mail verified already: the only way in is the link e-mailed to this address.
    var user = new User(email, email, firstName, true, true, actions, null);
    return call(
        () -> {
          var id = created(KeycloakConstants.USERS, user, email);
          return removedIfFails(
              KeycloakConstants.USER,
              id,
              () -> {
                for (var role : realmRoles) {
                  realmRole(HttpMethod.POST, id, role);
                }
                keycloak
                    .put()
                    .uri(KeycloakConstants.USER_ACTIONS_EMAIL, id)
                    .body(actions)
                    .retrieve()
                    .toBodilessEntity();
                return id;
              });
        });
  }

  public void deleteUser(String userId) {
    call(() -> keycloak.delete().uri(KeycloakConstants.USER, userId).retrieve().toBodilessEntity());
  }

  /** Disables a user and ends its sessions; access tokens already issued last until they expire. */
  public void disableUser(String userId) {
    call(
        () -> {
          changed(userId, new Enabled(false));
          return loggedOut(userId);
        });
  }

  /** Ends a user's sessions; access tokens already issued last until they expire. */
  public void logoutUser(String userId) {
    call(() -> loggedOut(userId));
  }

  public void enableUser(String userId) {
    call(() -> changed(userId, new Enabled(true)));
  }

  public void markEmailVerified(String userId) {
    call(() -> changed(userId, new EmailVerified(true)));
  }

  /** Grants a user a realm role, named as in Keycloak. */
  public void grantRealmRole(String userId, String role) {
    call(() -> realmRole(HttpMethod.POST, userId, role));
  }

  /** Takes a realm role, named as in Keycloak, from a user. */
  public void revokeRealmRole(String userId, String role) {
    call(() -> realmRole(HttpMethod.DELETE, userId, role));
  }

  /**
   * Creates a client that logs in with client credentials only, its service account holding these
   * realm roles. If a role fails, the client is removed again, so it can be created once more.
   *
   * @param clientId the name it logs in with
   * @throws GeneralException {@code KEYCLOAK_CONFLICT} if a client has that name already
   */
  public IssuedClient createConfidentialClient(String clientId, String... realmRoles) {
    var client = new Client(clientId, true, false, true, false, false);
    return call(
        () -> {
          var id = created(KeycloakConstants.CLIENTS, client, clientId);
          return removedIfFails(
              KeycloakConstants.CLIENT,
              id,
              () -> {
                if (realmRoles.length > 0) {
                  var account =
                      keycloak
                          .get()
                          .uri(KeycloakConstants.CLIENT_SERVICE_ACCOUNT, id)
                          .retrieve()
                          .body(Identified.class);
                  var serviceAccount =
                      required(account == null ? null : account.id(), KeycloakConstants.ID);
                  for (var role : realmRoles) {
                    realmRole(HttpMethod.POST, serviceAccount, role);
                  }
                }
                return new IssuedClient(
                    id, secretOf(keycloak.get().uri(KeycloakConstants.CLIENT_SECRET, id)));
              });
        });
  }

  /**
   * Gives a client a new secret; the old one stops working at once.
   *
   * @param clientInternalId {@link IssuedClient#id()}, not the client's name
   * @return the new secret
   */
  public String rotateSecret(String clientInternalId) {
    return call(
        () -> secretOf(keycloak.post().uri(KeycloakConstants.CLIENT_SECRET, clientInternalId)));
  }

  private String created(String path, Object representation, String name) {
    var created =
        keycloak
            .post()
            .uri(path)
            .body(representation)
            .retrieve()
            .onStatus(
                status -> status.isSameCodeAs(HttpStatus.CONFLICT),
                (request, response) -> {
                  throw new GeneralException(KeycloakExceptionStatus.KEYCLOAK_CONFLICT, name);
                })
            .toBodilessEntity();
    return idOf(created.getHeaders().getLocation());
  }

  private <T> T removedIfFails(String path, String id, Supplier<T> steps) {
    try {
      return steps.get();
    } catch (RuntimeException e) {
      // The step's failure is what the caller learns, even if the removal fails too.
      try {
        keycloak.delete().uri(path, id).retrieve().toBodilessEntity();
      } catch (RuntimeException removal) {
        e.addSuppressed(removal);
      }
      throw e;
    }
  }

  private Object changed(String userId, Object fields) {
    return keycloak
        .put()
        .uri(KeycloakConstants.USER, userId)
        .body(fields)
        .retrieve()
        .toBodilessEntity();
  }

  private Object loggedOut(String userId) {
    return keycloak.post().uri(KeycloakConstants.USER_LOGOUT, userId).retrieve().toBodilessEntity();
  }

  private Object realmRole(HttpMethod method, String userId, String name) {
    var role = keycloak.get().uri(KeycloakConstants.ROLE, name).retrieve().body(Role.class);
    required(role == null ? null : role.id(), KeycloakConstants.ID);
    return keycloak
        .method(method)
        .uri(KeycloakConstants.USER_REALM_ROLES, userId)
        .body(List.of(role))
        .retrieve()
        .toBodilessEntity();
  }

  private static <T> T call(Supplier<T> request) {
    try {
      return request.get();
    } catch (RestClientResponseException e) {
      var status = e.getStatusCode();
      var refused =
          status.is4xxClientError()
              && !status.isSameCodeAs(HttpStatus.UNAUTHORIZED)
              && !status.isSameCodeAs(HttpStatus.FORBIDDEN);
      throw new GeneralException(
              refused
                  ? KeycloakExceptionStatus.KEYCLOAK_REJECTED
                  : KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE)
          .subError(String.valueOf(status.value()), errorMessage(e));
    } catch (RestClientException | OAuth2AuthorizationException e) {
      // Its message names Keycloak's URL: the caller learns only what kind of failure it was.
      throw new GeneralException(KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE)
          .subError(e.getClass().getSimpleName(), null);
    }
  }

  private static String errorMessage(RestClientResponseException e) {
    try {
      var body = e.getResponseBodyAs(Failure.class);
      if (body == null) {
        return e.getStatusText();
      }
      if (body.errorMessage() != null) {
        return body.errorMessage();
      }
      return body.error() != null ? body.error() : e.getStatusText();
    } catch (RuntimeException notJson) {
      return e.getStatusText();
    }
  }

  private static String secretOf(RestClient.RequestHeadersSpec<?> request) {
    var answer = request.retrieve().body(Secret.class);
    return required(answer == null ? null : answer.value(), KeycloakConstants.VALUE);
  }

  private static String required(@Nullable String value, String name) {
    if (value == null) {
      throw broken(KeycloakConstants.MISSING_FIELD.formatted(name));
    }
    return value;
  }

  private static String idOf(@Nullable URI location) {
    if (location == null) {
      throw broken(KeycloakConstants.MISSING_LOCATION);
    }
    var path = location.getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static GeneralException broken(String what) {
    return new GeneralException(KeycloakExceptionStatus.KEYCLOAK_UNAVAILABLE).subError(null, what);
  }
}
