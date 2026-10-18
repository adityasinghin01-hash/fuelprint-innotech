package fuelprint.core.stub;

import java.util.List;
import java.util.Optional;

import fuelprint.api.Analysis;
import fuelprint.api.AnalysisInput;
import fuelprint.api.ExpectedBand;
import fuelprint.api.LearnedOrder;
import fuelprint.api.LearnedRanker;
import fuelprint.api.LearnedStatus;
import fuelprint.api.NormalAirModel;
import fuelprint.api.Outcome;
import fuelprint.api.WindNow;
import fuelprint.api.Wording;
import fuelprint.api.WordingCheck;
import fuelprint.api.WordingPurpose;
import fuelprint.api.WordingRequest;
import fuelprint.api.WordingService;
import fuelprint.api.WordingSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stub AI pieces: the learned ranker has no model ({@code MODEL_MISSING}, or {@code NOT_APPLICABLE} for INVALID
 * and NO_SIGNAL), the normal-air model has no band, and the wording service always returns its fixed template.
 */
@Component
@Profile("stub")
public class StubAi implements LearnedRanker, NormalAirModel, WordingService {

    static final String SMS_PREFIX = "FuelPrint DEMO (simulated): ";

    @Override
    public LearnedOrder order(AnalysisInput input, Analysis physics) {
        boolean nothing = physics.outcome() == Outcome.INVALID || physics.outcome() == Outcome.NO_SIGNAL;
        return LearnedOrder.none(nothing ? LearnedStatus.NOT_APPLICABLE : LearnedStatus.MODEL_MISSING, null, null);
    }

    @Override
    public Optional<ExpectedBand> expected(String sensor, String channel, double simTimeS, WindNow wind) {
        return Optional.empty();
    }

    @Override
    public Wording write(WordingRequest request) {
        String text = switch (request.purpose() == null ? WordingPurpose.EXPLANATION : request.purpose()) {
            case SMS_SMOKE_WARNING -> SMS_PREFIX + "smoke may drift toward your area. Investigation started. No action needed.";
            case SMS_BOARD_UPDATE -> SMS_PREFIX + "the board has reviewed the smoke investigation near your area.";
            default -> request.publicText() == null || request.publicText().isBlank()
                    ? "No plain-words text is available." : request.publicText();
        };
        if (text.length() > 160 && request.purpose() != null && request.purpose().name().startsWith("SMS")) {
            text = text.substring(0, 160);
        }
        return new Wording(request.target(), request.purpose(), text, WordingSource.TEMPLATE, null, "FP-WORD-1",
                List.of(new WordingCheck("numbers_match_input", true), new WordingCheck("banned_words", true),
                        new WordingCheck("length", true)), "STUB_NO_MODEL");
    }
}
