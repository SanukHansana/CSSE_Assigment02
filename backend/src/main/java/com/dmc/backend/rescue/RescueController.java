package com.dmc.backend.rescue;
import com.dmc.backend.auth.AuthService;
import com.dmc.backend.rescue.RescueModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
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
@RequestMapping("/api/dmc/rescue")
@PreAuthorize("hasRole('DMC_OFFICER')")
public class RescueController {
    public record IncidentRequest(@NotNull IncidentType type, @NotNull Priority priority,
            @NotBlank @Size(max=1000) String description, @NotBlank @Size(max=200) String location,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @Positive Long peopleNeedingAssistance, @PositiveOrZero Long expectedVersion) { }
    public record TeamRequest(@NotBlank @Size(max=100) String name, @NotNull IncidentType specialization,
            @NotBlank @Size(max=200) String location,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull TeamStatus status, @PositiveOrZero Long expectedVersion) { }
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) { }
    private final MongoTemplate mongo; private final AuthService auth; private final Clock clock;
    public RescueController(MongoTemplate mongo,AuthService auth,Clock clock) {this.mongo=mongo;this.auth=auth;this.clock=clock;}
    @GetMapping("/incidents")
    public Page<Incident> incidents(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) {return list(Incident.class,page,size);}
    @GetMapping("/teams")
    public Page<Team> teams(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) {return list(Team.class,page,size);}
    @GetMapping("/incidents/{id}") public Incident incident(@PathVariable String id) {return find(id,Incident.class);}
    @GetMapping("/teams/{id}") public Team team(@PathVariable String id) {return find(id,Team.class);}
    @PostMapping("/incidents") @ResponseStatus(HttpStatus.CREATED)
    public Incident createIncident(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody IncidentRequest body) {
        String officer=officer(jwt);String id=UUID.randomUUID().toString();var at=clock.instant();
        return mongo.insert(new Incident(id,null,"INC-"+id.substring(0,8).toUpperCase(),body.type(),body.priority(),body.description().strip(),
                body.location().strip(),body.latitude(),body.longitude(),body.peopleNeedingAssistance(),IncidentStatus.OPEN,officer,at,
                List.of(new Event(UUID.randomUUID().toString(),"Incident created",officer,at))));
    }
    @PutMapping("/incidents/{id}")
    public Incident updateIncident(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody IncidentRequest body) {
        Incident current=find(id,Incident.class);version(body.expectedVersion(),current.version());
        if(current.status()!=IncidentStatus.OPEN) throw fail(HttpStatus.CONFLICT,"Only open incidents can be edited.");
        String officer=officer(jwt);var at=clock.instant();List<Event> history=new ArrayList<>(current.history());history.add(new Event(UUID.randomUUID().toString(),"Incident details updated",officer,at));
        return mongo.save(new Incident(current.id(),current.version(),current.reference(),body.type(),body.priority(),body.description().strip(),
                body.location().strip(),body.latitude(),body.longitude(),body.peopleNeedingAssistance(),current.status(),officer,at,history));
    }
    @PostMapping("/teams") @ResponseStatus(HttpStatus.CREATED)
    public Team createTeam(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody TeamRequest body) {
        availableStatus(body.status());
        return mongo.insert(new Team(UUID.randomUUID().toString(),null,body.name().strip(),body.specialization(),body.location().strip(),
                body.latitude(),body.longitude(),body.status(),null,officer(jwt),clock.instant()));
    }
    @PutMapping("/teams/{id}")
    public Team updateTeam(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody TeamRequest body) {
        Team current=find(id,Team.class);version(body.expectedVersion(),current.version());availableStatus(body.status());
        if(current.activeIncidentId()!=null) throw fail(HttpStatus.CONFLICT,"Assigned teams must be updated through their operation.");
        return mongo.save(new Team(current.id(),current.version(),body.name().strip(),body.specialization(),body.location().strip(),body.latitude(),body.longitude(),body.status(),null,officer(jwt),clock.instant()));
    }
    private void availableStatus(TeamStatus status) {if(status!=TeamStatus.AVAILABLE&&status!=TeamStatus.UNAVAILABLE) throw fail(HttpStatus.BAD_REQUEST,"Choose AVAILABLE or UNAVAILABLE; deployment is controlled by assignment.");}
    private String officer(Jwt jwt) {return auth.requireActiveAccount(jwt.getSubject()).displayName();}
    private void version(Long expected,Long actual) {if(expected==null) throw fail(HttpStatus.BAD_REQUEST,"expectedVersion is required.");if(!expected.equals(actual)) throw fail(HttpStatus.CONFLICT,"Record changed. Reload before updating.");}
    private <T> T find(String id,Class<T> type) {T value=mongo.findById(id,type);if(value==null) throw fail(HttpStatus.NOT_FOUND,"Rescue record not found.");return value;}
    private <T> Page<T> list(Class<T> type,int page,int size) {
        if(page<0||size<1||size>100) throw fail(HttpStatus.BAD_REQUEST,"Use page >= 0 and size 1–100.");
        long total=mongo.count(new Query(),type);Query query=new Query().with(Sort.by(Sort.Direction.DESC,"updatedAt").and(Sort.by("_id"))).skip((long)page*size).limit(size);
        return new Page<>(mongo.find(query,type),page,size,total,(total+size-1)/size);
    }
    private ResponseStatusException fail(HttpStatus status,String message) {return new ResponseStatusException(status,message);}
}
