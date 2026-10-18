package fuelprint.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import fuelprint.core.command.CommandHandler;
import fuelprint.core.command.HandlerRegistry;
import fuelprint.core.command.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The roles of AGREEMENT section 5 for the four alerts commands, and the package rules. */
class HandlerRulesTest extends AlertsSpringTest {

    @Autowired HandlerRegistry registry;

    private Set<Role> roles(String type) {
        return registry.find(type).orElseThrow().allowedRoles();
    }

    @Test
    void theFourCommandsAreRegisteredWithTheRolesOfSection5() {
        assertThat(roles("subscribe_alerts")).containsExactlyInAnyOrder(Role.GUEST, Role.VISITOR);
        assertThat(roles("unsubscribe_alerts")).containsExactlyInAnyOrder(Role.GUEST, Role.VISITOR, Role.PRESENTER);
        assertThat(roles("send_alert")).containsExactlyInAnyOrder(Role.AUTOMATION, Role.PRESENTER);
        assertThat(roles("purge_subscriptions")).containsExactlyInAnyOrder(Role.PRESENTER, Role.AUTOMATION);
    }

    @Test
    void noAlertsCommandIsOneOfTheFiveHumanOnlyCommands() {
        for (String type : new String[] {"subscribe_alerts", "unsubscribe_alerts", "send_alert", "purge_subscriptions"}) {
            assertThat(HandlerRegistry.HUMAN_ONLY_COMMANDS).doesNotContain(type);
        }
    }

    @Test
    void theAutomationActorIsRefusedForTheFiveHumanCommandsWhichAlertsNeverRegisters() {
        for (String type : HandlerRegistry.HUMAN_ONLY_COMMANDS) {
            registry.find(type).ifPresent(handler -> {
                assertThat(handler.getClass().getPackageName()).as(type).isNotEqualTo("fuelprint.alerts");
                assertThat(handler.allowedRoles()).as(type).doesNotContain(Role.AUTOMATION);
            });
        }
    }

    @Test
    void subscribeAndUnsubscribeAreNeverOpenToAutomation() {
        assertThat(roles("subscribe_alerts")).doesNotContain(Role.AUTOMATION);
        assertThat(roles("unsubscribe_alerts")).doesNotContain(Role.AUTOMATION);
    }

    @Test
    void everyAlertsBeanIsARealBeanThatIsOffInTheStubProfile() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("fuelprint.alerts");
        assertThat(classes.stream().filter(c -> c.isAnnotatedWith(Component.class) || c.isAnnotatedWith(Configuration.class)))
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.isAnnotatedWith(Profile.class)).as(c.getName()).isTrue();
                    assertThat(c.getAnnotationOfType(Profile.class).value()).as(c.getName()).containsExactly("!stub");
                });
    }

    @Test
    void alertsOnlyUsesTheApiAndTheCoreCommandAndStoreTypes() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("fuelprint");
        classes.stream().filter(c -> c.getPackageName().equals("fuelprint.alerts")).forEach(c ->
                c.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String target = dependency.getTargetClass().getPackageName();
                    assertThat(target).as(c.getName() + " -> " + dependency.getTargetClass().getName())
                            .doesNotStartWith("fuelprint.science").doesNotStartWith("fuelprint.ops")
                            .doesNotStartWith("fuelprint.cases").doesNotStartWith("fuelprint.field")
                            .doesNotStartWith("fuelprint.ai").doesNotContain(".truth");
                }));
        assertThat(CommandHandler.class).isNotNull();
    }
}
