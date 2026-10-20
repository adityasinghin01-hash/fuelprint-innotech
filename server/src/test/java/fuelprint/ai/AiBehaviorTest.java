package fuelprint.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.*;
import com.fasterxml.jackson.databind.node.*;
import fuelprint.api.*;
import fuelprint.core.Ids;
import fuelprint.core.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public-record adapter fixtures, not trained inference or natural-simulation evidence. */
class AiBehaviorTest {
    final Memory records = new Memory();
    final String run = Ids.newId();
    final Ref target = new Ref("analysis", Ids.newId(), 1);

    BackendAiServices service() { return new BackendAiServices(records); }
    Analysis physics(Outcome outcome) {
        return Analysis.physics(List.of(), outcome, Qualification.SYNTHETIC_SCOPE_ONLY,
                List.of(new Candidate("S01",List.of("S01"),1.0,CandidateStatus.SURVIVES,null),
                        new Candidate("S02",List.of("S02"),9.0,CandidateStatus.REJECTED,"bad fit"),
                        new Candidate("UNKNOWN",List.of(),null,CandidateStatus.SURVIVES,null)),
                List.of(),List.of("Synthetic scope only"),"Public explanation", "FIXTURE");
    }
    AnalysisInput input() { return new AnalysisInput(run,Ids.newId(),100,List.of(),new WindNow(270,3)); }
    @Test void noModelIsMissingWithoutChangingPhysics() throws Exception {
        Analysis before=physics(Outcome.AMBIGUOUS_OR_OPTIONAL_MIXTURE);
        LearnedOrder order=((LearnedRanker)service()).order(input(),before);
        assertThat(order.learnedStatus()).isEqualTo(LearnedStatus.MODEL_MISSING);
        assertThat(order.learnedOrder()).isEmpty();
        assertThat(order.learnedModelVersionId()).isNull();
        Analysis after=before.withLearned(order);
        assertThat(after.candidates()).isEqualTo(before.candidates());
        assertThat(after.outcome()).isEqualTo(before.outcome());
        assertThat(after.limitations()).isEqualTo(before.limitations());
    }
    @ParameterizedTest @ValueSource(strings={"INVALID","NO_SIGNAL"})
    void nonapplicableOutcomesBypassEvenMalformedModel(String outcome) throws Exception {
        records.put("model_version",Ids.newId(),null,object().put("state","ACTIVE").put("model_kind","LEARNED_RANKER"));
        var order=((LearnedRanker)service()).order(input(),physics(Outcome.valueOf(outcome)));
        assertThat(order.learnedStatus()).isEqualTo(LearnedStatus.NOT_APPLICABLE);
        assertThat(order.learnedOrder()).isEmpty();
    }
    @Test void activeMetadataWithMissingArtifactFailsRatherThanInventingOrder() throws Exception {
        String model=Ids.newId();
        var body=object().put("state","ACTIVE").put("model_kind","LEARNED_RANKER");
        body.putObject("file").put("path","models/not-present.onnx").put("sha256","0".repeat(64));
        records.put("model_version",model,null,body);
        var order=((LearnedRanker)service()).order(input(),physics(Outcome.PROVISIONAL_SINGLE));
        assertThat(order.learnedStatus()).isEqualTo(LearnedStatus.FAILED);
        assertThat(order.learnedOrder()).isEmpty();
        assertThat(order.learnedModelVersionId()).isEqualTo(model);
    }
    @Test void ambiguousActiveVersionsFailClosed() throws Exception {
        for(int i=0;i<2;i++)records.put("model_version",Ids.newId(),null,object().put("state","ACTIVE").put("model_kind","LEARNED_RANKER"));
        var order=((LearnedRanker)service()).order(input(),physics(Outcome.PROVISIONAL_SINGLE));
        assertThat(order.learnedStatus()).isEqualTo(LearnedStatus.FAILED);
        assertThat(order.learnedOrder()).isEmpty();
    }
    @Test void retiredAndRejectedVersionsDoNotSupplyAModel() throws Exception {
        for(String state:List.of("RETIRED","REJECTED_BY_GATE"))records.put("model_version",Ids.newId(),null,object().put("state",state).put("model_kind","LEARNED_RANKER"));
        assertThat(((LearnedRanker)service()).order(input(),physics(Outcome.PROVISIONAL_SINGLE)).learnedStatus()).isEqualTo(LearnedStatus.MODEL_MISSING);
    }
    @Test void historyCountsOnlyDistinctEarlierRunsAndSurvivingSources() throws Exception {
        String earlier=Ids.newId(),later=Ids.newId();
        records.put("run",earlier,earlier,object().put("state","COMPLETED"));
        records.put("run",run,run,object().put("state","RUNNING"));
        records.put("run",later,later,object().put("state","COMPLETED"));
        for(String pastRun:List.of(earlier,earlier,run,later)) {
            ObjectNode a=object(),pair=object().put("explanation_id","S01+S02").put("status","SURVIVES"),rejected=object().put("explanation_id","S03").put("status","REJECTED");
            pair.putArray("sources").add("S01").add("S02");rejected.putArray("sources").add("S03");
            a.putArray("candidates").add(pair).add(rejected);
            records.put("analysis",Ids.newId(),pastRun,a);
        }
        var counts=((LearnedRanker)service()).order(input(),physics(Outcome.PROVISIONAL_SINGLE)).historyCounts();
        assertThat(counts).containsEntry("S01",1).containsEntry("S02",1).containsEntry("S03",0);
    }
    @Test void missingNormalModelHasNoFabricatedBand() throws Exception {
        assertThat(((NormalAirModel)service()).expected("N2","SO2_ppb",300,new WindNow(270,3))).isEmpty();
    }
    @Test void evenActiveNormalMetadataCannotFabricateAnInference() throws Exception {
        records.put("model_version",Ids.newId(),null,object().put("state","ACTIVE").put("model_kind","NORMAL_AIR"));
        assertThat(((NormalAirModel)service()).expected("N2","SO2_ppb",300,new WindNow(270,3))).isEmpty();
    }
    @Test void safeTemplatePreservesNumbersStatusesAndLimits() throws Exception {
        String input="SO2 measured 12.5 ppb. Outcome INCONCLUSIVE. Synthetic scope only.";
        Wording result=((WordingService)service()).write(new WordingRequest(target,WordingPurpose.EXPLANATION,input));
        assertThat(result.text()).isEqualTo(input);
        assertThat(result.source()).isEqualTo(WordingSource.TEMPLATE);
        assertThat(result.model()).isNull();
        assertThat(result.target()).isEqualTo(target);
        assertThat(result.fallbackReason()).isEqualTo("EXTERNAL_PROVIDER_DISABLED");
        assertThat(result.checks()).allSatisfy(check->assertThat(check.passed()).isTrue());
    }
    @ParameterizedTest @ValueSource(strings={"guilty","illegal","proved","coal","probability","chance","%"})
    void unsafeInputNeverBecomesTemplateText(String banned) throws Exception {
        Wording result=((WordingService)service()).write(new WordingRequest(target,WordingPurpose.FINDING_SUMMARY,"This is "+banned));
        assertThat(result.text().toLowerCase(Locale.ROOT)).doesNotContain(banned);
        assertThat(result.fallbackReason()).isEqualTo("UNSAFE_PUBLIC_TEXT");
    }
    @ParameterizedTest @ValueSource(strings={"Contact +919812345678", "phone_number: 9812345678", "hidden_truth: S02", "actual_emitter=S02",
            "Call 98123.45678", "Call +1 (202) 555-0123", "Call ＋９１９６１２３４５６７８"})
    void privateInputIsNotEchoed(String privateText) throws Exception {
        Wording result=((WordingService)service()).write(new WordingRequest(target,WordingPurpose.EXPLANATION,privateText));
        assertThat(result.text()).doesNotContain(privateText,"9812345678","hidden_truth","actual_emitter");
        assertThat(result.fallbackReason()).isEqualTo("UNSAFE_PUBLIC_TEXT");
    }
    @Test void safeBoardSmsRetainsDecisionSpecificMeaning() throws Exception {
        String text="FuelPrint DEMO (simulated): no source was confirmed. Monitoring continues. Demo only.";
        Wording result=((WordingService)service()).write(new WordingRequest(target,WordingPurpose.SMS_BOARD_UPDATE,text));
        assertThat(result.text()).isEqualTo(text);
        assertThat(result.text()).hasSizeLessThanOrEqualTo(160);
    }
    @ParameterizedTest @ValueSource(strings={"FuelPrint DEMO (simulated): S02 is under review.","FuelPrint DEMO (simulated): ","No prefix","FuelPrint DEMO (simulated): SourceS02"})
    void smsSafetyRejectsLabelsOrMissingPrefix(String text) throws Exception {
        if(text.endsWith(": ")) text += "x".repeat(170);
        var result=((WordingService)service()).write(new WordingRequest(target,WordingPurpose.SMS_BOARD_UPDATE,text));
        assertThat(result.text()).startsWith("FuelPrint DEMO (simulated):").doesNotContain("S02");
        assertThat(result.text()).hasSizeLessThanOrEqualTo(160);
        assertThat(result.fallbackReason()).isEqualTo("UNSAFE_PUBLIC_TEXT");
    }
    @Test void nullRequestFallsBackWithoutThrowing() throws Exception {
        Wording result=((WordingService)service()).write(null);
        assertThat(result.source()).isEqualTo(WordingSource.TEMPLATE);
        assertThat(result.text()).isNotBlank();
        assertThat(result.fallbackReason()).isEqualTo("INVALID_REQUEST");
    }
    static ObjectNode object(){return JsonNodeFactory.instance.objectNode();}
    static final class Memory implements StoreView {
        final List<StoredRecord> rows=new ArrayList<>();
        StoredRecord put(String type,String id,String run,ObjectNode body){
            var r=new StoredRecord(type,id,latestRevision(type,id)+1,run,"2026-10-19T00:00:00Z","fixture",body.deepCopy());rows.add(r);return r;
        }
        public Optional<StoredRecord> get(String type,String id){return rows.stream().filter(r->r.type().equals(type)&&r.id().equals(id)).max(Comparator.comparingLong(StoredRecord::revision));}
        public Optional<StoredRecord> get(String type,String id,long revision){return rows.stream().filter(r->r.type().equals(type)&&r.id().equals(id)&&r.revision()==revision).findFirst();}
        public List<StoredRecord> listAll(String type){return rows.stream().filter(r->r.type().equals(type)&&r.revision()==latestRevision(type,r.id())).toList();}
        public List<StoredRecord> list(String type,String run){return listAll(type).stream().filter(r->Objects.equals(run,r.runId())).toList();}
        public long latestRevision(String type,String id){return get(type,id).map(StoredRecord::revision).orElse(0L);}
    }
}
