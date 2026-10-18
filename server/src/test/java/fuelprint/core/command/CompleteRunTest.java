package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.RunEnded;
import fuelprint.api.WorldCapacityReached;
import fuelprint.core.CoreSpringTest;
import fuelprint.core.run.RunCommands;
import fuelprint.core.run.RunEventsRecorder;
import fuelprint.core.run.Runs;
import fuelprint.core.session.CookieSessionService;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import fuelprint.core.world.WorldLoop;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** The public science event must end the run through a normal, automation-only command. */
@ActiveProfiles("real")
@SpringBootTest(properties = "fp.field.safety-loop=false")
class CompleteRunTest extends CoreSpringTest {

    private static final String COMPLETE = "complete_run";

    @Autowired CommandBus bus;
    @Autowired AutomationBus automation;
    @Autowired CommandLog commands;
    @Autowired RecordStore store;
    @Autowired WorldLoop world;
    @Autowired ApplicationEventPublisher events;
    @Autowired CookieSessionService sessions;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    StoredRecord run;

    @BeforeEach
    void startRunWithARealPresenterCookie() throws Exception {
        JsonNode receipt = human(RunCommands.RESET, JsonNodeFactory.instance.objectNode()
                .put("preset_id", "SC1-NIGHT-PLUME").put("mode", "BLIND"), null, Role.PRESENTER);
        assertThat(receipt.path("status").asText()).isEqualTo("APPLIED");
        for (JsonNode ref : receipt.path("record_refs")) {
            if ("run".equals(ref.path("type").asText()) && ref.path("revision").asLong() == 1) {
                run = store.get("run", ref.path("id").asText()).orElseThrow();
            }
        }
        assertThat(run).isNotNull();
    }

