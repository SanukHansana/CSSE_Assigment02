package com.dmc.backend.reporting;

import static org.assertj.core.api.Assertions.*;
import com.dmc.backend.auth.*;
import com.dmc.backend.models.hazard.*;
import com.dmc.backend.models.hazard.HazardReport.*;
import jakarta.validation.Validation;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.bson.Document;
import org.bson.json.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Generates fictional local fixtures only; never connects to or seeds a database. */
class DevelopmentFixturesTest {
    @Test void generatesValidatedDemoAccountsReportsAndPrivateEvidence() throws Exception {
        Path root=Path.of("target/development-fixtures");Files.createDirectories(root);
        var storage=new LocalEvidenceStorage(root.resolve("evidence").toString());
        Instant time=Instant.parse("2026-09-04T05:00:00Z"); Clock clock=Clock.fixed(time,ZoneOffset.UTC);
        var encoder=new BCryptPasswordEncoder(12);
        String password="DemoReports2026!";
        List<UserAccount> accounts=List.of(
                account("citizen",AccountRole.CITIZEN,encoder.encode(password),time),
                account("volunteer",AccountRole.COMMUNITY_VOLUNTEER,encoder.encode(password),time),
                account("officer",AccountRole.DUTY_OFFICER,encoder.encode(password),time),
                account("dmc",AccountRole.DMC_OFFICER,encoder.encode(password),time));
        byte[] image=demoImage(); var officer=new UserReference("demo-officer","Demo Duty Officer");
        List<HazardReport> reports=new ArrayList<>();
        for (ReportStatus state:ReportStatus.values()) {
            boolean volunteer=state==ReportStatus.SUBMITTED;
            var reporter=new ReporterIdentity(new UserReference(volunteer?"demo-volunteer":"demo-citizen",
                    volunteer?"Demo Community Volunteer":"Demo Citizen"), volunteer?ReporterType.COMMUNITY_VOLUNTEER:ReporterType.CITIZEN);
            HazardReport report=HazardReport.draft(reporter,ReportSource.WEB_PORTAL,time.minusSeconds(120),clock);
            report.updateDraft(HazardType.FLOODING,"FICTIONAL DEMO: water covers a sample access road.",
                    state==ReportStatus.DRAFT?null:new ReportedLocation(6.9271,79.8612,"Demo Colombo District",time.minusSeconds(90)),clock);
            if (state!=ReportStatus.DRAFT) {
                report.replacePhoto(storage.store(new MockMultipartFile("file","demo-evidence.png","image/png",image),
                        reporter.user(),time.minusSeconds(100),time),clock);report.submit(clock);
            }
            if (state==ReportStatus.UNDER_REVIEW || state==ReportStatus.VERIFIED || state==ReportStatus.REJECTED)
                report.startReview(officer,clock);
            if (state==ReportStatus.VERIFIED || state==ReportStatus.REJECTED) {
                report.updateReview(officer,new CredibilityChecklist(true,true,true,true),"Fictional demonstration review.",clock);
                report.decide(officer,state==ReportStatus.VERIFIED?VerificationDecision.VERIFIED:VerificationDecision.REJECTED,
                        state==ReportStatus.REJECTED?"Demo example: evidence refers to another location.":null,clock);
            }
            reports.add(report);
        }
        var conversions=MongoCustomConversions.create(adapter -> {});var context=new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());context.setInitialEntitySet(Set.of(HazardReport.class,UserAccount.class));context.afterPropertiesSet();
        var converter=new MappingMongoConverter(NoOpDbRefResolver.INSTANCE,context);converter.setCustomConversions(conversions);converter.afterPropertiesSet();
        List<Document> accountDocuments=new ArrayList<>();for(var account:accounts){Document doc=new Document();converter.write(account,doc);accountDocuments.add(doc);}
        List<Document> reportDocuments=new ArrayList<>();
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            for(var report:reports) {
                assertThat(factory.getValidator().validate(report)).isEmpty();Document doc=new Document();converter.write(report,doc);
                doc.put("_id","demo-report-"+report.getStatus().name().toLowerCase(Locale.ROOT));doc.put("version",0L);
                doc.put("reference","HR-2026-DEMO"+report.getStatus().name().replace("_",""));
                if(report.getVerification()!=null) ((Document)doc.get("verification")).put("reference","RV-2026-DEMO"+report.getStatus().name());
                assertThat(converter.read(HazardReport.class,doc).isStateConsistent()).isTrue();reportDocuments.add(doc);
            }
        }
        Document export=new Document("accounts",accountDocuments).append("reports",reportDocuments);
        Files.writeString(root.resolve("fixtures.json"),export.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).indent(true).build()));
        assertThat(reportDocuments).hasSize(5);assertThat(accountDocuments).hasSize(4);
        assertThat(encoder.matches(password,accounts.getFirst().passwordHash())).isTrue();
    }
    private UserAccount account(String suffix,AccountRole role,String hash,Instant now) {
        return new UserAccount("demo-"+suffix,suffix+"@dmc.example.test","Demo "+switch(role){
            case CITIZEN->"Citizen";case COMMUNITY_VOLUNTEER->"Community Volunteer";case DUTY_OFFICER->"Duty Officer";case DMC_OFFICER->"DMC Officer";},hash,Set.of(role),true,now);
    }
    private byte[] demoImage() throws Exception {
        BufferedImage image=new BufferedImage(400,200,BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();
        try{graphics.setColor(Color.WHITE);graphics.fillRect(0,0,400,200);graphics.setColor(new Color(0,70,120));
            graphics.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));graphics.drawString("FICTIONAL DEMO EVIDENCE",35,90);graphics.drawString("Not a real hazard report",55,125);}
        finally{graphics.dispose();}var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
}
