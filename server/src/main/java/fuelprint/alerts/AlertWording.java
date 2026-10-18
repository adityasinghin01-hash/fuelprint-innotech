package fuelprint.alerts;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import fuelprint.api.Ref;
import fuelprint.api.Wording;
import fuelprint.api.WordingPurpose;
import fuelprint.api.WordingRequest;
import fuelprint.api.WordingService;
import fuelprint.core.store.StoreView;
import fuelprint.core.store.StoredRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Chooses the text of one SMS: plain words from the {@link WordingService} if they pass every check of
 * {@link AlertTexts#failures}, otherwise the fixed template. The service only ever gets the template text
 * (public words with no number, no factory and no phone number). If there is no wording service at all
 * (the ai package is not there yet) the template is used.
 */
@Component
@Profile("!stub")
class AlertWording {

    /** The text to send and where it came from. */
    record Chosen(String text, String source, String wordingId, String fallbackReason) {
    }

    private static final Logger log = LoggerFactory.getLogger(AlertWording.class);

    private final ObjectProvider<WordingService> service;

    AlertWording(ObjectProvider<WordingService> service) {
        this.service = service;
    }

    /** The template as the chosen text (used for TEST messages and for suppressed notifications). */
    static Chosen template(String template, String reason) {
        return new Chosen(template, "TEMPLATE", null, reason);
    }

    Chosen choose(Ref cause, WordingPurpose purpose, String template, StoreView store, String runId) {
        WordingService wordings = service.getIfAvailable();
        if (wordings == null) {
            return template(template, "NO_WORDING_SERVICE");
        }
        Wording wording;
        try {
            wording = wordings.write(new WordingRequest(cause, purpose, template));
        } catch (RuntimeException e) {
            log.warn("The wording service failed ({}); using the template", e.getClass().getSimpleName());
            return template(template, "WORDING_FAILED");
        }
        if (wording == null || wording.text() == null) {
            return template(template, "WORDING_EMPTY");
        }
        List<String> failed = AlertTexts.failures(wording.text(), template);
        if (!failed.isEmpty()) {
            log.info("The wording was refused by the SMS checks ({}); using the template", String.join(",", failed));
            return template(template, "SMS_CHECK_FAILED:" + String.join(",", failed));
        }
        String source = wording.source() == null ? "TEMPLATE" : wording.source().name();
        return new Chosen(wording.text(), source, wordingId(store, runId, cause, purpose, wording.text()),
                wording.fallbackReason());
    }

    /**
     * {@link Wording} carries no id, so the id of the saved {@code wording} record is found by what it
     * describes: the same target, purpose and text. Null if the ai package has not saved one.
     */
    private static String wordingId(StoreView store, String runId, Ref cause, WordingPurpose purpose, String text) {
        for (StoredRecord record : store.list(AlertRecords.WORDING, runId)) {
            JsonNode body = record.body();
            JsonNode target = body.path("target");
            if (purpose.name().equals(body.path("purpose").asText())
                    && text.equals(body.path("text").asText(null))
                    && cause.type().equals(target.path("type").asText())
                    && cause.id().equals(target.path("id").asText())
                    && cause.revision() == target.path("revision").asLong(-1)) {
                return record.id();
            }
        }
        return null;
    }
}
