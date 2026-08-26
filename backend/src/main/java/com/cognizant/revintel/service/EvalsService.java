package com.cognizant.revintel.service;

import com.cognizant.revintel.dto.EvalsPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Serves {@code GET /api/evals} by reading the artefacts the three eval layers leave behind.
 *
 * <p><b>Design choice, and its tradeoff.</b> The spec allowed either running the JUnit-backed
 * checks live through an in-process runner or pre-computing them at build time and reading a
 * summary. This takes the second option:
 *
 * <ul>
 *   <li>data-layer results come from {@code data-tools/output/data_eval_results.json}, written by
 *       {@code build_dataset.py};</li>
 *   <li>pipeline- and narrative-layer results come from the Surefire XML the last {@code mvn test}
 *       run produced, split by test package.</li>
 * </ul>
 *
 * <p><b>The cost:</b> this endpoint reports the last recorded run, not a live one. If you change
 * the code and reload the page without re-running the build, the numbers are stale -- so the
 * payload always reports when each layer was produced. The upside is no test-harness-in-production
 * machinery and no risk of an eval endpoint executing tests against the live datasource.
 *
 * <p>Layers that have never been run report {@code not_run} rather than passing vacuously.
 */
@Service
public class EvalsService {

    private static final Logger log = LoggerFactory.getLogger(EvalsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Path> dataReportCandidates;
    private final List<Path> surefireCandidates;

    public EvalsService(@Value("${revintel.evals.data-report:}") String configuredDataReport,
                        @Value("${revintel.evals.surefire-dir:}") String configuredSurefireDir) {
        // The app can be launched from the repo root or from backend/, so try both. Explicit
        // configuration wins.
        this.dataReportCandidates = candidates(configuredDataReport,
                "data-tools/output/data_eval_results.json",
                "../data-tools/output/data_eval_results.json");
        this.surefireCandidates = candidates(configuredSurefireDir,
                "target/surefire-reports",
                "backend/target/surefire-reports");
    }

    private static List<Path> candidates(String configured, String... fallbacks) {
        List<Path> paths = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            paths.add(Path.of(configured.trim()));
        }
        for (String fallback : fallbacks) {
            paths.add(Path.of(fallback));
        }
        return List.copyOf(paths);
    }

    public EvalsPayload evals() {
        List<EvalsPayload.Layer> layers = new ArrayList<>();
        layers.add(dataLayer());
        layers.addAll(junitLayers());

        int total = layers.stream().mapToInt(EvalsPayload.Layer::total).sum();
        int passed = layers.stream().mapToInt(EvalsPayload.Layer::passed).sum();
        int failed = layers.stream().mapToInt(EvalsPayload.Layer::failed).sum();
        int warnings = layers.stream().mapToInt(EvalsPayload.Layer::warnings).sum();

        boolean anythingRun = layers.stream().anyMatch(layer -> layer.total() > 0);
        String status = !anythingRun ? "not_run"
                : failed > 0 ? "failing"
                : warnings > 0 ? "warnings"
                : "all_clear";

        return new EvalsPayload(status, failed == 0 && anythingRun, total, passed, failed, warnings, layers,
                "Data-layer results are written by data-tools/build_dataset.py; pipeline and "
                        + "narrative results come from the last `mvn test` run. This endpoint reports "
                        + "recorded runs, not live ones -- re-run the build to refresh.");
    }

    // ==================================================================================
    // Data layer -- Python
    // ==================================================================================

    private EvalsPayload.Layer dataLayer() {
        Optional<Path> found = dataReportCandidates.stream().filter(Files::isRegularFile).findFirst();
        if (found.isEmpty()) {
            return notRun("data", "Data integrity & plausibility", "Python",
                    "data-tools/output/data_eval_results.json",
                    "Not found. Run `python data-tools/build_dataset.py` to generate it.");
        }

        Path path = found.get();
        try {
            JsonNode report = MAPPER.readTree(path.toFile());
            List<EvalsPayload.Check> checks = new ArrayList<>();
            for (JsonNode check : report.path("checks")) {
                checks.add(new EvalsPayload.Check(
                        check.path("name").asText(),
                        check.path("severity").asText("error"),
                        check.path("passed").asBoolean(false),
                        check.path("message").asText("")));
            }

            int total = report.path("total").asInt(checks.size());
            int failed = report.path("errors").asInt(0);
            int warnings = report.path("warnings").asInt(0);
            int passed = report.path("passed").asInt(Math.max(0, total - failed - warnings));

            return new EvalsPayload.Layer("data", "Data integrity & plausibility", "Python",
                    path.toString(),
                    failed > 0 ? "failing" : warnings > 0 ? "warnings" : "all_clear",
                    total, passed, failed, warnings,
                    report.path("generated_at").asText(null),
                    String.format("%d/%d checks passed against %s rows of generated data.",
                            passed, total, totalRowCount(report)),
                    checks);
        } catch (Exception e) {
            log.warn("Could not read data eval report at {}: {}", path, e.toString());
            return notRun("data", "Data integrity & plausibility", "Python", path.toString(),
                    "Report present but unreadable: " + e.getMessage());
        }
    }

