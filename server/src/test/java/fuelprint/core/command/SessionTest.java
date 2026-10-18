package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SessionTest {

    @Test
    void aPersonsSessionCanNeverBeTheAutomationActor() {
        for (String reserved : new String[] {"automation", "Automation", " AUTOMATION ", "anonymous"}) {
            assertThatThrownBy(() -> Session.human("s-test", reserved, Set.of(Role.ANALYST)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> Session.human("s-test", "mallory", Set.of(Role.AUTOMATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Session.human("s-test", "mallory", Set.of(Role.ANALYST, Role.AUTOMATION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Session.human("s-test", "nobody", Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Session.human(" ", "someone", Set.of(Role.GUEST))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTheAutomationSessionIsAutomatic() {
        Session person = Session.human("s-test", "supervisor@laptop-1", Set.of(Role.SUPERVISOR, Role.ANALYST));
        assertThat(person.isAutomation()).isFalse();
        assertThat(person.sessionId()).isEqualTo("s-test");
        assertThat(person.actorLabel()).isEqualTo("supervisor@laptop-1");
        assertThat(person.toString()).doesNotContain("s-test");
        assertThat(Session.anonymous().sessionId()).isNull();
        assertThat(Session.automation().sessionId()).isNull();
        assertThat(person.roles()).containsExactlyInAnyOrder(Role.SUPERVISOR, Role.ANALYST);
        assertThat(Session.anonymous().isAutomation()).isFalse();
        assertThat(Session.anonymous().isSignedIn()).isFalse();
        assertThat(Session.automation().isAutomation()).isTrue();
        assertThat(Session.automation().actorLabel()).isEqualTo("automation");
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
