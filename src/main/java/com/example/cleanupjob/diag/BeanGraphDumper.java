package com.example.cleanupjob.diag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Dumps the live Spring container's bean wiring to a JSON file the codegraph
 * import script can ingest. One node per bean, one edge per @Autowired field.
 *
 * <p>Output: {@code .codegraph/spring-beans.json} in the working directory.
 *
 * <p>Why this exists: codegraph's static analysis tracks source-level call
 * edges, but Spring's container is metadata, not code. Without this dump,
 * {@code codegraph_explore} cannot answer "who depends on which bean?" — that
 * gap is exactly what makes the @Bean → @Bean graph invisible to tree-sitter.
 *
 * <p>Limitations:
 * <ul>
 *   <li>No source line numbers (reflection strips them); relations are at the
 *       bean level, not the call-site level.</li>
 *   <li>Constructor injection is detected for the canonical
 *       single-constructor / Lombok-{@code @RequiredArgsConstructor} case
 *       (every parameter is assumed injected). When a constructor is
 *       explicitly annotated {@code @Autowired} we also record it.</li>
 *   <li>Only {@code @Autowired}, {@code @Resource}, {@code @Inject} are scanned.
 *       {@code @Inject} is FQN-matched so it works whether the project uses
 *       {@code javax.inject} or {@code jakarta.inject} without a compile dep.</li>
 *   <li>Manual {@code @Bean} method parameter injection is not.</li>
 * </ul>
 */
@Component
public class BeanGraphDumper {

    private static final Logger log = LoggerFactory.getLogger(BeanGraphDumper.class);
    private static final String SITE_FIELD = "field";
    private static final String SITE_CONSTRUCTOR = "constructor";

    private final ApplicationContext ctx;
    private boolean dumped = false;

    public BeanGraphDumper(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextReady() throws IOException {
        if (dumped) return;
        dumped = true;

        List<Map<String, Object>> beanList = new ArrayList<>();
        List<Map<String, Object>> injList = new ArrayList<>();
        for (String name : new TreeSet<>(Arrays.asList(ctx.getBeanDefinitionNames()))) {
            try {
                Object bean = ctx.getBean(name);
                beanList.add(beanRecord(name, bean));
                injList.addAll(injectionRecords(name, bean));
            } catch (Exception ignored) {
                // Skip beans that fail to instantiate; they would block the
                // entire dump.
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generator", "BeanGraphDumper");
        out.put("spring_version", springVersion());
        out.put("bean_count", beanList.size());
        out.put("injection_count", injList.size());
        out.put("beans", beanList);
        out.put("injections", injList);

        Path outPath = Paths.get(".codegraph", "spring-beans.json");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, toJson(out).getBytes(StandardCharsets.UTF_8));
        log.info("wrote {} (beans={}, injections={})",
            outPath.toAbsolutePath(), beanList.size(), injList.size());
    }

    private Map<String, Object> beanRecord(String name, Object bean) {
        Class<?> type = ctx.getType(name);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("type", type != null ? type.getName() : bean.getClass().getName());
        b.put("class", bean.getClass().getName());
        return b;
    }

    private List<Map<String, Object>> injectionRecords(String name, Object bean) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Field f : allFields(bean.getClass())) {
            if (isInjection(f)) {
                out.add(injection(name, f.getType().getName(), f.getName(), SITE_FIELD));
            }
        }
        for (Constructor<?> ctor : bean.getClass().getDeclaredConstructors()) {
            if (!isInjectableConstructor(ctor)) continue;
            for (Parameter p : ctor.getParameters()) {
                out.add(injection(name, p.getType().getName(), p.getName(), SITE_CONSTRUCTOR));
            }
        }
        return out;
    }

    private static Map<String, Object> injection(String from, String toClass, String field, String site) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to_class", toClass);
        m.put(SITE_FIELD, field);
        m.put("site", site);
        return m;
    }

    static boolean isInjection(Field f) {
        if (f.isAnnotationPresent(Autowired.class)) return true;
        if (f.isAnnotationPresent(Resource.class)) return true;
        return Stream.of(f.getAnnotations())
            .map(a -> a.annotationType().getName())
            .anyMatch(n -> n.equals("javax.inject.Inject") || n.equals("jakarta.inject.Inject"));
    }

    static boolean isInjectableConstructor(Constructor<?> ctor) {
        return ctor.getDeclaringClass().getDeclaredConstructors().length == 1
            || ctor.isAnnotationPresent(Autowired.class);
    }

    static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            out.addAll(Arrays.asList(cur.getDeclaredFields()));
            cur = cur.getSuperclass();
        }
        return out;
    }

    static String springVersion() {
        try {
            return org.springframework.core.SpringVersion.getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, o, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object o, int indent) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            writeMap(sb, (Map<String, Object>) o, indent);
        } else if (o instanceof List) {
            writeList(sb, (List<Object>) o, indent);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            sb.append('"').append(esc(String.valueOf(o))).append('"');
        }
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> m, int indent) {
        String pad = "  ".repeat(indent);
        String pad1 = "  ".repeat(indent + 1);
        if (m.isEmpty()) { sb.append("{}"); return; }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            sb.append(pad1).append('"').append(esc(e.getKey())).append("\": ");
            writeJson(sb, e.getValue(), indent + 1);
            if (++i < m.size()) sb.append(',');
            sb.append('\n');
        }
        sb.append(pad).append('}');
    }

    private static void writeList(StringBuilder sb, List<Object> l, int indent) {
        String pad = "  ".repeat(indent);
        String pad1 = "  ".repeat(indent + 1);
        if (l.isEmpty()) { sb.append("[]"); return; }
        sb.append("[\n");
        for (int i = 0; i < l.size(); i++) {
            sb.append(pad1);
            writeJson(sb, l.get(i), indent + 1);
            if (i < l.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(pad).append(']');
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
