package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Adapter failure semantics only; integrated Ops/Field tests use the real Ops authority bean. */
class MissionAuthorityPortTest {
    FieldPorts ports(DefaultListableBeanFactory beans) {
        return new FieldPorts(beans.getBeanProvider(AutomationSettings.class),beans.getBeanProvider(ClockControl.class),
                beans.getBeanProvider(CaptureRules.class),beans.getBeanProvider(MissionAuthority.class));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING","THROWING","NULL","AMBIGUOUS"})
    void unavailableAuthorityAlwaysRefusesInitialDeparture(String scenario) {
        var beans=new DefaultListableBeanFactory();
        if(scenario.equals("THROWING"))beans.registerSingleton("authority",(MissionAuthority)(run,mission)->{throw new IllegalStateException("fixture unavailable");});
        if(scenario.equals("NULL"))beans.registerSingleton("authority",(MissionAuthority)(run,mission)->null);
        if(scenario.equals("AMBIGUOUS")){
            beans.registerSingleton("one",(MissionAuthority)(run,mission)->MissionAuthority.Decision.permit());
            beans.registerSingleton("two",(MissionAuthority)(run,mission)->MissionAuthority.Decision.permit());
        }
        var decision=ports(beans).initialDeparture("run","mission");
        assertThat(decision.allowed()).isFalse();assertThat(decision.reasonCode()).isEqualTo("AUTHORITY_UNAVAILABLE");assertThat(decision.reasonText()).isNotBlank();
    }
    @Test void adapterPreservesSpecificAuthorityRefusal() {
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("authority",(MissionAuthority)(run,mission)->MissionAuthority.Decision.refuse("NEEDS_NEW_APPROVAL","Analysis changed."));
        var decision=ports(beans).initialDeparture("run","mission");assertThat(decision.allowed()).isFalse();assertThat(decision.reasonCode()).isEqualTo("NEEDS_NEW_APPROVAL");
    }
}
