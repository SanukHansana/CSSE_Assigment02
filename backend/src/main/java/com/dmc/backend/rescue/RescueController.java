package com.dmc.backend.rescue;
import com.dmc.backend.auth.AuthService;
import com.dmc.backend.rescue.RescueModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final MongoTemplate mongo; private final AuthService auth; private final Clock clock; private final TransactionTemplate transactions;
    public RescueController(MongoTemplate mongo,AuthService auth,Clock clock,MongoDatabaseFactory factory) {this.mongo=mongo;this.auth=auth;this.clock=clock;this.transactions=new TransactionTemplate(new MongoTransactionManager(factory));}
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
                List.of(new Event(UUID.randomUUID().toString(),"Incident created",officer,at)),List.of()));
    }
    @PutMapping("/incidents/{id}")
    public Incident updateIncident(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody IncidentRequest body) {
        Incident current=find(id,Incident.class);version(body.expectedVersion(),current.version());
        if(current.status()!=IncidentStatus.OPEN) throw fail(HttpStatus.CONFLICT,"Only open incidents can be edited.");
        String officer=officer(jwt);var at=clock.instant();List<Event> history=new ArrayList<>(current.history());history.add(new Event(UUID.randomUUID().toString(),"Incident details updated",officer,at));
        return mongo.save(new Incident(current.id(),current.version(),current.reference(),body.type(),body.priority(),body.description().strip(),
                body.location().strip(),body.latitude(),body.longitude(),body.peopleNeedingAssistance(),current.status(),officer,at,history,current.assignments()));
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
    public record AssignRequest(@NotBlank @Size(max=100) String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotBlank String teamId,
            @NotNull @PositiveOrZero Long teamVersion, @NotBlank @Size(max=500) String note) { }
    public record ProgressRequest(@NotBlank @Size(max=100) String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull AssignmentStatus status,
            @NotBlank @Size(max=500) String note,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @Size(max=200) String location) { }
    public record CompleteRequest(@NotBlank @Size(max=100) String requestId,
            @NotNull @PositiveOrZero Long expectedVersion, @NotNull IncidentStatus status,
            @NotBlank @Size(max=500) String note) { }
    @PostMapping("/incidents/{id}/assignments")
    public Incident assign(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody AssignRequest body) {
        return transactions.execute(transaction -> {
            Incident incident=find(id,Incident.class);
            String detail="ASSIGN "+body.teamId()+". "+body.note().strip();
            if(replay(incident,body.requestId(),detail)) return incident;
            version(body.expectedVersion(),incident.version());open(incident);
            Team team=find(body.teamId(),Team.class);version(body.teamVersion(),team.version());
            if(team.status()!=TeamStatus.AVAILABLE || team.activeIncidentId()!=null) throw fail(HttpStatus.CONFLICT,"Team is already assigned or unavailable.");
            var at=clock.instant();String officer=officer(jwt);
            List<Assignment> assignments=new ArrayList<>(incident.assignments());
            assignments.add(new Assignment(body.requestId(),team.id(),team.name(),AssignmentStatus.ASSIGNED,body.note().strip(),officer,at,at));
            mongo.save(new Team(team.id(),team.version(),team.name(),team.specialization(),team.location(),team.latitude(),team.longitude(),TeamStatus.DEPLOYED,incident.id(),officer,at));
            return saveOperation(incident,incident.status(),assignments,body.requestId(),detail,officer);
        });
    }
    @PostMapping("/incidents/{id}/assignments/{assignmentId}/progress")
    public Incident progress(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@PathVariable String assignmentId,
            @Valid @RequestBody ProgressRequest body) {
        return transactions.execute(transaction -> {
            Incident incident=find(id,Incident.class);
            String detail="PROGRESS "+assignmentId+" "+body.status()+". "+body.note().strip()+"; location="+body.location()+"; GPS="+body.latitude()+","+body.longitude();
            if(replay(incident,body.requestId(),detail)) return incident;
            version(body.expectedVersion(),incident.version());open(incident);
            List<Assignment> assignments=new ArrayList<>(incident.assignments());
            int index=-1;for(int i=0;i<assignments.size();i++) if(assignments.get(i).id().equals(assignmentId)) index=i;
            if(index<0) throw fail(HttpStatus.NOT_FOUND,"Assignment not found.");
            Assignment current=assignments.get(index);transition(current.status(),body.status());
            if((body.latitude()==null)!=(body.longitude()==null)) throw fail(HttpStatus.BAD_REQUEST,"Supply both coordinates or neither.");
            if(body.latitude()!=null && (!Double.isFinite(body.latitude()) || !Double.isFinite(body.longitude()))) throw fail(HttpStatus.BAD_REQUEST,"Coordinates must be finite.");
            Team team=find(current.teamId(),Team.class);
            if(!incident.id().equals(team.activeIncidentId())) throw fail(HttpStatus.CONFLICT,"Team is no longer assigned to this incident.");
            boolean terminal=terminal(body.status());var at=clock.instant();String officer=officer(jwt);
            assignments.set(index,new Assignment(current.id(),current.teamId(),current.teamName(),body.status(),body.note().strip(),officer,current.assignedAt(),at));
            mongo.save(new Team(team.id(),team.version(),team.name(),team.specialization(),
                    body.location()==null || body.location().isBlank() ? team.location() : body.location().strip(),
                    body.latitude()==null ? team.latitude() : body.latitude(),body.longitude()==null ? team.longitude() : body.longitude(),
                    terminal ? TeamStatus.AVAILABLE : body.status()==AssignmentStatus.ON_SITE ? TeamStatus.BUSY : TeamStatus.DEPLOYED,
                    terminal ? null : incident.id(),officer,at));
            return saveOperation(incident,incident.status(),assignments,body.requestId(),detail,officer);
        });
    }
    @PostMapping("/incidents/{id}/status")
    public Incident complete(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,@Valid @RequestBody CompleteRequest body) {
        Incident incident=find(id,Incident.class);String detail="INCIDENT "+body.status()+". "+body.note().strip();
        if(replay(incident,body.requestId(),detail)) return incident;
        version(body.expectedVersion(),incident.version());
        if(body.status()==IncidentStatus.RESOLVED) {
            open(incident);
            if(incident.assignments().isEmpty() || incident.assignments().stream().anyMatch(a -> !terminal(a.status()))
                    || incident.assignments().stream().noneMatch(a -> a.status()==AssignmentStatus.COMPLETED))
                throw fail(HttpStatus.CONFLICT,"Complete rescue activities and end all active assignments before resolving.");
        } else if(body.status()!=IncidentStatus.CLOSED || incident.status()!=IncidentStatus.RESOLVED)
            throw fail(HttpStatus.CONFLICT,"Resolve the incident before closing it.");
        return saveOperation(incident,body.status(),incident.assignments(),body.requestId(),detail,officer(jwt));
    }
    private void open(Incident incident) {if(incident.status()!=IncidentStatus.OPEN) throw fail(HttpStatus.CONFLICT,"Incident is not open.");}
    private boolean terminal(AssignmentStatus status) {return status==AssignmentStatus.COMPLETED||status==AssignmentStatus.CANCELLED;}
    private void transition(AssignmentStatus from,AssignmentStatus to) {
        boolean valid=!terminal(from) && (to==AssignmentStatus.CANCELLED
                || from==AssignmentStatus.ASSIGNED && to==AssignmentStatus.EN_ROUTE
                || from==AssignmentStatus.EN_ROUTE && to==AssignmentStatus.ON_SITE
                || from==AssignmentStatus.ON_SITE && to==AssignmentStatus.COMPLETED);
        if(!valid) throw fail(HttpStatus.CONFLICT,"Invalid assignment progress transition.");
    }
    private boolean replay(Incident incident,String requestId,String detail) {
        Event previous=incident.history().stream().filter(e -> e.id().equals(requestId)).findFirst().orElse(null);
        if(previous==null) return false;
        if(!previous.detail().equals(detail)) throw fail(HttpStatus.CONFLICT,"Request ID was already used for different input.");
        return true;
    }
    private Incident saveOperation(Incident incident,IncidentStatus status,List<Assignment> assignments,String requestId,String detail,String officer) {
        var at=clock.instant();List<Event> history=new ArrayList<>(incident.history());history.add(new Event(requestId,detail,officer,at));
        return mongo.save(new Incident(incident.id(),incident.version(),incident.reference(),incident.type(),incident.priority(),incident.description(),
                incident.location(),incident.latitude(),incident.longitude(),incident.peopleNeedingAssistance(),status,officer,at,history,assignments));
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
