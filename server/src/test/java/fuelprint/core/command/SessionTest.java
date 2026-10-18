package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SessionTest {

    @Test
    void aPersonsSessionCanNeverBeTheAutomationActor() {
        for (String reserved : new String[] {"automation", "Automation", " AUTOMATION ", "anonymous"}) {
            assertThatThrownBy(() -> Session.human(reserved, Set.of(Role.ANALYST)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Session.human("mallory", Set.of(Role.AUTOMATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Session.human("mallory", Set.of(Role.ANALYST, Role.AUTOMATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Session.human("nobody", Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheAutomationSessionIsAutomatic() {
        Session person = Session.human("supervisor@laptop-1", Set.of(Role.SUPERVISOR, Role.ANALYST));
        assertThat(person.isAutomation()).isFalse();
        assertThat(person.roles()).containsExactlyInAnyOrder(Role.SUPERVISOR, Role.ANALYST);
        assertThat(Session.anonymous().isAutomation()).isFalse();
        assertThat(Session.anonymous().isSignedIn()).isFalse();
        assertThat(Session.automation().isAutomation()).isTrue();
        assertThat(Session.automation().actor()).isEqualTo("automation");
        assertThat(Session.automation().roles()).containsExactly(Role.AUTOMATION);
    }

    @Test
    void rolesUseTheContractNames() {
        assertThat(Role.FIELD_OPERATOR.wire()).isEqualTo("field_operator");
        assertThat(Role.fromWire("qc_reviewer")).contains(Role.QC_REVIEWER);
        assertThat(Role.fromWire("root")).isEmpty();
        assertThat(Role.values()).hasSize(13);
    }
}
