package fuelprint.api;

/** The current {@code automation_settings} record (core implements; changed only by {@code change_automation}). */
public interface AutomationSettings {

    /** Setting names of AGREEMENT section 6; all are on by default. */
    String AUTO_PROPOSE = "auto_propose";
    String AUTO_AUTOPILOT = "auto_autopilot";
    String AUTO_CAPTURE = "auto_capture";
    String AUTO_SEAL = "auto_seal";
    String AUTO_ALERTS = "auto_alerts";
    String AUTO_RETRAIN = "auto_retrain";

    /** True if the automatic step {@code setting} (one of the names above) is switched on. */
    boolean isOn(String setting);

    /** Seconds the autopilot waits for a person before it starts (default 60). */
    int autopilotWaitS();
}
