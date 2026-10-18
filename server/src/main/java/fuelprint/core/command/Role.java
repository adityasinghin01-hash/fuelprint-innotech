package fuelprint.core.command;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/** The roles of AGREEMENT section 4. {@link #wire()} is the name used in JSON and station config. */
public enum Role {
    PRESENTER("presenter"),
    ANALYST("analyst"),
    SUPERVISOR("supervisor"),
    FIELD_OPERATOR("field_operator"),
    QC_REVIEWER("qc_reviewer"),
    CASE_REVIEWER("case_reviewer"),
    INSPECTOR_DEMO("inspector_demo"),
    LAB_DEMO("lab_demo"),
    EXPORTER("exporter"),
    BOARD("board"),
    VISITOR("visitor"),
    GUEST("guest"),
    /** The server itself. Only {@link AutomationBus} can act with this role; no session from a request can. */
    AUTOMATION("automation");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The role with this wire name, if any. */
    public static Optional<Role> fromWire(String wire) {
        return Arrays.stream(values()).filter(role -> role.wire.equals(wire)).findFirst();
    }
}
