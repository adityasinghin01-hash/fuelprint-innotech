package fuelprint.cases;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import fuelprint.api.SessionService;
import fuelprint.core.Ids;
import fuelprint.core.command.Role;
import fuelprint.core.command.Session;
import fuelprint.core.store.BlobStore;
import fuelprint.core.store.RecordStore;
import fuelprint.core.store.StoredRecord;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/exports/{export_id}/pack.zip?run_id=} for a signed-in exporter station. Authorization is checked
 * before any record lookup. The whole ZIP is prepared and validated in a private temporary file before any success
 * header, so a missing or damaged blob gives a JSON refusal instead of a partial 200 ZIP. Nothing is written to the
 * record store. A network interruption during transfer can still truncate the response; the offline verifier
 * refuses truncated packs.
 */
@RestController @Profile("!stub")
public class EvidencePackController {
    static final Set<String> PACKABLE = Set.of("SEALED", "TAMPER_DEMO_COPY");
    private final SessionService sessions;
    private final RecordStore store;
    private final BlobStore blobs;
    private final ObjectMapper json = new ObjectMapper();

    EvidencePackController(SessionService sessions, RecordStore store, BlobStore blobs) {
        this.sessions = sessions; this.store = store; this.blobs = blobs;
    }

    @GetMapping("/api/exports/{exportId}/pack.zip")
    public void pack(@PathVariable("exportId") String exportId, @RequestParam(name = "run_id", required = false) String runId,
                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        Optional<Session> session = sessions.resolve(request);
        if (session.isEmpty() || !allowed(session.get())) {
            refuse(response, 403, "NOT_ALLOWED", "Downloading an evidence pack needs a signed-in exporter station.");
            return;
        }
        if (!Ids.isUuid(exportId) || runId == null || !Ids.isUuid(runId)) {
            refuse(response, 400, "INVALID", "export_id and run_id must be UUIDs.");
            return;
        }
        Optional<StoredRecord> export = store.get("export", exportId).filter(r -> runId.equals(r.runId()));
        if (export.isEmpty()) {
            refuse(response, 404, "NOT_FOUND", "No export with this id is recorded in this run.");
            return;
        }
        String state = export.get().body().path("state").asText();
        if (!PACKABLE.contains(state)) {
            refuse(response, 400, "NOT_A_PACKABLE_EXPORT", "Only sealed exports and labelled tamper demo copies can be downloaded.");
            return;
        }
        Path file;
        try {
            file = EvidenceArchive.prepare(export.get(), blobs, Path.of(System.getProperty("java.io.tmpdir")));
        } catch (EvidenceArchive.Refusal r) {
            int status = EvidenceVault.MISSING.equals(r.code) ? 409 : "EVIDENCE_TOO_LARGE".equals(r.code) ? 413 : 500;
            refuse(response, status, r.code, r.getMessage());
            return;
        }
        try {
            String label = "SEALED".equals(state) ? "ORIGINAL" : "TAMPER_DEMO_COPY";
            response.setStatus(200);
            response.setContentType("application/zip");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "attachment; filename=\"fp-evidence-" + exportId.substring(0, 8) + "-" + label + ".zip\"");
            response.setContentLengthLong(Files.size(file));
            try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
            }
        } finally {
            EvidenceArchive.delete(file);
        }
    }

    static boolean allowed(Session s) {
        return s.isSignedIn() && !s.isAutomation() && s.stationId() != null && s.roles().contains(Role.EXPORTER);
    }

    private void refuse(HttpServletResponse response, int status, String code, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        byte[] body = json.writeValueAsBytes(json.createObjectNode().put("reason_code", code).put("reason_text", reason));
        response.getOutputStream().write(body);
    }
}
