package com.example.cleanupjob.diag;

import jakarta.inject.Inject;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two layers of coverage:
 *
 * <ol>
 *   <li>End-to-end ({@link #dumpsSpringBeansToCodegraphDir}): boots the full
 *       Spring context so the {@link BeanGraphDumper} listener fires, then
 *       asserts the on-disk dump is plausible.</li>
 *   <li>Unit tests for every package-private helper. {@code BeanGraphDumper}
 *       exposes its reflection helpers at package scope on purpose — they're
 *       pure functions of {@code Class<?>} / {@code Field} / {@code Constructor}
 *       and don't need a Spring context to verify. This is what gets the
 *       new-code coverage over the 80% Sonar way threshold.</li>
 * </ol>
 *
 * <p>The production app is a CronJob that runs to completion and exits; we
 * need a repeatable way to materialize the dump during development. The
 * SpringBoot test below is the trigger.
 */
class BeanGraphDumperTest {

    // ---- Test fixtures: classes whose declared members let us exercise each
    // branch of the reflection helpers.

    /** Parent class — fields declared here must be visible to {@code allFields}. */
    static class Parent {
        @Autowired String parentAutowired;
        String parentPlain;
    }

    /** Single declared constructor (Spring 4.3+ auto-injection). */
    static class SingleCtor {
        SingleCtor(String x) {}
    }

    /** Multiple constructors, one explicitly marked {@code @Autowired}. */
    static class MultiCtorAutowired {
        // No-op ctors exist only so the class has two declared
        // constructors — BeanGraphDumper's isInjectableConstructor() test
        // needs to see "many ctors" + exactly one @Autowired. The bodies
        // are never invoked; reflection just inspects the annotations.
        MultiCtorAutowired() {} // NOSONAR
        @Autowired
        MultiCtorAutowired(String x) {} // NOSONAR
    }

    /** Multiple constructors, none annotated. */
    static class MultiCtorPlain {
        // Same rationale as MultiCtorAutowired above — both ctors are
        // required by the reflection test, neither is ever called.
        MultiCtorPlain() {} // NOSONAR
        MultiCtorPlain(String x) {} // NOSONAR
    }

    static class WithAutowiredField { @Autowired String a; }
    static class WithResourceField { @Resource String r; }
    static class WithJakartaInjectField { @Inject String j; }
    static class WithPlainField { String p; }

    // ---- Reflection helpers (package-private to BeanGraphDumper, accessible here)

    private static Field fieldOf(Class<?> c, String name) throws NoSuchFieldException {
        return c.getDeclaredField(name);
    }

    // ===== Unit: isInjection =====

    @Test
    void isInjection_detectsAutowired() throws Exception {
        assertThat(BeanGraphDumper.isInjection(fieldOf(WithAutowiredField.class, "a"))).isTrue();
    }

    @Test
    void isInjection_detectsResource() throws Exception {
        assertThat(BeanGraphDumper.isInjection(fieldOf(WithResourceField.class, "r"))).isTrue();
    }

    @Test
    void isInjection_detectsJakartaInject() throws Exception {
        assertThat(BeanGraphDumper.isInjection(fieldOf(WithJakartaInjectField.class, "j"))).isTrue();
    }

    @Test
    void isInjection_rejectsUnannotatedField() throws Exception {
        assertThat(BeanGraphDumper.isInjection(fieldOf(WithPlainField.class, "p"))).isFalse();
    }

    // ===== Unit: isInjectableConstructor =====

    @Test
    void isInjectableConstructor_acceptsSingleCtor() throws NoSuchMethodException {
        Constructor<SingleCtor> ctor = SingleCtor.class.getDeclaredConstructor(String.class);
        assertThat(BeanGraphDumper.isInjectableConstructor(ctor)).isTrue();
    }

    @Test
    void isInjectableConstructor_acceptsExplicitAutowiredAmongMany() throws NoSuchMethodException {
        Constructor<MultiCtorAutowired> autowired =
            MultiCtorAutowired.class.getDeclaredConstructor(String.class);
        Constructor<MultiCtorAutowired> noAutowired =
            MultiCtorAutowired.class.getDeclaredConstructor();
        assertThat(BeanGraphDumper.isInjectableConstructor(autowired)).isTrue();
        assertThat(BeanGraphDumper.isInjectableConstructor(noAutowired)).isFalse();
    }

    @Test
    void isInjectableConstructor_rejectsMultiCtorWithNoAnnotation() throws NoSuchMethodException {
        Constructor<MultiCtorPlain> ctor = MultiCtorPlain.class.getDeclaredConstructor(String.class);
        assertThat(BeanGraphDumper.isInjectableConstructor(ctor)).isFalse();
    }

    // ===== Unit: allFields =====

    @Test
    void allFields_includesOwnAndInheritedFields() {
        // Use a small subclass so we can assert both parent and own fields show up.
        class Child extends Parent {
            String childPlain;
        }
        List<Field> fields = BeanGraphDumper.allFields(Child.class);
        List<String> names = fields.stream().map(Field::getName).toList();
        assertThat(names).isNotEmpty()
            .contains("parentAutowired", "parentPlain", "childPlain");
    }

    @Test
    void allFields_stopsAtObject() {
        List<Field> fields = BeanGraphDumper.allFields(Parent.class);
        // Object's fields (none) must not pollute the list.
        List<String> declaringClasses = fields.stream()
            .map(Field::getDeclaringClass)
            .map(Class::getName)
            .toList();
        assertThat(declaringClasses).isNotEmpty()
            .doesNotContain(Object.class.getName());
    }

    // ===== Unit: springVersion =====

    @Test
    void springVersion_returnsNonBlankValue() {
        String v = BeanGraphDumper.springVersion();
        assertThat(v).isNotBlank();
    }

    // ===== Unit: toJson =====

    @Test
    void toJson_writesNullLiteral() {
        assertThat(BeanGraphDumper.toJson(null)).isEqualTo("null");
    }

    @Test
    void toJson_writesScalarTypes() {
        assertThat(BeanGraphDumper.toJson(42)).isEqualTo("42");
        assertThat(BeanGraphDumper.toJson(3.14)).isEqualTo("3.14");
        assertThat(BeanGraphDumper.toJson(true)).isEqualTo("true");
        assertThat(BeanGraphDumper.toJson("hi")).isEqualTo("\"hi\"");
    }

    @Test
    void toJson_writesEmptyCollections() {
        assertThat(BeanGraphDumper.toJson(java.util.Collections.emptyMap())).isEqualTo("{}");
        assertThat(BeanGraphDumper.toJson(java.util.Collections.emptyList())).isEqualTo("[]");
    }

    @Test
    void toJson_writesNestedMapAndList() {
        // LinkedHashMap preserves insertion order so the assertion is stable.
        Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("k", "v");
        Map<String, Object> outer = new java.util.LinkedHashMap<>();
        outer.put("name", "x");
        outer.put("nums", Arrays.asList(1, 2, 3));
        outer.put("inner", inner);

        String json = BeanGraphDumper.toJson(outer);
        // Sanity-check the pieces — exact whitespace is not part of the contract.
        // Chained `contains` so a single AssertJ chain walks the whole substring
        // set; otherwise the test reads as six unrelated micro-asserts.
        assertThat(json)
            .contains("\"name\": \"x\"")
            .contains("\"nums\":")
            .contains("1")
            .contains("3")
            .contains("\"inner\":")
            .contains("\"k\": \"v\"")
            .startsWith("{")
            .endsWith("}");
        // At least one comma between three top-level entries.
        assertThat(json.split("\\R")).anyMatch(l -> l.endsWith(","));
    }

    @Test
    void toJson_escapesSpecialCharactersInStrings() {
        // tab + newline + double-quote + backslash
        String json = BeanGraphDumper.toJson("a\tb\nc\"d\\e");
        assertThat(json).isEqualTo("\"a\\tb\\nc\\\"d\\\\e\"");
    }

    // ===== End-to-end: the original SpringBoot test that boots the context.

    @SpringBootTest(classes = com.example.cleanupjob.CleanupJobApplication.class)
    @ActiveProfiles("test")
    @TestPropertySource(properties = "spring.batch.job.enabled=false")
    static class DumpFileWiringTest {
        @Test
        void dumpsSpringBeansToCodegraphDir() throws Exception {
            Path out = Paths.get(".codegraph", "spring-beans.json");
            // BeanGraphDumper fires on ContextRefreshedEvent; @SpringBootTest guarantees
            // the listener has run by the time this method body executes.
            assertThat(Files.exists(out)).as("BeanGraphDumper should have written " + out).isTrue();
            long size = Files.size(out);
            assertThat(size)
                .as("spring-beans.json should be at least 1KB; %d bytes is too small", size)
                .isGreaterThan(1024);
            System.out.println("[BeanGraphDumperTest] " + out.toAbsolutePath() + " = " + size + " bytes");
        }
    }
}
