package fuelprint.field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fuelprint.api.AutomationSettings;
import fuelprint.api.CaptureInput;
import fuelprint.api.CaptureRules;
import fuelprint.api.ChannelStatus;
import fuelprint.api.ChannelVerdict;
import fuelprint.api.ClockControl;
import fuelprint.api.Reading;
import fuelprint.api.SessionService;
import fuelprint.api.Stage;
import fuelprint.api.VehicleSource;
import fuelprint.core.command.Command;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerOutcome;
import fuelprint.core.command.HandlerResult;
import fuelprint.core.command.Rejection;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.RecordChange;
import fuelprint.core.store.StoreView;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test doubles for every {@code fuelprint.api} interface field depends on (core session, clock and settings;
 * science capture rules), plus a manual wall clock and a test-only command that saves {@code mission} records
 * the way ops will. None of the core's stub classes are used.
 */
@TestConfiguration
public class FieldTestConfig {

    @Bean
    @Primary
    public ManualFieldTime manualFieldTime() {
        return new ManualFieldTime();
    }

    @Bean
    @Primary
    public FakeSessions fakeSessions() {
        return new FakeSessions();
    }

    @Bean
    public FakeClock fakeClock(ObjectProvider<VehicleSource> vehicles) {
        return new FakeClock(vehicles);
    }

    @Bean
    public FakeSettings fakeSettings() {
        return new FakeSettings();
    }

    @Bean
    public FakeCaptureRules fakeCaptureRules() {
        return new FakeCaptureRules();
    }

    @Bean
    public PutMission putMission() {
        return new PutMission();
    }

    /** Wall time that moves only when the test says so. */
    public static class ManualFieldTime implements FieldTime {
        private final AtomicLong millis = new AtomicLong(1_000_000);
        private final Instant base = Instant.parse("2026-10-22T10:00:00.000Z");

        @Override
        public long monoMillis() {
            return millis.get();
        }

        @Override
        public Instant now() {
            return base.plusMillis(millis.get());
        }

        public void advanceMillis(long ms) {
            millis.addAndGet(ms);
        }
    }

    /** Cookie sessions kept in memory; same-origin unless an {@code Origin} header names another host. */
    public static class FakeSessions implements SessionService {
        public static final String COOKIE = "fp_session";
        private final Map<String, Session> byToken = new ConcurrentHashMap<>();
        private final Map<String, String> tokenByActor = new ConcurrentHashMap<>();

        /** Registers {@code session} and returns its cookie value. */
        public String register(Session session) {
            String token = UUID.randomUUID().toString();
            byToken.put(token, session);
            tokenByActor.put(session.actorLabel(), token);
            return token;
        }

        public Optional<String> tokenOf(String actor) {
            return Optional.ofNullable(tokenByActor.get(actor));
        }

