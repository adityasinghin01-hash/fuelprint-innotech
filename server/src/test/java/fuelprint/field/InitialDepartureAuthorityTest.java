package fuelprint.field;

import static org.assertj.core.api.Assertions.assertThat;

import fuelprint.api.MissionAuthority;
import fuelprint.core.command.Receipt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** Isolated Field grant/safety behavior with an explicit authority double, not Ops authority integration. */
class InitialDepartureAuthorityTest extends FieldSpringTest {
    @Autowired FieldTestConfig.FakeMissionAuthority authority;
    @BeforeEach void resetAuthority(){authority.reset();}
    @AfterEach void restoreAuthority(){authority.reset();}

    @ParameterizedTest @ValueSource(strings={"acquire_lease","start_autopilot","take_over"})
    void everyInitialMissionGrantHonorsAuthorityRefusal(String type) {
        String mission=approvedMission();authority.decision=MissionAuthority.Decision.refuse("NEEDS_NEW_APPROVAL","Analysis changed.");
        var p=type.equals("take_over")?payload().put("vehicle_id",world.droneId()):missionVehicle(mission);
        Receipt receipt=send(OPERATOR,type,p);
        assertThat(receipt.reasonCode()).isEqualTo("NEEDS_NEW_APPROVAL");assertThat(receipt.recordRefs()).isEmpty();assertThat(records("lease")).isEmpty();assertThat(missionState(mission)).isEqualTo("APPROVED");
    }
    @Test void validatorFailureCannotGrantALease() {
        String mission=approvedMission();authority.fail=true;Receipt receipt=send(OPERATOR,"acquire_lease",missionVehicle(mission));
        assertThat(receipt.reasonCode()).isEqualTo("AUTHORITY_UNAVAILABLE");assertThat(records("lease")).isEmpty();assertThat(missionState(mission)).isEqualTo("APPROVED");
    }
    @Test void failedAuthorityDoesNotBlockStartedHandoverResumeOrSafetyReturn() {
        String mission=approvedMission();assertThat(send(OPERATOR,"start_autopilot",missionVehicle(mission)).applied()).isTrue();
        tickUntil(()->!world.landed(),200,"legitimate departure");settle();authority.fail=true;
        assertThat(send(OPERATOR,"take_over",payload().put("vehicle_id",world.droneId())).applied()).isTrue();
        String lease=world.lease().orElseThrow().leaseId();assertThat(send(OPERATOR,"release_lease",payload().put("lease_id",lease)).applied()).isTrue();
        assertThat(send(OPERATOR,"start_autopilot",missionVehicle(mission)).applied()).isTrue();
        assertThat(send(OPERATOR,"return_to_depot",payload().put("vehicle_id",world.droneId()).put("reason","OPERATOR")).applied()).isTrue();
        assertThat(missionState(mission)).isEqualTo("RETURNING");
    }
}
