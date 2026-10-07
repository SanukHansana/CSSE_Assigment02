package com.dmc.backend.support;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dmc.backend.auth.*;
import com.mongodb.*;
import com.mongodb.client.ClientSession;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.*;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.oauth2.jwt.Jwt;

/** In-memory persistence boundary; exercises real workflow code and transaction manager, never Atlas. */
public class WorkflowFixtures {

  public final Clock clock = Clock.fixed(
    Instant.parse("2026-10-13T12:00:00Z"),
    ZoneOffset.UTC
  );
  public final MongoTemplate mongo = mock(MongoTemplate.class);
  public final AuthService auth = mock(AuthService.class);
  public final MongoDatabaseFactory factory = mock(MongoDatabaseFactory.class);
  public final ClientSession session = mock(ClientSession.class);
  public final Map<String, Object> records = new HashMap<>();
  private Map<String, Object> transactionSnapshot;

  @SuppressWarnings("unchecked")
  public WorkflowFixtures() {
    when(mongo.findById(anyString(), any(Class.class))).thenAnswer(call ->
      records.get(key(call.getArgument(1), call.getArgument(0)))
    );
    when(mongo.insert(any(Object.class))).thenAnswer(call ->
      put(withVersion(call.getArgument(0), 0L))
    );
    when(mongo.save(any(Object.class))).thenAnswer(call -> {
      Object value = call.getArgument(0);
      Object existing = records.get(key(value.getClass(), field(value, "id")));
      if (
        existing != null &&
        !Objects.equals(field(existing, "version"), field(value, "version"))
      ) throw new OptimisticLockingFailureException("Concurrent write");
      return put(
        withVersion(
          value,
          existing == null ? 0L : (Long) field(existing, "version") + 1
        )
      );
    });
    when(mongo.find(any(Query.class), any(Class.class))).thenAnswer(call ->
      matching(call.getArgument(0), call.getArgument(1))
    );
    when(mongo.count(any(Query.class), any(Class.class))).thenAnswer(
      call -> (long) matching(call.getArgument(0), call.getArgument(1)).size()
    );
    when(factory.getSession(any(ClientSessionOptions.class))).thenReturn(
      session
    );
    when(factory.withSession(any(ClientSession.class))).thenReturn(factory);
    when(factory.getExceptionTranslator()).thenReturn(
      new MongoExceptionTranslator()
    );
    when(session.getOptions()).thenReturn(
      ClientSessionOptions.builder().build()
    );
    doAnswer(call -> {
      transactionSnapshot = new HashMap<>(records);
      return null;
    })
      .when(session)
      .startTransaction(any(TransactionOptions.class));
    doAnswer(call -> {
      records.clear();
      if (transactionSnapshot != null) records.putAll(transactionSnapshot);
      return null;
    })
      .when(session)
      .abortTransaction();
    doAnswer(call -> {
      transactionSnapshot = new HashMap<>(records);
      return null;
    })
      .when(session)
      .startTransaction();
    account("officer", AccountRole.DMC_OFFICER);
  }

  public UserAccount account(String id, AccountRole... roles) {
    UserAccount account = new UserAccount(
      id,
      id + "@example.test",
      "Test " + id,
      "hash",
      Set.of(roles),
      true,
      clock.instant()
    );
    when(auth.requireActiveAccount(id)).thenReturn(account);
    return account;
  }

  public Jwt jwt(String subject) {
    return Jwt.withTokenValue("test-only")
      .header("alg", "none")
      .subject(subject)
      .build();
  }

  public <T> T put(T value) {
    records.put(key(value.getClass(), field(value, "id")), value);
    return value;
  }

  public <T> T get(Class<T> type, String id) {
    return type.cast(records.get(key(type, id)));
  }

  private String key(Class<?> type, Object id) {
    return type.getName() + ":" + id;
  }

  private List<Object> matching(Query query, Class<?> type) {
    return records
      .values()
      .stream()
      .filter(type::isInstance)
      .filter(value ->
        query
          .getQueryObject()
          .entrySet()
          .stream()
          .allMatch(entry -> {
            if (entry.getKey().equals("offers.volunteerId")) return (
              (List<?>) field(value, "offers")
            )
              .stream()
              .anyMatch(offer ->
                Objects.equals(field(offer, "volunteerId"), entry.getValue())
              );
            return Objects.equals(
              field(
                value,
                entry.getKey().equals("_id") ? "id" : entry.getKey()
              ),
              entry.getValue()
            );
          })
      )
      .toList();
  }

  private static Object field(Object value, String name) {
    try {
      return value.getClass().getMethod(name).invoke(value);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T withVersion(T value, Long version) {
    try {
      RecordComponent[] components = value.getClass().getRecordComponents();
      Object[] arguments = new Object[components.length];
      Class<?>[] types = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        types[i] = components[i].getType();
        arguments[i] = components[i].getName().equals("version")
          ? version
          : components[i].getAccessor().invoke(value);
      }
      return (T) value
        .getClass()
        .getDeclaredConstructor(types)
        .newInstance(arguments);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }
}