        @Override
        public Optional<Session> resolve(HttpServletRequest request) {
            Cookie[] cookies = request.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (COOKIE.equals(cookie.getName()) && byToken.containsKey(cookie.getValue())) {
                        return Optional.of(byToken.get(cookie.getValue()));
                    }
                }
            }
            String header = request.getHeader("Cookie");
            if (header != null) {
                for (String part : header.split(";")) {
                    String[] kv = part.trim().split("=", 2);
                    if (kv.length == 2 && COOKIE.equals(kv[0]) && byToken.containsKey(kv[1])) {
                        return Optional.of(byToken.get(kv[1]));
                    }
                }
            }
            return Optional.empty();
        }

        @Override
        public Session createStation(String stationId, Set<String> roles) {
            throw new UnsupportedOperationException("not used by field");
        }

        @Override
        public Session createVisitor(String visitorLabel) {
            return Session.human("s-" + UUID.randomUUID(), visitorLabel, Set.of(Role.VISITOR));
        }

        @Override
        public Session createGuest() {
            throw new UnsupportedOperationException("not used by field");
        }

        @Override
        public void writeCookie(HttpServletResponse response, Session session) {
            String token = register(session);
            response.addHeader("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax");
        }

        @Override
        public boolean originAllowed(HttpServletRequest request) {
            String origin = request.getHeader("Origin");
            return origin == null || origin.startsWith("http://localhost");
        }
    }

    /** Clock control: grants time scale 15 only while every vehicle is stationary, like the core will. */
    public static class FakeClock implements ClockControl {
        private final ObjectProvider<VehicleSource> vehicles;
        private final Map<String, Double> requests = new ConcurrentHashMap<>();
        public final List<String> log = Collections.synchronizedList(new ArrayList<>());

        FakeClock(ObjectProvider<VehicleSource> vehicles) {
            this.vehicles = vehicles;
        }

        public void reset() {
            requests.clear();
            log.clear();
        }

        @Override
        public double simTimeS() {
            return 0;
        }

        @Override
        public double timeScale() {
            if (requests.isEmpty()) {
                return 1;
            }
            boolean still = vehicles.stream().flatMap(v -> v.vehicles().stream()).allMatch(v ->
                    v.velocity().east() == 0 && v.velocity().north() == 0 && v.velocity().up() == 0);
            return still ? requests.values().stream().mapToDouble(Double::doubleValue).max().orElse(1) : 1;
        }

        @Override
        public void requestTimeScale(String requester, double scale) {
            requests.put(requester, scale);
            log.add("request " + requester + " " + scale);
        }

        @Override
        public void releaseTimeScale(String requester) {
            requests.remove(requester);
            log.add("release " + requester);
        }
    }

    /** Automation settings the test switches. */
    public static class FakeSettings implements AutomationSettings {
        private final Set<String> on = ConcurrentHashMap.newKeySet();
        private volatile int waitS = 60;

        public void reset() {
            on.clear();
            waitS = 60;
        }

        public FakeSettings set(String setting, boolean value) {
            if (value) {
                on.add(setting);
            } else {
                on.remove(setting);
            }
            return this;
        }

        public void waitS(int seconds) {
            waitS = seconds;
        }

        @Override
        public boolean isOn(String setting) {
            return on.contains(setting);
        }

        @Override
        public int autopilotWaitS() {
            return waitS;
        }
    }

    /**
     * Capture rules for tests: in COLLECT, a second is valid when the channel reading is OK and its branch flow is
     * OK and within 5 % of 0.5 (SO2) or 0.3 (CO2) L/min. 30 consecutive valid seconds is VALID; anything less is
     * REJECTED with the flags seen. Disabled, it returns null (no rules available).
     */
    public static class FakeCaptureRules implements CaptureRules {
        public volatile boolean enabled = true;
        public final List<CaptureInput> calls = Collections.synchronizedList(new ArrayList<>());

        public void reset() {
            enabled = true;
            calls.clear();
        }

        @Override
        public ChannelVerdict judge(CaptureInput input) {
            calls.add(input);
            if (!enabled) {
                return null;
            }
            Stage collect = input.stages().stream().filter(s -> s.stage().equals("COLLECT")).findFirst().orElse(null);
            if (collect == null || collect.endS() == null) {
                return new ChannelVerdict(input.channel(), null, null, ChannelStatus.REJECTED, List.of("NO_COLLECT"));
            }
            double nominal = input.channel().startsWith("SO2") ? 0.5 : 0.3;
            Map<Integer, Reading> flows = new HashMap<>();
            for (Reading r : input.readings()) {
                if ("L_min".equals(r.unit())) {
                    flows.put((int) Math.floor(r.simTimeS() + 1e-6), r);
                }
            }
            int run = 0;
            int best = 0;
            Set<String> reasons = new LinkedHashSet<>();
            Set<Integer> seen = new HashSet<>();
            for (Reading r : input.readings()) {
                int second = (int) Math.floor(r.simTimeS() + 1e-6);
                if (!input.channel().equals(r.channel()) || second < collect.startS() || second >= collect.endS()
                        || !seen.add(second)) {
                    continue;
                }
                Reading flow = flows.get(second);
                boolean ok = r.value() != null && r.quality().equals(List.of("OK")) && flow != null
                        && flow.value() != null && flow.quality().equals(List.of("OK"))
                        && Math.abs(flow.value() - nominal) <= 0.05 * nominal;
                if (ok) {
                    run++;
                    best = Math.max(best, run);
                    if (best >= 30) {
                        break;
                    }
                } else {
                    run = 0;
                    r.quality().stream().filter(q -> !q.equals("OK")).forEach(reasons::add);
                }
            }
            double volume = best * nominal / 60.0;
            if (best >= 30) {
                return new ChannelVerdict(input.channel(), 30.0, volume, ChannelStatus.VALID, List.of());
            }
            reasons.add("SHORT_VALID_COLLECTION");
            return new ChannelVerdict(input.channel(), (double) best, volume, ChannelStatus.REJECTED, List.copyOf(reasons));
        }
    }

    /**
     * Test-only {@code test_put_mission} (analyst): saves the next revision of a {@code mission} record with the
     * given fields, as ops will (ops is not built yet). Payload: mission_id, fields.
     */
    public static class PutMission implements CommandHandler {
        public static final String TYPE = "test_put_mission";

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
            String id = command.payload().path("mission_id").asText(null);
            JsonNode fields = command.payload().get("fields");
            if (id == null || fields == null || !fields.isObject()) {
                return Rejection.invalid("mission_id and fields are required");
            }
            RecordChange change = store.get("mission", id)
                    .map(current -> RecordChange.next(current, (ObjectNode) fields))
                    .orElseGet(() -> RecordChange.create("mission", id, command.runId(), (ObjectNode) fields));
            return HandlerResult.builder().save(change, "MISSION_SAVED_BY_TEST", null).build();
        }
    }
}
