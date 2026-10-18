package fuelprint.field.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fuelprint.api.SessionService;
import fuelprint.core.command.Session;
import fuelprint.field.world.FieldWorld;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/field/queue}: the public, pseudonymous visitor queue (visitor labels only, first in line
 * first) and, for a signed-in visitor, their own place. Not in AGREEMENT section 3; see the P6 report.
 */
@RestController
@Profile("!stub")
public class QueueController {

    private final FieldWorld world;
    private final ObjectProvider<SessionService> sessions;

    public QueueController(FieldWorld world, ObjectProvider<SessionService> sessions) {
        this.world = world;
        this.sessions = sessions;
    }

    @GetMapping(path = "/api/field/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> queue(HttpServletRequest request) {
        String me = me(request);
        List<Map<String, Object>> entries = new ArrayList<>();
        Integer myPlace = null;
        Map<String, Integer> perMission = new LinkedHashMap<>();
        for (FieldWorld.QueueEntry entry : world.queue()) {
            int place = perMission.merge(entry.missionId(), 1, Integer::sum);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mission_id", entry.missionId());
            row.put("position", place);
            row.put("visitor", entry.visitor());
            entries.add(row);
            if (entry.visitor().equals(me)) {
                myPlace = place;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", world.runId().orElse(null));
        out.put("entries", entries);
        out.put("your_position", myPlace);
        out.put("lease_holder", world.lease().filter(l -> !l.ending()).map(FieldWorld.LeaseInfo::holder).orElse(null));
        return out;
    }

    private String me(HttpServletRequest request) {
        SessionService service = sessions.getIfAvailable();
        if (service == null) {
            return null;
        }
        try {
            Optional<Session> session = service.resolve(request);
            return session == null ? null : session.map(Session::actorLabel).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