    JsonNode human(String type, ObjectNode payload, Long revision, Role role) throws Exception {
        Session session = switch (role) {
            case VISITOR -> sessions.createVisitor("visitor-capacity");
            case GUEST -> sessions.createGuest();
            default -> sessions.createStation("capacity-test", Set.of(role.wire()));
        };
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), type, null,
                revision, payload);
        String response = mvc.perform(post("/api/commands").contentType(MediaType.APPLICATION_JSON)
                        .cookie(new Cookie(CookieSessionService.COOKIE, session.sessionId()))
                        .content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        drain();
        return mapper.readTree(response);
    }

    void drain() {
        bus.awaitIdle();
        bus.awaitIdle(); // field may queue its own cleanup after RunEnded
    }

    ObjectNode payload() {
        return JsonNodeFactory.instance.objectNode().put("run_id", run.id()).put("reason", "WORLD_CAPACITY");
    }

    Receipt complete(ObjectNode payload, Long revision) throws Exception {
        Receipt receipt = automation.submit(COMPLETE, payload, run.id(), revision).get(10, TimeUnit.SECONDS);
        drain();
        return receipt;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void capacityEventCompletesRunningOrPausedRunWithAnAutomaticReceipt(boolean paused) throws Exception {
        for (int i = 0; i < 25; i++) {
            world.wallStep();
        }
        drain();
        if (paused) {
            assertThat(human(RunCommands.PAUSE, payload(), run.revision(), Role.PRESENTER)
                    .path("status").asText()).isEqualTo("APPLIED");
            run = store.get("run", run.id()).orElseThrow();
        }
        double time = world.simTimeS();
        assertThat(time).isGreaterThan(0);
        events.publishEvent(new WorldCapacityReached(run.id(), "PUFF_CAP", time, 160000));
        drain();

        StoredRecord ended = store.get("run", run.id()).orElseThrow();
        assertThat(Runs.state(ended)).isEqualTo(Runs.COMPLETED);
        assertThat(ended.revision()).isEqualTo(run.revision() + 1);
        assertThat(ended.createdBy()).isEqualTo("automation");
        assertThat(ended.body().path("state_reason").asText()).isEqualTo("WORLD_CAPACITY");
        assertThat(ended.body().path("sim_time_s").asDouble()).isEqualTo(time);
        assertThat(world.isLoaded(run.id())).isFalse();
        assertThat(world.isRunning()).isFalse();
        assertThat(RunEventsRecorder.EVENTS).contains(new RunEnded(run.id(), RunEnded.Reason.COMPLETED));
        var entries = commands.automationEntries(run.id(), 100).stream()
                .filter(e -> COMPLETE.equals(e.commandType()) && run.id().equals(e.runId())).toList();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).receipt().status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(entries.get(0).receipt().actor()).isEqualTo("automation");
        assertThat(entries.get(0).receipt().automatic()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = "AUTOMATION", mode = EnumSource.Mode.EXCLUDE)
    void everyHumanRoleIsRefusedOverHttp(Role role) throws Exception {
        JsonNode receipt = human(COMPLETE, payload(), run.revision(), role);
        assertThat(receipt.path("reason_code").asText()).isEqualTo(ReasonCode.NOT_ALLOWED);
        assertThat(receipt.path("automatic").asBoolean()).isFalse();
        assertThat(store.get("run", run.id()).orElseThrow().revision()).isEqualTo(run.revision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED", "COMPLETED", "ABORTED"})
    void otherRunStatesAreRefused(String state) throws Exception {
        bus.saveWithoutCommand("automation", HandlerResult.builder()
                .save(Runs.changeState(run, state, 0, null), "TEST_RUN_STATE", 0.0).build(), true)
                .get(10, TimeUnit.SECONDS);
        drain();
        long revision = store.get("run", run.id()).orElseThrow().revision();
        Receipt receipt = complete(payload(), revision);
        assertThat(receipt.status()).isEqualTo(Receipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo(RunCommands.WRONG_STATE);
        assertThat(store.get("run", run.id()).orElseThrow().revision()).isEqualTo(revision);
        assertThat(RunEventsRecorder.EVENTS).doesNotContain(new RunEnded(run.id(), RunEnded.Reason.COMPLETED));
    }

    @Test
    void repeatedCommandIdReturnsTheFirstReceiptAndPublishesOneEnd() {
        Command command = new Command(Command.SCHEMA_VERSION, UUID.randomUUID().toString(), COMPLETE, run.id(),
                run.revision(), payload());
        Receipt first = bus.send(command, Session.automation());
        drain();
        Receipt again = bus.send(command, Session.automation());
        drain();
        assertThat(first.status()).isEqualTo(Receipt.Status.APPLIED);
        assertThat(again).isEqualTo(first);
        assertThat(store.get("run", run.id()).orElseThrow().revision()).isEqualTo(run.revision() + 1);
        assertThat(RunEventsRecorder.EVENTS.stream()
                .filter(new RunEnded(run.id(), RunEnded.Reason.COMPLETED)::equals).count()).isEqualTo(1);
    }

    @Test
    void staleRevisionIsRefused() throws Exception {
        Receipt receipt = complete(payload(), run.revision() - 1);
        assertThat(receipt.reasonCode()).isEqualTo(ReasonCode.STALE_REVISION);
        assertThat(store.get("run", run.id()).orElseThrow().revision()).isEqualTo(run.revision());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "OPERATOR", "RESET"})
    void onlyWorldCapacityIsAnAllowedReason(String reason) throws Exception {
        assertThat(complete(payload().put("reason", reason), null).reasonCode()).isEqualTo(ReasonCode.INVALID);
        assertThat(store.get("run", run.id()).orElseThrow().revision()).isEqualTo(run.revision());
    }

    @Test
    void missingReasonIsRefused() throws Exception {
        ObjectNode body = payload();
        body.remove("reason");
        assertThat(complete(body, null).reasonCode()).isEqualTo(ReasonCode.INVALID);
    }

    @Test
    void unknownRunIsRefused() throws Exception {
        assertThat(complete(payload().put("run_id", UUID.randomUUID().toString()), null).reasonCode())
                .isEqualTo(RunCommands.NOT_FOUND);
    }
}
