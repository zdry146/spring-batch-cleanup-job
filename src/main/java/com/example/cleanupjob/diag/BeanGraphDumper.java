package com.example.cleanupjob.diag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

    private final ApplicationContext ctx;
    private boolean dumped = false;

    public BeanGraphDumper(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onContextReady() throws Exception {
        if (dumped) return;
        dumped = true;

        TreeSet<String> names = new TreeSet<>();
        for (String n : ctx.getBeanDefinitionNames()) names.add(n);

        List<Map<String, Object>> beanList = new ArrayList<>();
        List<Map<String, Object>> injList = new ArrayList<>();

        for (String name : names) {
            try {
                Object bean = ctx.getBean(name);
                Class<?> type = ctx.getType(name);
                String typeName = type != null ? type.getName() : bean.getClass().getName();

                Map<String, Object> b = new LinkedHashMap<>();
                b.put("name", name);
                b.put("type", typeName);
                b.put("class", bean.getClass().getName());
                beanList.add(b);

                for (Field f : allFields(bean.getClass())) {
                    if (isInjection(f)) {
                        Map<String, Object> inj = new LinkedHashMap<>();
                        inj.put("from", name);
                        inj.put("to_class", f.getType().getName());
                        inj.put("field", f.getName());
                        inj.put("site", "field");
                        injList.add(inj);
                    }
                }
                // Constructor injection: the canonical Spring 4.3+ / Lombok
                // case is a single non-default constructor whose every
                // parameter is treated as a dependency. An explicit
                // @Autowired on the constructor is also honored.
                for (java.lang.reflect.Constructor<?> ctor : bean.getClass().getDeclaredConstructors()) {
                    if (!isInjectableConstructor(ctor)) continue;
                    for (java.lang.reflect.Parameter p : ctor.getParameters()) {
                        Map<String, Object> inj = new LinkedHashMap<>();
                        inj.put("from", name);
                        inj.put("to_class", p.getType().getName());
                        inj.put("field", p.getName());
                        inj.put("site", "constructor");
                        injList.add(inj);
                    }
                }
            } catch (Exception e) {
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
        String json = toJson(out);
        Files.write(outPath, json.getBytes("UTF-8"));

        System.out.println("[BeanGraphDumper] wrote " + outPath.toAbsolutePath()
            + " (beans=" + beanList.size() + ", injections=" + injList.size() + ")");
    }

    private static boolean isInjection(Field f) {
        if (f.isAnnotationPresent(Autowired.class)) return true;
        if (f.isAnnotationPresent(Resource.class)) return true;
        // FQN match @Inject to avoid forcing javax.inject or jakarta.inject on
        // the classpath at compile time.
        for (java.lang.annotation.Annotation a : f.getAnnotations()) {
            String name = a.annotationType().getName();
            if (name.equals("javax.inject.Inject") || name.equals("jakarta.inject.Inject")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInjectableConstructor(java.lang.reflect.Constructor<?> ctor) {
        // Canonical: a single declared constructor. With Spring 4.3+ a single
        // non-default constructor is auto-injected.
        java.lang.reflect.Constructor<?>[] all = ctor.getDeclaringClass().getDeclaredConstructors();
        if (all.length == 1) return true;
        // Explicit @Autowired on the constructor itself (rare, but supported).
        if (ctor.isAnnotationPresent(Autowired.class)) return true;
        return false;
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) out.add(f);
            c = c.getSuperclass();
        }
        return out;
    }

    private static String springVersion() {
        try { return org.springframework.core.SpringVersion.getVersion(); }
        catch (Throwable t) { return "unknown"; }
    }

    /** Minimal hand-rolled JSON writer so we don't pull in Jackson. */
    private static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o, int indent) {
        String pad = "  ".repeat(indent);
        String pad1 = "  ".repeat(indent + 1);
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                sb.append(pad1).append('"').append(esc(e.getKey())).append("\": ");
                write(sb, e.getValue(), indent + 1);
                if (++i < m.size()) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad).append('}');
        } else if (o instanceof List) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                sb.append(pad1);
                write(sb, l.get(i), indent + 1);
                if (i < l.size() - 1) sb.append(',');
                sb.append('\n');
            }
            sb.append(pad).append(']');
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else {
            sb.append('"').append(esc(String.valueOf(o))).append('"');
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                 .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
