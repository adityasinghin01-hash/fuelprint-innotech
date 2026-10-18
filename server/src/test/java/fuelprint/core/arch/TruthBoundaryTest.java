package fuelprint.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test 13: AGREEMENT ground rule 3. No class outside {@code fuelprint.science..} may depend on
 * {@code fuelprint.science.truth..}. Main and test classes of every package are checked.
 */
class TruthBoundaryTest {

    static final ArchRule TRUTH_BOUNDARY = noClasses()
            .that().resideOutsideOfPackage("fuelprint.science..")
            .should().dependOnClassesThat().resideInAPackage("fuelprint.science.truth..")
            .as("No class outside fuelprint.science may use fuelprint.science.truth (hidden truth)")
            .allowEmptyShould(true);

    @Test
    void noClassOutsideScienceUsesHiddenTruth() {
        JavaClasses all = new ClassFileImporter().importPackages("fuelprint");
        assertThat(all.size()).as("classes were imported").isGreaterThan(3);
        TRUTH_BOUNDARY.check(all);
    }

    @Test
    void theRuleReallyFailsOnAnOffendingClass(@TempDir Path dir) throws Exception {
        JavaClasses compiled = compile(dir, Map.of(
                "fuelprint/science/truth/Secret.java",
                "package fuelprint.science.truth; public class Secret { public static String source() { return \"S02\"; } }",
                "fuelprint/science/Allowed.java",
                "package fuelprint.science; public class Allowed { String s = fuelprint.science.truth.Secret.source(); }",
                "fuelprint/ops/Leak.java",
                "package fuelprint.ops; public class Leak { String s = fuelprint.science.truth.Secret.source(); }"));

        assertThatThrownBy(() -> TRUTH_BOUNDARY.check(compiled))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("fuelprint.ops.Leak");
        // The same truth class used from inside fuelprint.science is allowed.
        JavaClasses scienceOnly = compiled.that(com.tngtech.archunit.base.DescribedPredicate.describe(
                "not ops", c -> !c.getPackageName().equals("fuelprint.ops")));
        assertThatCode(() -> TRUTH_BOUNDARY.check(scienceOnly)).doesNotThrowAnyException();
    }

    /** Compiles small Java sources into {@code dir} with the JDK compiler and imports the classes. */
    private static JavaClasses compile(Path dir, Map<String, String> sources) throws Exception {
        Path src = dir.resolve("src");
        Path out = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString(), "--release", "17"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK compiler is available").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new))).isZero();
        return new ClassFileImporter().importPath(out);
    }
}
