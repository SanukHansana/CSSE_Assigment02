package com.dmc.backend.email;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Server-only transactional email adapter. Never logs credentials or provider response bodies. */
@Service
public class BrevoEmailClient {

  public record Result(String status, String messageId, String message) {}

  private final HttpClient http;
  private final String key, sender, name;
  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired
  public BrevoEmailClient(
    @Value("${dmc.email.brevo-key:}") String key,
    @Value("${dmc.email.sender:}") String sender,
    @Value("${dmc.email.sender-name:DMC}") String name
  ) {
    this(
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
      key,
      sender,
      name
    );
  }

  public BrevoEmailClient(
    HttpClient http,
    String key,
    String sender,
    String name
  ) {
    this.http = http;
    this.key = key;
    this.sender = sender;
    this.name = name;
  }

  public boolean configured() {
    return !key.isBlank() && !sender.isBlank();
  }

  public Result send(
    List<String> recipients,
    String subject,
    String text,
    String operationId
  ) {
    if (!configured()) return new Result(
      "NOT_CONFIGURED",
      null,
      "Configure the backend Brevo key and verified sender."
    );
    if (recipients.isEmpty() || recipients.size() > 50) return new Result(
      "FAILED",
      null,
      "Use 1–50 recipients."
    );
    var versions = recipients
      .stream()
      .map(address -> Map.of("to", List.of(Map.of("email", address))))
      .toList();
    String html =
      "<html><body style=\"font-family:Arial;color:#173F5F\"><h2>DMC Notification</h2><p style=\"white-space:pre-wrap\">" +
      escape(text) +
      "</p></body></html>";
    var payload = Map.of(
      "sender",
      Map.of("email", sender, "name", name),
      "subject",
      subject,
      "textContent",
      text,
      "htmlContent",
      html,
      "messageVersions",
      versions,
      "headers",
      Map.of(
        "idempotencyKey",
        UUID.nameUUIDFromBytes(
          operationId.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString()
      )
    );
    try {
      var request = HttpRequest.newBuilder(
        URI.create("https://api.brevo.com/v3/smtp/email")
      )
        .timeout(Duration.ofSeconds(20))
        .header("api-key", key)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(
          HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))
        )
        .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 201) {
        var data = json.readTree(response.body());
        String id = data.path("messageId").asText("");
        if (
          id.isBlank() &&
          data.path("messageIds").isArray() &&
          !data.path("messageIds").isEmpty()
        ) id = data.path("messageIds").get(0).asText("");
        return id.isBlank()
          ? unknown()
          : new Result(
              "ACCEPTED",
              id,
              "Brevo accepted the email. Inbox delivery is not confirmed."
            );
      }
      if (
        response.statusCode() >= 400 &&
        response.statusCode() < 500 &&
        json
          .readTree(response.body())
          .path("code")
          .asText("")
          .equals("duplicate_parameter")
      ) return unknown();
      if (
        response.statusCode() >= 400 && response.statusCode() < 500
      ) return new Result(
        "FAILED",
        null,
        "Brevo rejected the request (HTTP " +
          response.statusCode() +
          "). Check sender, API key and sending limits."
      );
      return unknown();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return unknown();
    } catch (Exception error) {
      return unknown();
    }
  }

  private Result unknown() {
    return new Result(
      "UNKNOWN",
      null,
      "The send outcome is uncertain. Check Brevo logs before attempting another send."
    );
  }

  private String escape(String value) {
    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;");
  }
}
