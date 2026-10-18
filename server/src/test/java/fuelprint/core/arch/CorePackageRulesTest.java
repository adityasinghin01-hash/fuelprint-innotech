package fuelprint.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CORE-API section 9: {@code fuelprint.core} never depends on the packages built on top of it. It sees their
 * beans only through {@code fuelprint.api} interfaces.
 */
class CorePackageRulesTest {

    static final String[] OTHER_PACKAGES = {
            "fuelprint.science..", "fuelprint.field..", "fuelprint.alerts..",
            "fuelprint.ops..", "fuelprint.cases..", "fuelprint.ai.."};

    static final ArchRule CORE_STANDS_ALONE = noClasses()
            .that().resideInAPackage("fuelprint.core..")
            .should().dependOnClassesThat().resideInAnyPackage(OTHER_PACKAGES)
            .as("fuelprint.core must not depend on science, field, alerts, ops, cases or ai (CORE-API section 9)")
            .allowEmptyShould(true);

    @Test
    void coreDependsOnNoOtherPackage() {
        // Main classes only: test helpers under fuelprint.core may exercise other packages later.
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("fuelprint");
        assertThat(main.stream().filter(c -> c.getPackageName().startsWith("fuelprint.core"))).isNotEmpty();
        CORE_STANDS_ALONE.check(main);
    }

    @Test
    void theRuleReallyFailsOnAnOffendingClass(@TempDir Path dir) throws Exception {
        for (String other : List.of("science", "field", "alerts", "ops", "cases", "ai")) {
            Path out = dir.resolve(other);
            JavaClasses compiled = compile(out, Map.of(
                    "fuelprint/" + other + "/Thing.java",
                    "package fuelprint." + other + "; public class Thing { public static int x() { return 1; } }",
                    "fuelprint/core/Bad.java",
                    "package fuelprint.core; public class Bad { int y = fuelprint." + other + ".Thing.x(); }"));
            assertThatThrownBy(() -> CORE_STANDS_ALONE.check(compiled))
                    .as(other).isInstanceOf(AssertionError.class).hasMessageContaining("fuelprint.core.Bad");
        }
    }

    private static JavaClasses compile(Path dir, Map<String, String> sources) throws Exception {
        Path src = dir.resolve("src");
        Path out = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).isZero();
        return new ClassFileImporter().importPath(out);
    }
}
