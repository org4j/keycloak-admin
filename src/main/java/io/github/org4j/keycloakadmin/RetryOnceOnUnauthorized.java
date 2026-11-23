package io.github.org4j.keycloakadmin;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Sends a request once more when Keycloak refuses its token, e.g. after a restart or a rotated
 * secret. Spring Security has dropped the token by then, so the second try logs in again.
 */
class RetryOnceOnUnauthorized implements ClientHttpRequestInterceptor {

  @Override
  public ClientHttpResponse intercept(
      HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
    var response = execution.execute(request, body);
    if (!response.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
      return response;
    }
    var challenge = response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
    if (challenge != null && !challenge.contains(KeycloakConstants.INVALID_TOKEN)) {
      return response;
    }
    response.close();
    return execution.execute(request, body);
  }
}
