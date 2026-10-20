package fuelprint.api;

/**
 * Read-only authority for an initial APPROVED mission departure. Field calls this on the command thread
 * immediately before granting the first mission lease. Ops reads current committed records, clock and wind;
 * this port does not approve, expire or save anything. Already-started flight and safety return are separate.
 */
public interface MissionAuthority {
    Decision checkInitialDeparture(String runId, String missionId);

    record Decision(boolean allowed, String reasonCode, String reasonText) {
        public Decision {
            if (allowed ? reasonCode != null || reasonText != null
                    : reasonCode == null || reasonCode.isBlank() || reasonText == null || reasonText.isBlank()) {
                throw new IllegalArgumentException("Authority decisions need either permission or a refusal reason.");
            }
        }
        public static Decision permit() { return new Decision(true, null, null); }
        public static Decision refuse(String code, String text) { return new Decision(false, code, text); }
    }
}
