package com.bohutskyi.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regenerates the measured tables in README.md.
 *
 * <p>The point of this class is that a number should exist in exactly one place. Before it, the
 * README's results were transcribed by hand from terminal output, which meant every re-run on a
 * different machine either left the file stale or invited a copy-paste error. Now the prose —
 * which carries the reasoning and is the part worth writing by hand — stays put, and every table
 * between a {@code BEGIN GENERATED} / {@code END GENERATED} marker pair is replaced by whatever
 * the suite just measured.
 *
 * <p>Each command runs in its own JVM, for two reasons. {@code OrderGatewayLatencyTask} reads its
 * stage-probe flag into a {@code static final}, so {@code jlbh} and {@code stages} cannot share a
 * process; and JMH forks anyway. Running them exactly as a user would also means the report cannot
 * quietly measure something the documented commands do not.
 *
 * <p>Usage:
 * <pre>
 *   java -jar target/lab.jar report                 # full suite, ~20 minutes, rewrites README.md
 *   java -jar target/lab.jar report --quick         # reduced settings, for checking the plumbing
 *   java -jar target/lab.jar report --out other.md  # write somewhere else
 *   java -jar target/lab.jar report --only clock,co # regenerate a subset
 * </pre>
 *
 * <p>A {@code --quick} run stamps the generated header as such, because those numbers are not
 * worth quoting and should never be mistaken for a real measurement.
 */
public final class ReportGenerator {

    private static final String BEGIN = "<!-- BEGIN GENERATED: ";
    private static final String END = "<!-- END GENERATED: ";
    private static final String SUFFIX = " -->";

    /** What each AllocationBenchmark method is pretending to be. Editorial, so it lives here. */
    private static final Map<String, String> ALLOCATION_REGRESSIONS = new LinkedHashMap<>();

    static {
        ALLOCATION_REGRESSIONS.put("zeroAlloc", "none — this is the control");
        ALLOCATION_REGRESSIONS.put("allocatesRecord", "`new Order(...)` per message");
        ALLOCATION_REGRESSIONS.put("usesBigDecimal", "`new BigDecimal(...)`");
        ALLOCATION_REGRESSIONS.put("autoboxes", "a `Long` outside the cache");
        ALLOCATION_REGRESSIONS.put("buildsString", "`String.valueOf` + concat");
    }

    private final boolean quick;
    private final Path out;
    private final List<String> only;

    /** Idle-JVM worst case for this run, used to warn when the machine was too busy to trust. */
    private double floorMaxMs = Double.NaN;

    private ReportGenerator(boolean quick, Path out, List<String> only) {
        this.quick = quick;
        this.out = out;
        this.only = only;
    }

