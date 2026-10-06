package com.dmc.backend.warnings;

import com.dmc.backend.auth.AuthService;
import com.dmc.backend.warnings.DisasterWarning.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

@RestController
@RequestMapping("/api/dmc/warnings")
@PreAuthorize("hasRole('DMC_OFFICER')")
public class WarningController {
    public record DraftRequest(@NotBlank @Size(max=200) String hazard, @NotNull Level level,
            @NotBlank @Size(max=200) String affectedArea, @NotBlank @Size(max=500) String message,
            @NotBlank @Size(max=300) String instructions, @NotNull @Size(min=1) Set<@NotNull Channel> channels,
            @NotNull Instant validUntil, @PositiveOrZero Long expectedVersion) { }
    public record Page(List<DisasterWarning> items, int page, int size, long totalElements, long totalPages) { }
    private final MongoTemplate mongo;
    private final AuthService auth;
    private final Clock clock;
    public WarningController(MongoTemplate mongo, AuthService auth, Clock clock) { this.mongo=mongo; this.auth=auth; this.clock=clock; }
    @GetMapping
    public Page list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        if (page<0 || size<1 || size>100) throw fail(HttpStatus.BAD_REQUEST, "Use page >= 0 and size 1–100.");
        long total=mongo.count(new Query(), DisasterWarning.class);
        var query=new Query().with(Sort.by(Sort.Direction.DESC,"updatedAt").and(Sort.by("_id"))).skip((long)page*size).limit(size);
        return new Page(mongo.find(query, DisasterWarning.class),page,size,total,(total+size-1)/size);
    }
    @GetMapping("/{id}")
    public DisasterWarning detail(@PathVariable String id) { return find(id); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DisasterWarning create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DraftRequest body) {
        future(body); String officer=auth.requireActiveAccount(jwt.getSubject()).displayName(); Instant at=clock.instant();
        String id=UUID.randomUUID().toString();
        return mongo.insert(new DisasterWarning(id,null,"WR-"+id.substring(0,8).toUpperCase(),body.hazard().strip(),body.level(),
                body.affectedArea().strip(),body.message().strip(),body.instructions().strip(),body.channels(),body.validUntil(),
                Status.DRAFT,officer,at,officer,at));
    }
    @PutMapping("/{id}")
    public DisasterWarning update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DraftRequest body) {
        DisasterWarning current=find(id); future(body);
        if (body.expectedVersion()==null) throw fail(HttpStatus.BAD_REQUEST,"expectedVersion is required.");
        if (!body.expectedVersion().equals(current.version())) throw fail(HttpStatus.CONFLICT,"Warning changed. Reload before saving.");
        if (current.status()!=Status.DRAFT) throw fail(HttpStatus.CONFLICT,"Only drafts can be edited.");
        return mongo.save(new DisasterWarning(current.id(),current.version(),current.reference(),body.hazard().strip(),body.level(),
                body.affectedArea().strip(),body.message().strip(),body.instructions().strip(),body.channels(),body.validUntil(),Status.DRAFT,
                current.createdBy(),current.createdAt(),auth.requireActiveAccount(jwt.getSubject()).displayName(),clock.instant()));
    }
    private void future(DraftRequest body) {
        if (!body.validUntil().isAfter(clock.instant())) throw fail(HttpStatus.BAD_REQUEST,"Warning expiry must be in the future.");
    }
    private DisasterWarning find(String id) {
        var value=mongo.findById(id,DisasterWarning.class);
        if (value==null) throw fail(HttpStatus.NOT_FOUND,"Warning not found.");
        return value;
    }
    private ResponseStatusException fail(HttpStatus status,String message) { return new ResponseStatusException(status,message); }
}
