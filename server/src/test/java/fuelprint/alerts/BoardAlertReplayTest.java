package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import fuelprint.api.RecordSaved;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/** Fresh listener instances exercise durable dedup; this is not a process-restart claim. */
class BoardAlertReplayTest extends AlertsSpringTest {
    @Autowired ApplicationContext context;

    @Test void replayingBoardCauseThroughFreshAdapterDoesNotSendAgain() {
        signUp(GUEST_1,NUMBER_A,"AREA-E");
        String id=boardDecision("MORE_INVESTIGATION",List.of("AREA-E"));
        assertThat(gateway.sent).hasSize(1);
        var cause=store.get("board_decision",id).orElseThrow();
        var fresh=context.getAutowireCapableBeanFactory().createBean(AlertAutomation.class);
        fresh.onRecordSaved(new RecordSaved(cause.type(),id,cause.revision(),cause.runId(),cause.createdBy(),false));settle();
        assertThat(gateway.sent).hasSize(1);
        assertThat(notifications().stream().filter(n->id.equals(n.body().path("cause").path("id").asText()))).hasSize(1);
    }

    @Test void freshAdapterAlsoPreservesExistingSmokeDedup() {
        signUp(GUEST_1,NUMBER_A,"AREA-E");
        String id=openInvestigation(List.of("AREA-E"));
        assertThat(gateway.sent).hasSize(1);
        var cause=store.get("investigation",id).orElseThrow();
        var fresh=context.getAutowireCapableBeanFactory().createBean(AlertAutomation.class);
        fresh.onRecordSaved(new RecordSaved(cause.type(),id,cause.revision(),cause.runId(),cause.createdBy(),false));settle();
        assertThat(gateway.sent).hasSize(1);
    }
}
