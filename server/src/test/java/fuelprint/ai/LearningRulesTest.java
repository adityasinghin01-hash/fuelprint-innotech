package fuelprint.ai;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.core.Ids;
import fuelprint.core.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Literal gate counts and public-case lesson fixtures. No actual training or performance claim. */
class LearningRulesTest {
    final AiBehaviorTest.Memory store=new AiBehaviorTest.Memory();
    final String run=Ids.newId(),caseId=Ids.newId();
    boolean gate(ObjectNode next,ObjectNode parent){return LearningRules.gate(next,parent);}
    JsonNode lessons(){return LearningRules.lessons(store);}
    ObjectNode counts(int correct){return object().put("nights",60).put("first_correct",correct).put("in_top_3",58).put("wrong_when_no_source",0).put("wrong_when_unregistered",0).put("misses",60-correct);}
    @Test void twoNightsWorseIsAcceptedInclusively()throws Exception {assertThat(gate(counts(48),counts(50))).isTrue();}
    @Test void threeNightsWorseIsRejected()throws Exception {assertThat(gate(counts(47),counts(50))).isFalse();}
    @Test void improvedCandidateIsAccepted()throws Exception {assertThat(gate(counts(55),counts(50))).isTrue();}
    @Test void changedDenominatorCannotPassAsSameHeldOutTest()throws Exception {assertThat(gate(counts(55).put("nights",70),counts(50))).isFalse();}
    @Test void missingOrNegativeCountsNeverDefaultToZero()throws Exception {
        var missing=counts(50);missing.remove("first_correct");assertThat(gate(missing,counts(50))).isFalse();
        assertThat(gate(counts(50).put("first_correct",-1),counts(50))).isFalse();
        assertThat(gate(counts(50).put("first_correct",61),counts(50))).isFalse();
    }
    @Test void fractionalCountsAreNotRoundedIntoGateSuccess()throws Exception {assertThat(gate(counts(50).put("first_correct",49.5),counts(50))).isFalse();}
    @Test void missingParentOrIncompleteEvaluationCannotPassGate()throws Exception {
        var incomplete=counts(50);incomplete.remove("misses");
        assertThat(gate(incomplete,counts(50))).isFalse();
        assertThat(gate(counts(50),object())).isFalse();
    }
    StoredRecord lesson(String outcome,String qc,String site,String state){
        var specimen=store.put("specimen",Ids.newId(),run,object().put("case_id",caseId).put("site_id",site));
        var labBody=object().put("specimen_id",specimen.id()).put("qc_status",qc);labBody.set("specimen_ref",ref(specimen));labBody.set("case_ref",object().put("type","case").put("id",caseId).put("revision",1));
        var lab=store.put("lab_result",Ids.newId(),run,labBody);
        var findingBody=object().put("case_id",caseId).put("outcome",outcome);findingBody.putArray("cited_refs").add(ref(lab));
        var finding=store.put("finding",Ids.newId(),run,findingBody);
        var body=object().put("state",state);body.set("finding_ref",ref(finding));
        return store.put("case",caseId,run,body);
    }
    @Test void supportedSealedCaseAndCitedPassLabAreAnEligiblePublicLesson()throws Exception {
        lesson("SUPPORTED_WITHIN_SCOPE","PASS","S02","SEALED");var rows=lessons();
        assertThat(rows).hasSize(1);assertThat(rows.get(0).path("status").asText()).isEqualTo("ELIGIBLE");
        assertThat(rows.get(0).path("site_id").asText()).isEqualTo("S02");
        assertThat(rows.get(0).path("case_id").asText()).isEqualTo(caseId);
    }
    @ParameterizedTest @ValueSource(strings={"INCONCLUSIVE","REJECTED_FOR_USE"})
    void otherFindingsAreSkippedWithReason(String outcome)throws Exception {
        lesson(outcome,"PASS","S02","SEALED");var row=lessons().get(0);
        assertThat(row.path("status").asText()).isEqualTo("SKIPPED");assertThat(row.path("reason").asText()).isNotBlank();
        assertThat(row.path("site_id").isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","FAIL"})
    void failedOrMissingQcCannotTeach(String qc)throws Exception {
        lesson("SUPPORTED_WITHIN_SCOPE",qc,"S02","SEALED");assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void unsealedCaseIsNotALesson()throws Exception {lesson("SUPPORTED_WITHIN_SCOPE","PASS","S02","FINDING");assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");}
    @Test void laterFindingRevisionCannotChangePinnedLesson()throws Exception {
        var c=lesson("INCONCLUSIVE","PASS","S02","SEALED");var f=c.body().path("finding_ref");
        var body=store.get("finding",f.path("id").asText()).orElseThrow().body().put("outcome","SUPPORTED_WITHIN_SCOPE");
        store.put("finding",f.path("id").asText(),run,body);
        assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void foreignCaseSpecimenCannotSupplyALabel()throws Exception {
        lesson("SUPPORTED_WITHIN_SCOPE","PASS","S02","SEALED");var specimen=store.list("specimen",run).get(0);
        // The lab's exact cited specimen is foreign at that revision, not merely a later changed row.
        store.rows.remove(specimen);store.put("specimen",specimen.id(),run,specimen.body().put("case_id",Ids.newId()));
        assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void unregisteredSiteDoesNotBecomeATrainingLabel()throws Exception {lesson("SUPPORTED_WITHIN_SCOPE","PASS","REAL_FACTORY","SEALED");assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");}
    @Test void arbitraryUncitedPassLabDoesNotQualifyACase()throws Exception {
        var c=lesson("SUPPORTED_WITHIN_SCOPE","PASS","S02","SEALED");var f=c.body().path("finding_ref");var r=store.get("finding",f.path("id").asText()).orElseThrow();store.rows.remove(r);
        var body=r.body();body.putArray("cited_refs");store.put("finding",r.id(),run,body);
        assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void labCaseReferenceMustResolveItsExactRevision() {
        lesson("SUPPORTED_WITHIN_SCOPE","PASS","S02","SEALED");var lab=store.list("lab_result",run).get(0);
        var body=lab.body();((ObjectNode)body.path("case_ref")).put("revision",999);
        store.rows.remove(lab);store.put("lab_result",lab.id(),run,body);
        assertThat(lessons().get(0).path("status").asText()).isEqualTo("SKIPPED");
    }
    @Test void impossibleFirstCorrectAboveTopThreeCannotPassGate() {
        assertThat(gate(counts(59),counts(50))).isFalse();
    }
    static ObjectNode object(){return JsonNodeFactory.instance.objectNode();}
    static ObjectNode ref(StoredRecord r){return object().put("type",r.type()).put("id",r.id()).put("revision",r.revision());}
}
