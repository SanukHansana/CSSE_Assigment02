package com.dmc.backend.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dmc/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    public record Decision(@NotNull Boolean approved,@NotBlank @Size(max=500) String note) { }
    public record Applications(List<AuthContracts.UserResponse> items,int page,int size,long totalElements,long totalPages) { }
    private final MongoTemplate mongo; private final AuthService auth; private final Clock clock;
    public AdminController(MongoTemplate mongo,AuthService auth,Clock clock) {this.mongo=mongo;this.auth=auth;this.clock=clock;}
    @GetMapping("/applications")
    public ResponseEntity<Applications> list(@RequestParam(defaultValue="PENDING") String status,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        if(!List.of("PENDING","APPROVED","REJECTED").contains(status)||page<0||size<1||size>100)
            throw new AuthException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","Use a valid approval status, page >= 0 and size 1–100.");
        Criteria criteria=Criteria.where("approvalStatus").is(status).and("requestedRole").in(AccountRole.DUTY_OFFICER,AccountRole.DMC_OFFICER);
        long total=mongo.count(Query.query(criteria),UserAccount.class);
        var query=Query.query(criteria).with(Sort.by("createdAt").and(Sort.by("_id"))).skip((long)page*size).limit(size);
        var items=mongo.find(query,UserAccount.class).stream().map(AuthContracts.UserResponse::from).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Applications(items,page,size,total,(total+size-1)/size));
    }
    @PostMapping("/applications/{id}/decision")
    public ResponseEntity<AuthContracts.UserResponse> decide(@AuthenticationPrincipal Jwt jwt,@PathVariable String id,
            @Valid @RequestBody Decision body) {
        UserAccount current=mongo.findById(id,UserAccount.class);
        if(current==null) throw new AuthException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","Account not found.");
        if(!"PENDING".equals(current.approvalStatus()) || !List.of(AccountRole.DUTY_OFFICER,AccountRole.DMC_OFFICER).contains(current.requestedRole()))
            throw new AuthException(HttpStatus.CONFLICT,"APPLICATION_NOT_PENDING","Only pending officer applications can be reviewed.");
        String reviewer=auth.requireActiveAccount(jwt.getSubject()).displayName();
        Query query=Query.query(Criteria.where("_id").is(id).and("approvalStatus").is("PENDING").and("requestedRole").is(current.requestedRole()));
        Update update=new Update().set("approvalStatus",body.approved()?"APPROVED":"REJECTED").set("roles",List.of(current.requestedRole()))
                .set("reviewedBy",reviewer).set("reviewedAt",clock.instant()).set("approvalNote",body.note().strip());
        UserAccount saved=mongo.findAndModify(query,update,FindAndModifyOptions.options().returnNew(true),UserAccount.class);
        if(saved==null) throw new AuthException(HttpStatus.CONFLICT,"APPLICATION_CHANGED","Another admin already reviewed this application.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AuthContracts.UserResponse.from(saved));
    }
}
