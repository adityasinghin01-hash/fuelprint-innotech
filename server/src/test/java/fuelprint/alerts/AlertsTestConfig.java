package fuelprint.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.SendResult;
import fuelprint.api.SmsGateway;
import fuelprint.api.Wording;
import fuelprint.api.WordingRequest;
import fuelprint.api.WordingService;
import fuelprint.api.WordingSource;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test doubles for the alerts tests: a clock the test moves, the core's automation settings, the ai
 * package's wording service, a gateway that records what it was asked to send, and small handlers that
 * stand in for the ops package (which saves {@code investigation} and {@code board_decision}) and the ai
 * package (which saves {@code wording}).
 */
@TestConfiguration
class AlertsTestConfig {

    /** 10:00 IST on 22 Oct 2026. */
    static final Instant START = Instant.parse("2026-10-22T04:30:00Z");

    /** A wall clock the test can move. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration by) {
            now.updateAndGet(current -> current.plus(by));
        }

        void set(Instant instant) {
            now.set(instant);
        }
    }

    /** The core's automation settings, with every automatic step on unless the test switches one off. */
    static final class FakeAutomationSettings implements AutomationSettings {
        private final Set<String> off = Collections.synchronizedSet(new HashSet<>());

        @Override
        public boolean isOn(String setting) {
            return !off.contains(setting);
        }

        @Override
        public int autopilotWaitS() {
            return 60;
        }

        void set(String setting, boolean on) {
            if (on) {
                off.remove(setting);
            } else {
                off.add(setting);
            }
        }
    }

    /** The ai package's wording service: returns the template unless the test sets a text. */
    static final class FakeWordingService implements WordingService {
        final List<WordingRequest> requests = new CopyOnWriteArrayList<>();
        private volatile String forcedText;

        void reply(String text) {
            this.forcedText = text;
        }

        void reset() {
            forcedText = null;
            requests.clear();
        }

        @Override
        public Wording write(WordingRequest request) {
            requests.add(request);
            String text = forcedText == null ? request.publicText() : forcedText;
            return new Wording(request.target(), request.purpose(), text,
                    forcedText == null ? WordingSource.TEMPLATE : WordingSource.LLM,
                    forcedText == null ? null : "test-model", "FP-WORD-1", List.of(), null);
        }
    }

    /** Records every send, then lets the real alerts gateway (LOG_ONLY or REAL) handle it. */
    static final class RecordingGateway implements SmsGateway {
        record Sent(String phone, String text) {
        }

        final List<Sent> sent = new CopyOnWriteArrayList<>();
        private final SmsGateway delegate;

        RecordingGateway(SmsGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public SendResult send(String phoneE164, String text) {
            sent.add(new Sent(phoneE164, text));
            return delegate.send(phoneE164, text);
        }

        List<String> phones() {
            List<String> phones = new ArrayList<>();
            sent.forEach(item -> phones.add(item.phone()));
            return phones;
        }
    }

    /** Stands in for ops: saves an {@code investigation} from the payload. */
    static final class SaveInvestigation implements CommandHandler {
        static final String TYPE = "test_save_investigation";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            return saveFrom(command, "investigation", "INVESTIGATION_SAVED", store);
        }
    }

    /** Stands in for ops: saves a {@code board_decision} from the payload. */
    static final class SaveBoardDecision implements CommandHandler {
        static final String TYPE = "test_save_board_decision";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.BOARD);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            return saveFrom(command, "board_decision", "BOARD_DECISION_SAVED", store);
        }
    }

    /** Stands in for ai: saves a {@code wording} record from the payload. */
    static final class SaveWording implements CommandHandler {
        static final String TYPE = "test_save_wording";

        @Override
        public String commandType() {
            return TYPE;
        }

        @Override
        public Set<Role> allowedRoles() {
            return Set.of(Role.ANALYST);
        }

        @Override
        public HandlerOutcome handle(Command command, Session session, StoreView store) {
            return saveFrom(command, "wording", "WORDING_SAVED", store);
        }
    }

    /**
     * Saves {@code payload.fields} as record {@code payload.id} of {@code type} (a new revision if it exists).
     * {@code payload.run_id} is the record's run.
     */
    private static HandlerOutcome saveFrom(Command command, String type, String eventType, StoreView store) {
        ObjectNode payload = command.payload();
        String id = payload.path("id").asText();
        ObjectNode fields = (ObjectNode) payload.get("fields");
        String runId = payload.path("run_id").isTextual() ? payload.path("run_id").asText() : null;
        RecordChange change = store.get(type, id)
                .map(current -> RecordChange.next(current, fields))
                .orElseGet(() -> RecordChange.create(type, id, runId, fields));
        return HandlerResult.builder().save(change, eventType, null).build();
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    FakeAutomationSettings fakeAutomationSettings() {
        return new FakeAutomationSettings();
    }

    @Bean
    FakeWordingService fakeWordingService() {
        return new FakeWordingService();
    }

    @Bean
    @Primary
    RecordingGateway recordingGateway(AlertsSmsGateway real) {
        return new RecordingGateway(real);
    }

    @Bean
    SaveInvestigation saveInvestigation() {
        return new SaveInvestigation();
    }

    @Bean
    SaveBoardDecision saveBoardDecision() {
        return new SaveBoardDecision();
    }

    @Bean
    SaveWording saveWording() {
        return new SaveWording();
    }
}
