package fuelprint.api;

/** Whether the server runs the live world or plays a recording (CORE-API section 7); core implements. */
public interface ServerMode {

    /** True in fake replay mode ({@code --fp.mode=replay}): records come from a recording, not the world loop. */
    boolean isReplay();

    /**
     * True if {@code AutomationBus.submit} runs commands in this mode. In replay mode it is off by default, because
     * the recording already holds every automatic step; listeners would otherwise repeat them.
     */
    boolean automationActive();
}
