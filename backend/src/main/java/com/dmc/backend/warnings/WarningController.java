package com.dmc.backend.warnings;

import com.dmc.backend.auth.AuthService;
import com.dmc.backend.warnings.DisasterWarning.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
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
                Status.DRAFT,officer,at,officer,at,List.of(),List.of()));
    }
    @PutMapping("/{id}")
    public DisasterWarning update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody DraftRequest body) {
        DisasterWarning current=find(id); future(body);
        if (body.expectedVersion()==null) throw fail(HttpStatus.BAD_REQUEST,"expectedVersion is required.");
        if (!body.expectedVersion().equals(current.version())) throw fail(HttpStatus.CONFLICT,"Warning changed. Reload before saving.");
        if (current.status()!=Status.DRAFT) throw fail(HttpStatus.CONFLICT,"Only drafts can be edited.");
        return mongo.save(new DisasterWarning(current.id(),current.version(),current.reference(),body.hazard().strip(),body.level(),
                body.affectedArea().strip(),body.message().strip(),body.instructions().strip(),body.channels(),body.validUntil(),Status.DRAFT,
                current.createdBy(),current.createdAt(),auth.requireActiveAccount(jwt.getSubject()).displayName(),clock.instant(),current.deliveries(),current.history()));
    }
    public record BroadcastRequest(@NotBlank @Size(max=100) String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, Channel simulateFailureChannel) { }
    public record CancelRequest(@NotBlank @Size(max=100) String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotBlank @Size(max=500) String reason) { }
    @PostMapping("/{id}/broadcast")
    public DisasterWarning broadcast(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody BroadcastRequest body) { return deliver(jwt,id,body,false); }
    @PostMapping("/{id}/retry")
    public DisasterWarning retry(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody BroadcastRequest body) { return deliver(jwt,id,body,true); }
    @PostMapping("/{id}/cancel")
    public DisasterWarning cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody CancelRequest body) {
        DisasterWarning current=find(id);
        String detail=body.reason().strip();
        if (replay(current,body.requestId(),"CANCEL",detail)) return current;
        version(current,body.expectedVersion());
        if (current.status()!=Status.ISSUED) throw fail(HttpStatus.CONFLICT,"Only issued warnings can be cancelled.");
        return store(current,Status.CANCELLED,current.deliveries(),body.requestId(),"CANCEL",detail,jwt);
    }
    private DisasterWarning deliver(Jwt jwt,String id,BroadcastRequest body,boolean retry) {
        DisasterWarning current=find(id);
        String action=retry ? "RETRY" : "BROADCAST";
        String detail="SIMULATION; failed channel: "+body.simulateFailureChannel();
        if (replay(current,body.requestId(),action,detail)) return current;
        version(current,body.expectedVersion());
        if (current.status()!=(retry ? Status.ISSUED : Status.DRAFT)) throw fail(HttpStatus.CONFLICT,"Warning is not in the required state.");
        if (!current.validUntil().isAfter(clock.instant())) throw fail(HttpStatus.CONFLICT,"Warning expired. Prepare a new draft.");
        if (body.simulateFailureChannel()!=null && !current.channels().contains(body.simulateFailureChannel()))
            throw fail(HttpStatus.BAD_REQUEST,"Failure channel must be selected in the warning.");
        if (retry && current.deliveries().stream().noneMatch(d -> d.status().equals("FAILED")))
            throw fail(HttpStatus.CONFLICT,"No failed channels to retry.");
        List<Delivery> deliveries=new ArrayList<>();
        for (Channel channel:current.channels()) {
            Delivery previous=current.deliveries().stream().filter(d -> d.channel()==channel).findFirst().orElse(null);
            if (retry && previous!=null && !previous.status().equals("FAILED")) { deliveries.add(previous); continue; }
            deliveries.add(new Delivery(channel,channel==body.simulateFailureChannel() ? "FAILED" : "SIMULATED_SENT",
                    previous==null ? 1 : previous.attempts()+1,clock.instant()));
        }
        // No external sends: simulated outcomes and issue/history are one versioned document save.
        return store(current,Status.ISSUED,deliveries,body.requestId(),action,detail,jwt);
    }
    private boolean replay(DisasterWarning warning,String requestId,String action,String detail) {
        Event previous=warning.history().stream().filter(e -> e.requestId().equals(requestId)).findFirst().orElse(null);
        if (previous==null) return false;
        if (!previous.action().equals(action) || !previous.detail().equals(detail)) throw fail(HttpStatus.CONFLICT,"Request ID was already used for different input.");
        return true;
    }
    private void version(DisasterWarning warning,Long expected) {
        if (!expected.equals(warning.version())) throw fail(HttpStatus.CONFLICT,"Warning changed. Reload before continuing.");
    }
    private DisasterWarning store(DisasterWarning current,Status status,List<Delivery> deliveries,String requestId,String action,String detail,Jwt jwt) {
        String officer=auth.requireActiveAccount(jwt.getSubject()).displayName(); Instant at=clock.instant();
        List<Event> history=new ArrayList<>(current.history()); history.add(new Event(requestId,action,detail,officer,at));
        return mongo.save(new DisasterWarning(current.id(),current.version(),current.reference(),current.hazard(),current.level(),
                current.affectedArea(),current.message(),current.instructions(),current.channels(),current.validUntil(),status,
                current.createdBy(),current.createdAt(),officer,at,deliveries,history));
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
