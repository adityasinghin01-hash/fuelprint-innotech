package fuelprint.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import fuelprint.core.command.CommandBus;
import org.junit.jupiter.api.Test;

/** CORE-API section 1: only the core writes records without a command (world loop, recovery, defaults, replay). */
class CoreWriteRulesTest {

    @Test
    void onlyTheCoreSavesRecordsWithoutACommand() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("fuelprint");
        ArchRule rule = noClasses().that().resideOutsideOfPackage("fuelprint.core..")
                .should().callMethod(CommandBus.class, "saveWithoutCommand", String.class,
                        fuelprint.core.command.HandlerResult.class, boolean.class)
                .orShould().callMethod(CommandBus.class, "saveRecordedCommand",
                        fuelprint.core.command.RecordedCommand.class)
                .because("other packages change the world only through command handlers and AutomationBus")
                .allowEmptyShould(true);
        rule.check(main);
    }
}
