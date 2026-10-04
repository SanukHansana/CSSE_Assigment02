package com.dmc.backend.email;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class BrevoEmailClientTest {

  HttpClient http;
  HttpResponse<String> response;
  BrevoEmailClient client;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() throws Exception {
    http = mock(HttpClient.class);
    response = mock(HttpResponse.class);
    when(
      http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))
    ).thenReturn(response);
    client = new BrevoEmailClient(
      http,
      "fictional-test-key",
      "sender@example.test",
      "DMC"
    );
  }

  @Test
  void requestIsPrivateAuthenticatedEscapedAndUsesUuidIdempotency()
    throws Exception {
    when(response.statusCode()).thenReturn(201);
    when(response.body()).thenReturn(
      "{\"messageIds\":[\"message-1\",\"message-2\"]}"
    );
    assertThat(
      client
        .send(
          List.of("a@example.test", "b@example.test"),
          "Warning",
          "<hazard> & \"quotes\" 'safe'",
          "warning-operation"
        )
        .status()
    ).isEqualTo("ACCEPTED");
    var capture = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(http).send(capture.capture(), any());
    assertThat(capture.getValue().uri().toString()).isEqualTo(
      "https://api.brevo.com/v3/smtp/email"
    );
    assertThat(capture.getValue().headers().firstValue("api-key")).contains(
      "fictional-test-key"
    );
    assertThat(capture.getValue().timeout()).isPresent();
    assertThat(capture.getValue().bodyPublisher()).isPresent();
    var body = body(capture.getValue());
    var data = tools.jackson.databind.json.JsonMapper.builder()
      .build()
      .readTree(body);
    assertThat(data.path("messageVersions").size()).isEqualTo(2);
    assertThat(data.path("messageVersions").get(0).path("to").size()).isEqualTo(
      1
    );
    assertThat(
      data
        .path("messageVersions")
        .get(1)
        .path("to")
        .get(0)
        .path("email")
        .asText()
    ).isEqualTo("b@example.test");
    assertThat(data.path("htmlContent").asText()).contains(
      "&lt;hazard&gt;",
      "&amp;",
      "&quot;",
      "&#39;"
    );
    assertThat(data.path("textContent").asText()).contains("<hazard>");
    assertThat(body).doesNotContain("fictional-test-key");
    assertThatCode(() ->
      UUID.fromString(data.path("headers").path("idempotencyKey").asText())
    ).doesNotThrowAnyException();
  }

  private String body(HttpRequest request) throws Exception {
    var completed = new java.util.concurrent.CompletableFuture<String>();
    var bytes = new java.io.ByteArrayOutputStream();
    request.bodyPublisher().orElseThrow().subscribe(
      new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
        public void onSubscribe(
          java.util.concurrent.Flow.Subscription subscription
        ) {
          subscription.request(Long.MAX_VALUE);
        }

        public void onNext(java.nio.ByteBuffer buffer) {
          while (buffer.hasRemaining()) bytes.write(buffer.get());
        }

        public void onError(Throwable error) {
          completed.completeExceptionally(error);
        }

        public void onComplete() {
          completed.complete(
            bytes.toString(java.nio.charset.StandardCharsets.UTF_8)
          );
        }
      }
    );
    return completed.get(2, java.util.concurrent.TimeUnit.SECONDS);
  }

  @Test
  void singleMessageReferenceMeansAcceptedNotDelivered() {
    when(response.statusCode()).thenReturn(201);
    when(response.body()).thenReturn("{\"messageId\":\"message-1\"}");
    var result = client.send(
      List.of("a@example.test"),
      "Warning",
      "Text",
      "test"
    );
    assertThat(result.messageId()).isEqualTo("message-1");
    assertThat(result.message()).contains("not confirmed");
  }

  @ParameterizedTest
  @ValueSource(
    strings = {
      "{}",
      "{\"messageId\":\"\"}",
      "{\"messageIds\":[]}",
      "not json",
    }
  )
  void missingOrMalformedAcknowledgementIsUncertain(String body) {
    when(response.statusCode()).thenReturn(201);
    when(response.body()).thenReturn(body);
    assertThat(
      client.send(List.of("a@example.test"), "Warning", "Text", "test").status()
    ).isEqualTo("UNKNOWN");
  }

  @ParameterizedTest
  @ValueSource(ints = { 400, 401, 403, 429 })
  void rejectionIsRetryableButProviderBodyNeverLeaks(int status) {
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(
      "{\"code\":\"invalid_parameter\",\"message\":\"fictional-test-key\"}"
    );
    var result = client.send(
      List.of("a@example.test"),
      "Warning",
      "Text",
      "test"
    );
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.message()).doesNotContain("fictional-test-key");
  }

  @ParameterizedTest
  @ValueSource(ints = { 500, 502, 302 })
  void otherHttpOutcome(int status) {
    when(response.statusCode()).thenReturn(status);
    assertThat(
      client.send(List.of("a@example.test"), "Warning", "Text", "test").status()
    ).isEqualTo("UNKNOWN");
  }

  @Test
  void duplicateProviderKeyNeverTriggersAutomaticRetry() {
    when(response.statusCode()).thenReturn(400);
    when(response.body()).thenReturn("{\"code\":\"duplicate_parameter\"}");
    assertThat(
      client.send(List.of("a@example.test"), "Warning", "Text", "test").status()
    ).isEqualTo("UNKNOWN");
  }

  @Test
  void timeoutAndInterruptedSendAreUncertain() throws Exception {
    doThrow(new java.io.IOException("secret response"))
      .when(http)
      .send(any(), any(HttpResponse.BodyHandler.class));
    assertThat(
      client.send(List.of("a@example.test"), "Warning", "Text", "test").status()
    ).isEqualTo("UNKNOWN");
    doThrow(new InterruptedException())
      .when(http)
      .send(any(), any(HttpResponse.BodyHandler.class));
    assertThat(
      client.send(List.of("a@example.test"), "Warning", "Text", "test").status()
    ).isEqualTo("UNKNOWN");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void missingConfigurationAndInvalidRecipientCountNeverUseNetwork() {
    assertThat(
      new BrevoEmailClient(http, "", "sender@example.test", "DMC")
        .send(List.of("a@example.test"), "Warning", "Text", "test")
        .status()
    ).isEqualTo("NOT_CONFIGURED");
    assertThat(
      new BrevoEmailClient(http, "key", "", "DMC").configured()
    ).isFalse();
    assertThat(
      client.send(List.of(), "Warning", "Text", "test").status()
    ).isEqualTo("FAILED");
    assertThat(
      client
        .send(
          Collections.nCopies(51, "a@example.test"),
          "Warning",
          "Text",
          "test"
        )
        .status()
    ).isEqualTo("FAILED");
    verifyNoInteractions(http);
  }
}
