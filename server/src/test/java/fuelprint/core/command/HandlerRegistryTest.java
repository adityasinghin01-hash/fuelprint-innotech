package fuelprint.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import fuelprint.core.store.StoreView;
import org.junit.jupiter.api.Test;

class HandlerRegistryTest {

    static CommandHandler handler(String type, Set<Role> roles) {
        return new CommandHandler() {
            @Override
            public String commandType() {
                return type;
            }

            @Override
            public Set<Role> allowedRoles() {
                return roles;
            }

            @Override
            public HandlerOutcome handle(Command command, Session session, StoreView store) {
                return HandlerResult.nothing();
            }
        };
    }

    @Test
    void theFiveHumanOnlyCommandsAreExactlyTheContractOnes() {
        assertThat(HandlerRegistry.HUMAN_ONLY_COMMANDS).containsExactlyInAnyOrder(
                "approve_mission", "reject_mission", "review_capture", "revise_finding", "record_board_decision");
    }

    @Test
    void aHumanOnlyCommandThatAllowsAutomationIsRefusedAtStartUp() {
        for (String type : HandlerRegistry.HUMAN_ONLY_COMMANDS) {
            assertThatThrownBy(() -> new HandlerRegistry(List.of(handler(type, Set.of(Role.SUPERVISOR, Role.AUTOMATION)))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("human-only");
        }
        assertThat(new HandlerRegistry(List.of(handler("approve_mission", Set.of(Role.SUPERVISOR))))
                .find("approve_mission")).isPresent();
    }

    @Test
    void duplicateTypesAndHandlersWithoutRolesAreRefused() {
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler("x_cmd", Set.of(Role.ANALYST)),
                handler("x_cmd", Set.of(Role.ANALYST))))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler("y_cmd", Set.of()))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HandlerRegistry(List.of(handler("Bad-Type", Set.of(Role.ANALYST)))))
                .isInstanceOf(IllegalStateException.class);
    }
}