    public static void main(String[] args) throws Exception {
        boolean quick = false;
        Path out = Path.of("README.md");
        List<String> only = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--quick" -> quick = true;
                case "--out" -> out = Path.of(args[++i]);
                case "--only" -> {
                    for (String s : args[++i].split(",")) {
                        only.add(s.trim());
                    }
                }
                default -> {
                    System.out.println("Unknown report option: " + args[i]);
                    System.out.println("  --quick | --out <file> | --only <id,id,...>");
                    System.exit(2);
                }
            }
        }
        new ReportGenerator(quick, out, only).run();
    }

    private void run() throws Exception {
        if (!Files.exists(out)) {
            throw new IOException("No such file to update: " + out.toAbsolutePath());
        }

        System.out.println("  target file   " + out.toAbsolutePath());
        System.out.println("  mode          " + (quick ? "QUICK (numbers not worth quoting)" : "full suite"));
        if (!only.isEmpty()) {
            System.out.println("  only          " + String.join(", ", only));
        }
        System.out.println("  java          " + javaBinary());
        System.out.println("  jar           " + jarPath());
        System.out.println();
        if (!quick && only.isEmpty()) {
            System.out.println("  This runs the whole suite and takes roughly 20 minutes.");
            System.out.println("  Close what you can first — see the README on why that matters.");
            System.out.println();
        }

        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("env", environmentBlock());

        if (wanted("hiccup")) {
            String o = exec("hiccup", quick ? "10" : "60");
            String hiccupBlock = slice(o, "platform hiccup (idle JVM)", 2, l -> l.contains("max "));
            blocks.put("hiccup", fence(hiccupBlock));
            floorMaxMs = parseMillis(firstMatching(hiccupBlock, "max "));
        }
        if (wanted("clock")) {
            String o = exec("clock");
            blocks.put("clock", fence(grepAll(o,
                    "cost of one nanoTime() call",
                    "smallest non-zero delta",
                    "consecutive reads that were equal",
                    "cost of a stage probe")));
        }
        if (wanted("encoder")) {
            blocks.put("encoder", fence(jmhTable(exec(jmhArgs("OrderEncoderBenchmark")))));
        }
        if (wanted("allocation")) {
            blocks.put("allocation", allocationTable(jmhTable(exec(jmhArgs("AllocationBenchmark", "-prof", "gc")))));
        }
        if (wanted("deadcode")) {
            blocks.put("deadcode", fence(jmhTable(exec(jmhArgs("DeadCodeBenchmark")))));
        }
        if (wanted("clockcost")) {
            String pipeline = jmhTable(exec(jmhArgs("GatewayPipelineBenchmark")));
            String nanos = jmhTable(exec(jmhArgs("NanoTimeBenchmark")));
            blocks.put("clockcost", fence(pipeline + "\n\n" + nanos));
        }
        if (wanted("co")) {
            String o = exec(quick ? new String[]{"co", "100000", "4"} : new String[]{"co"});
            String table = slice(o, "what was measured", 0, l -> l.trim().startsWith("Samples above"));
            String rate = firstMatching(o, "achieved rate");
            blocks.put("co", fence(table + (rate.isEmpty() ? "" : "\n" + rate)));
        }
        if (wanted("sweep")) {
            String o = exec(quick
                    ? new String[]{"sweep", "-Dsweep.runs=1", "-Dsweep.secondsPerRun=1"}
                    : new String[]{"sweep"});
            blocks.put("sweep", fence(sweepTable(o)));
        }
        if (wanted("jlbh")) {
            blocks.put("jlbh", fence(jlbhSummary(exec(gatewayArgs("jlbh")))));
        }
        if (wanted("stages")) {
            blocks.put("stages", fence(jlbhSummary(exec(gatewayArgs("stages")))));
        }

        String text = Files.readString(out, StandardCharsets.UTF_8);
        int replaced = 0;
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            text = splice(text, e.getKey(), e.getValue());
            replaced++;
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("  wrote " + replaced + " generated block(s) into " + out);
        System.out.println("  Review the prose around them: this command updates numbers, not arguments.");

        if (!Double.isNaN(floorMaxMs) && floorMaxMs > 5.0) {
            System.out.println();
            System.out.printf(
                    "  WARNING: an idle JVM on this machine was %.1f ms late at worst.%n", floorMaxMs);
            System.out.println("  That is a busy machine, not a floor worth quoting. Everything above");
            System.out.println("  inherits that noise: close what you can and run this again before");
            System.out.println("  committing these numbers.");
        }
    }

    private boolean wanted(String id) {
        return only.isEmpty() || only.contains(id);
    }

    // ---------------------------------------------------------------- running

    private String[] jmhArgs(String benchmark, String... extra) {
        List<String> a = new ArrayList<>(List.of("jmh", benchmark));
        if (quick) {
            a.addAll(List.of("-f", "1", "-wi", "1", "-i", "1", "-r", "1s", "-w", "1s"));
        }
        a.addAll(List.of(extra));
        return a.toArray(new String[0]);
    }

    private String[] gatewayArgs(String command) {
        if (!quick) {
            return new String[]{command};
        }
        return new String[]{
                command,
                "-Dgateway.iterations=50000",
                "-Dgateway.warmup=20000",
                "-Dgateway.runs=2"};
    }

    /** Runs one lab.jar command in its own JVM, echoing output live and returning all of it. */
    private String exec(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Dstdout.encoding=UTF-8");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(jarPath());
        cmd.addAll(List.of(args));

        System.out.println("  ---- " + String.join(" ", args) + " ----");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();

        StringBuilder all = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                all.append(line).append('\n');
            }
        }
        int rc = p.waitFor();
        if (rc != 0) {
            throw new IOException("Command failed (" + rc + "): " + String.join(" ", args));
        }
        System.out.println("  ok");
        return all.toString();
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    }

    private static String jarPath() {
        String cp = System.getProperty("java.class.path");
        if (cp.contains(java.io.File.pathSeparator)) {
            throw new IllegalStateException(
                    "report must be run from the shaded jar (java -jar target/lab.jar report), "
                            + "so that each command can be launched the same way a reader would run it.");
        }
        return cp;
    }

    // ------------------------------------------------------------- extraction

    /** Lines from the one containing {@code anchor} (skipping {@code skip} after it) to {@code end}. */
    private static String slice(String text, String anchor, int skip, java.util.function.Predicate<String> end) {
        List<String> lines = text.lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(anchor)) {
                start = i + skip;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalStateException("Anchor not found in output: " + anchor);
        }
        List<String> outLines = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            outLines.add(lines.get(i));
            if (end.test(lines.get(i))) {
                break;
            }
        }
        return dedent(outLines);
    }

    private static String grepAll(String text, String... needles) {
        List<String> hits = new ArrayList<>();
        for (String needle : needles) {
            text.lines().filter(l -> l.contains(needle)).findFirst().ifPresent(hits::add);
        }
        if (hits.isEmpty()) {
            throw new IllegalStateException("None of the expected lines were found");
        }
        return dedent(hits);
    }

    private static String firstMatching(String text, String needle) {
        return text.lines().filter(l -> l.contains(needle)).findFirst().map(String::strip).orElse("");
    }

    /** The final JMH results table: the last "Benchmark ..." header and everything after it. */
    private static String jmhTable(String output) {
        List<String> lines = output.lines().toList();
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Benchmark") && lines.get(i).contains("Units")) {
                header = i;
            }
        }
        if (header < 0) {
            throw new IllegalStateException("No JMH results table in output");
        }
        List<String> table = new ArrayList<>();
        for (int i = header; i < lines.size(); i++) {
            if (lines.get(i).isBlank() && table.size() > 1) {
                break;
            }
            table.add(lines.get(i));
        }
        return String.join("\n", table).stripTrailing();
    }

    private static String sweepTable(String output) {
        List<String> lines = output.lines().toList();
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("rho") && lines.get(i).contains("target/s")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw new IllegalStateException("No sweep table in output");
        }
        List<String> table = new ArrayList<>();
        boolean seenData = false;
        for (int i = header; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean isRule = l.strip().startsWith("---");
            boolean isData = l.strip().matches("^[0-9].*");
            if (isData) {
                seenData = true;
            }
            // the rule directly under the header is part of the table; the next one closes it
            if (isRule && seenData) {
                break;
            }
            table.add(l);
        }
        return dedent(table);
    }

    /** All of JLBH's SUMMARY blocks plus the achieved-rate line the harness prints afterwards. */
    private static String jlbhSummary(String output) {
        List<String> lines = output.lines().toList();
        List<String> keep = new ArrayList<>();
        boolean in = false;
        for (String l : lines) {
            if (l.contains("SUMMARY (")) {
                in = true;
                keep.add(l.replaceAll("-{5,}", "").strip().isEmpty() ? l : summaryTitle(l));
                continue;
            }
            if (in) {
                if (l.startsWith("----------")) {
                    in = false;
                    keep.add("");
                    continue;
                }
                keep.add(l);
            }
        }
        String rate = firstMatching(output, "achieved arrival rate");
        if (!rate.isEmpty()) {
            keep.add(rate);
        }
        if (keep.isEmpty()) {
            throw new IllegalStateException("No JLBH SUMMARY block in output");
        }
        return String.join("\n", keep).strip();
    }

    private static String summaryTitle(String rawLine) {
        Matcher m = Pattern.compile("SUMMARY \\(([^)]+)\\)").matcher(rawLine);
        return m.find() ? "-- " + m.group(1) + " --" : rawLine;
    }

    // --------------------------------------------------------------- renderng

    private static String allocationTable(String jmhTable) {
        Map<String, String> nsPerOp = new LinkedHashMap<>();
        Map<String, String> bytes = new LinkedHashMap<>();

        for (String line : jmhTable.lines().toList()) {
            String t = line.strip();
            if (!t.startsWith("AllocationBenchmark.")) {
                continue;
            }
            String name = t.substring(0, t.indexOf(' ') < 0 ? t.length() : t.indexOf(' '));
            String value = scoreOf(t);
            if (name.endsWith(":gc.alloc.rate.norm")) {
                bytes.put(method(name), value);
            } else if (!name.contains(":")) {
                nsPerOp.put(method(name), value);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("| Benchmark | Regression | ns/op | `gc.alloc.rate.norm` |\n");
        sb.append("|---|---|---|---|\n");
        for (Map.Entry<String, String> e : ALLOCATION_REGRESSIONS.entrySet()) {
            String m = e.getKey();
            String b = bytes.getOrDefault(m, "—");
            String note = isEffectivelyZero(b) && !m.equals("zeroAlloc")
                    ? " — **scalar replaced**"
                    : "";
            sb.append("| `").append(m).append("` | ").append(e.getValue()).append(" | ")
                    .append(nsPerOp.getOrDefault(m, "—")).append(" | ")
                    .append(withUnit(b)).append(note).append(" |\n");
        }
        return sb.toString().stripTrailing();
    }

    /** JMH prints near-zero allocation as "≈ 10⁻⁵"; a tiny absolute figure means the same. */
    private static boolean isEffectivelyZero(String value) {
        if (value.startsWith("≈")) {
            return true;
        }
        try {
            return Double.parseDouble(value.split("\\s")[0]) < 1.0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String withUnit(String value) {
        return value.equals("—") || value.endsWith("B/op") ? value : value + " B/op";
    }

    private static String method(String jmhName) {
        String n = jmhName;
        int colon = n.indexOf(':');
        if (colon > 0) {
            n = n.substring(0, colon);
        }
        return n.substring(n.lastIndexOf('.') + 1);
    }

    /** Pulls "2.343 ± 0.044" (or JMH's "≈ 10⁻⁵") out of a result line. */
    private static String scoreOf(String line) {
        Matcher m = Pattern.compile("avgt\\s+(?:\\d+\\s+)?(≈\\s*\\S+|[\\d.]+\\s*±\\s*[\\d.]+|[\\d.]+)")
                .matcher(line);
        if (!m.find()) {
            return "—";
        }
        return m.group(1).replaceAll("\\s+", " ").strip();
    }

    /** Turns HiccupMeter's "max        22.2 ms" into milliseconds. */
    private static double parseMillis(String line) {
        Matcher m = Pattern.compile("([0-9.,]+)\s*(ns|us|ms|s)\b").matcher(line);
        if (!m.find()) {
            return Double.NaN;
        }
        double v = Double.parseDouble(m.group(1).replace(",", ""));
        return switch (m.group(2)) {
            case "ns" -> v / 1_000_000.0;
            case "us" -> v / 1_000.0;
            case "ms" -> v;
            default -> v * 1_000.0;
        };
    }

    private static String fence(String body) {
        return "```\n" + body.stripTrailing() + "\n```";
    }

    private static String dedent(List<String> lines) {
        int min = lines.stream()
                .filter(l -> !l.isBlank())
                .mapToInt(l -> l.length() - l.stripLeading().length())
                .min().orElse(0);
        return lines.stream()
                .map(l -> l.length() >= min ? l.substring(min) : l.strip())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("")
                .stripTrailing();
    }

    private String environmentBlock() {
        Runtime rt = Runtime.getRuntime();
        String gc = java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName)
                .reduce((a, b) -> a + ", " + b).orElse("unknown");
        return ("*Generated by `lab.jar report` on %s — %s, %d logical cores, %s %s, %s.%s*")
                .formatted(
                        LocalDate.now(),
                        System.getProperty("os.name") + " " + System.getProperty("os.version"),
                        rt.availableProcessors(),
                        System.getProperty("java.vm.name"),
                        System.getProperty("java.version"),
                        gc,
                        quick ? " **QUICK RUN — these numbers are not worth quoting.**" : "");
    }

    // ----------------------------------------------------------------- README

    private static String splice(String text, String id, String body) {
        String begin = BEGIN + id + SUFFIX;
        String end = END + id + SUFFIX;
        int i = text.indexOf(begin);
        int j = text.indexOf(end);
        if (i < 0 || j < 0) {
            throw new IllegalStateException(
                    "Missing marker pair for '" + id + "'. Expected " + begin + " ... " + end);
        }
        if (j < i) {
            throw new IllegalStateException("Markers out of order for '" + id + "'");
        }
        return text.substring(0, i + begin.length())
                + "\n" + body + "\n"
                + text.substring(j);
    }

}
