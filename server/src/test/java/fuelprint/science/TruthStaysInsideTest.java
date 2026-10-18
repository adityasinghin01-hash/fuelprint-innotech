package fuelprint.science;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Hidden truth never leaves its package through a public signature, and the api package never sees it. */
class TruthStaysInsideTest {

    private final JavaClasses main = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("fuelprint");

    @Test
    void noPublicMethodOutsideTruthReturnsATruthType() {
        methods().that().arePublic().and().areDeclaredInClassesThat().resideInAPackage("fuelprint.science..")
                .and().areDeclaredInClassesThat().resideOutsideOfPackage("fuelprint.science.truth..")
                .should().haveRawReturnType(com.tngtech.archunit.base.DescribedPredicate.not(
                        com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage("fuelprint.science.truth..")))
                .allowEmptyShould(true).check(main);
    }

    @Test
    void noPublicFieldOutsideTruthHoldsATruthType() {
        noFields().that().arePublic().and().areDeclaredInClassesThat().resideOutsideOfPackage("fuelprint.science.truth..")
                .should().haveRawType(com.tngtech.archunit.core.domain.JavaClass.Predicates
                        .resideInAPackage("fuelprint.science.truth.."))
                .allowEmptyShould(true).check(main);
    }

    @Test
    void theApiPackageAndEveryOtherPackageNeverTouchTruth() {
        noClasses().that().resideOutsideOfPackage("fuelprint.science..")
                .should().dependOnClassesThat().resideInAPackage("fuelprint.science.truth..")
                .allowEmptyShould(true).check(main);
    }

    @Test
    void publicScienceTypesTakeNoTruthInTheirPublicConstructors() {
        noClasses().that().resideInAPackage("fuelprint.science..")
                .and().resideOutsideOfPackage("fuelprint.science.truth..")
                .and().resideOutsideOfPackage("fuelprint.science.scenario..")
                .and().resideOutsideOfPackage("fuelprint.science.world..")
                .should().dependOnClassesThat().resideInAPackage("fuelprint.science.truth..")
                .allowEmptyShould(true).check(main);
    }
}