    private static String totalRowCount(JsonNode report) {
        long total = 0;
        for (JsonNode count : report.path("row_counts")) {
            total += count.asLong(0);
        }
        return String.valueOf(total);
    }

    // ==================================================================================
    // Pipeline and narrative layers -- JUnit
    // ==================================================================================

    private List<EvalsPayload.Layer> junitLayers() {
        Optional<Path> dir = surefireCandidates.stream().filter(Files::isDirectory).findFirst();
        if (dir.isEmpty()) {
            return List.of(
                    notRun("pipeline", "Pipeline consistency & plausibility", "Java (JUnit)",
                            "target/surefire-reports", "No test reports found. Run `mvn test`."),
                    notRun("narrative", "Groundedness & template fallback", "Java (JUnit)",
                            "target/surefire-reports", "No test reports found. Run `mvn test`."));
        }

        Tally pipeline = new Tally();
        Tally narrative = new Tally();
        String generatedAt = null;

        try (var stream = Files.list(dir.get())) {
            List<Path> reports = stream
                    .filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml"))
                    .sorted()
                    .toList();

            for (Path report : reports) {
                String className = report.getFileName().toString()
                        .replaceFirst("^TEST-", "").replaceFirst("\\.xml$", "");
                Tally target = className.contains(".narrative.") ? narrative
                        : className.contains(".pipeline.") ? pipeline
                        : null;
                if (target == null) {
                    // Boot/smoke tests are not eval-layer checks; don't inflate the counts.
                    continue;
                }
                readSuite(report.toFile(), className, target);
                generatedAt = newerOf(generatedAt, report.toFile());
            }
        } catch (Exception e) {
            log.warn("Could not read Surefire reports from {}: {}", dir.get(), e.toString());
        }

        return List.of(
                pipeline.toLayer("pipeline", "Pipeline consistency & plausibility", "Java (JUnit)",
                        dir.get().toString(), generatedAt,
                        "Aggregations sum, ratios stay in range, JSON stays serialisable, "
                                + "and what-if overrides move the metrics they should."),
                narrative.toLayer("narrative", "Groundedness & template fallback", "Java (JUnit)",
                        dir.get().toString(), generatedAt,
                        "Invented numbers are caught and the template path is exercised."));
    }

    private void readSuite(File file, String className, Tally tally) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // These files come from our own build, but parsing XML with external entities enabled is
        // never worth it.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(file);
        NodeList cases = document.getElementsByTagName("testcase");
        String simpleName = className.substring(className.lastIndexOf('.') + 1);

        for (int i = 0; i < cases.getLength(); i++) {
            Element testCase = (Element) cases.item(i);
            String name = testCase.getAttribute("name");
            boolean skipped = testCase.getElementsByTagName("skipped").getLength() > 0;
            boolean failed = testCase.getElementsByTagName("failure").getLength() > 0
                    || testCase.getElementsByTagName("error").getLength() > 0;

            String message = failed
                    ? firstMessage(testCase)
                    : skipped ? "Skipped." : "Passed.";
            tally.add(new EvalsPayload.Check(simpleName + "." + name,
                    skipped ? "warning" : "error", !failed && !skipped, message),
                    failed, skipped);
        }
    }

    private static String firstMessage(Element testCase) {
        for (String tag : List.of("failure", "error")) {
            NodeList nodes = testCase.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String message = ((Element) nodes.item(0)).getAttribute("message");
                return message.isBlank() ? "Failed." : message.lines().findFirst().orElse("Failed.");
            }
        }
        return "Failed.";
    }

    private static String newerOf(String current, File file) {
        String candidate = java.time.Instant.ofEpochMilli(file.lastModified()).toString();
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    private static EvalsPayload.Layer notRun(String layer, String label, String language,
                                            String source, String message) {
        return new EvalsPayload.Layer(layer, label, language, source, "not_run",
                0, 0, 0, 0, null, message, List.of());
    }

    /** Mutable accumulator for one JUnit-backed layer. */
    private static final class Tally {
        private final List<EvalsPayload.Check> checks = new ArrayList<>();
        private int failed;
        private int skipped;

        void add(EvalsPayload.Check check, boolean isFailure, boolean isSkipped) {
            checks.add(check);
            if (isFailure) {
                failed++;
            }
            if (isSkipped) {
                skipped++;
            }
        }

        EvalsPayload.Layer toLayer(String layer, String label, String language, String source,
                                  String generatedAt, String description) {
            if (checks.isEmpty()) {
                return notRun(layer, label, language, source, "No test reports found. Run `mvn test`.");
            }
            checks.sort(Comparator.comparing(EvalsPayload.Check::name));
            int total = checks.size();
            int passed = total - failed - skipped;
            return new EvalsPayload.Layer(layer, label, language, source,
                    failed > 0 ? "failing" : skipped > 0 ? "warnings" : "all_clear",
                    total, passed, failed, skipped, generatedAt,
                    String.format("%d/%d checks passed. %s", passed, total, description),
                    List.copyOf(checks));
        }
    }
}
