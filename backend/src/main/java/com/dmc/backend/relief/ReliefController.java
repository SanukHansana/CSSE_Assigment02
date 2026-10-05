package com.dmc.backend.relief;

import com.dmc.backend.auth.AuthService;
import com.dmc.backend.relief.ReliefModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Reuses current database roles; individual Git author names never grant access. */
@RestController
@RequestMapping("/api/dmc/relief")
@PreAuthorize("hasRole('DMC_OFFICER')")
public class ReliefController {
    public record ResourceRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=60) String type, @NotBlank @Size(max=200) String location,
            @NotNull @PositiveOrZero Long availableQuantity, @NotBlank @Size(max=30) String unit,
            @PositiveOrZero Long expectedVersion) { }
    public record ShelterRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=200) String location, @NotNull @Positive Long capacity,
            @NotNull @PositiveOrZero Long occupancy, @NotNull ShelterStatus status,
            @PositiveOrZero Long expectedVersion) { }
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) { }
    private final MongoTemplate mongo;
    private final AuthService auth;
    private final Clock clock;
    public ReliefController(MongoTemplate mongo, AuthService auth, Clock clock) {
        this.mongo=mongo; this.auth=auth; this.clock=clock;
    }
    @GetMapping("/resources")
    public Page<Resource> resources(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return list(Resource.class, page, size); }
    @GetMapping("/resources/{id}")
    public Resource resource(@PathVariable String id) { return find(id, Resource.class); }
    @PostMapping("/resources")
    @ResponseStatus(HttpStatus.CREATED)
    public Resource createResource(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ResourceRequest body) {
        return mongo.insert(new Resource(UUID.randomUUID().toString(), null, body.name().strip(),
                body.type().strip(), body.location().strip(), body.availableQuantity(), body.unit().strip(),
                auth.requireActiveAccount(jwt.getSubject()).displayName(), clock.instant()));
    }
    @PutMapping("/resources/{id}")
    public Resource updateResource(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ResourceRequest body) {
        Resource saved=find(id, Resource.class); version(body.expectedVersion(), saved.version());
        return mongo.save(new Resource(saved.id(), saved.version(), body.name().strip(), body.type().strip(),
                body.location().strip(), body.availableQuantity(), body.unit().strip(),
                auth.requireActiveAccount(jwt.getSubject()).displayName(), clock.instant()));
    }
    @GetMapping("/shelters")
    public Page<Shelter> shelters(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) { return list(Shelter.class, page, size); }
    @GetMapping("/shelters/{id}")
    public Shelter shelter(@PathVariable String id) { return find(id, Shelter.class); }
    @PostMapping("/shelters")
    @ResponseStatus(HttpStatus.CREATED)
    public Shelter createShelter(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ShelterRequest body) {
        validateShelter(body);
        return mongo.insert(new Shelter(UUID.randomUUID().toString(), null, body.name().strip(),
                body.location().strip(), body.capacity(), body.occupancy(), body.status(),
                auth.requireActiveAccount(jwt.getSubject()).displayName(), clock.instant()));
    }
    @PutMapping("/shelters/{id}")
    public Shelter updateShelter(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody ShelterRequest body) {
        Shelter saved=find(id, Shelter.class); version(body.expectedVersion(), saved.version()); validateShelter(body);
        return mongo.save(new Shelter(saved.id(), saved.version(), body.name().strip(), body.location().strip(),
                body.capacity(), body.occupancy(), body.status(),
                auth.requireActiveAccount(jwt.getSubject()).displayName(), clock.instant()));
    }
    private void validateShelter(ShelterRequest body) {
        if (body.occupancy()>body.capacity()) throw failure(HttpStatus.BAD_REQUEST, "Occupancy cannot exceed capacity.");
        if (body.status()==ShelterStatus.FULL && !body.occupancy().equals(body.capacity()))
            throw failure(HttpStatus.BAD_REQUEST, "A FULL shelter must have occupancy equal to capacity.");
        if (body.status()==ShelterStatus.OPEN && body.occupancy().equals(body.capacity()))
            throw failure(HttpStatus.BAD_REQUEST, "A shelter at capacity must be FULL or CLOSED.");
    }
    private void version(Long expected, Long actual) {
        if (expected==null) throw failure(HttpStatus.BAD_REQUEST, "expectedVersion is required for updates.");
        if (!expected.equals(actual)) throw failure(HttpStatus.CONFLICT, "The record changed. Reload before updating.");
    }
    private <T> T find(String id, Class<T> type) {
        T value=mongo.findById(id, type);
        if (value==null) throw failure(HttpStatus.NOT_FOUND, "Relief record not found.");
        return value;
    }
    private <T> Page<T> list(Class<T> type, int page, int size) {
        if (page<0 || size<1 || size>100) throw failure(HttpStatus.BAD_REQUEST, "Use page >= 0 and size between 1 and 100.");
        long total=mongo.count(new Query(), type);
        Query query=new Query().with(Sort.by("name").ascending().and(Sort.by("_id").ascending()))
                .skip((long)page*size).limit(size);
        return new Page<>(mongo.find(query, type), page, size, total, (total+size-1)/size);
    }
    private ResponseStatusException failure(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
