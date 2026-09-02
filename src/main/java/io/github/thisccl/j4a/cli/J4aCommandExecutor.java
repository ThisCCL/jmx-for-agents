package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.jmx.JmxInitialPlanFactory;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerClient;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public final class J4aCommandExecutor {
    private static final Set<String> RESERVED_APPLY_RECEIPT_FIELDS = new HashSet<String>(Arrays.asList(
            "command", "format", "output", "dryRun", "writtenTarget", "writeMode",
            "appliedCount", "createdRefs", "deletedRefs", "changeResults"));
    private static final Set<String> FORBIDDEN_APPLY_RECEIPT_FIELDS = new HashSet<String>(Arrays.asList(
            "tree", "root", "snapshot", "snapshotId", "fingerprint", "generation", "workerGeneration",
            "refIndex", "referenceIndex", "unchangedRefs", "copySourceDeletedRefs", "sourceDeletedRefs"));
    private final LocalJMeterWorkerClient workerClient;

    public J4aCommandExecutor() {
        this(new LocalJMeterWorkerClient());
    }

    public J4aCommandExecutor(LocalJMeterWorkerClient workerClient) {
        this.workerClient = workerClient;
    }

    public CommandResult execute(String[] args, Map<String, String> environment) {
        return execute(args, environment, false, null);
    }

    public CommandResult execute(String[] args, Map<String, String> environment, InputStream stdin) {
        return execute(args, environment, false, stdin);
    }

    public CommandResult execute(String[] args, Map<String, String> environment, boolean debug) {
        return execute(args, environment, debug, null);
    }

    private CommandResult execute(String[] args, Map<String, String> environment, boolean debug, InputStream stdin) {
        final String[] ownedArgs = args;
        final Map<String, String> ownedEnvironment = environment;
        final boolean ownedDebug = debug;
        final InputStream ownedStdin = stdin;
        return CliSupport.withWorkerClient(workerClient,
                () -> executeOwned(ownedArgs, ownedEnvironment, ownedDebug, ownedStdin));
    }

    private CommandResult executeOwned(String[] args, Map<String, String> environment, boolean debug, InputStream stdin) {
        if (args.length > 0 && "read".equals(args[0])) {
            return ReadCliCommand.execute(args, environment, debug);
        }
        if (args.length > 0 && capturedCommand(args[0])) {
            return executeCaptured(args, environment, stdin);
        }
        return DefaultCommandResult.failure(2, new CliError(
                "USAGE_ERROR",
                "usage",
                "Unsupported command for shared execution: " + (args.length == 0 ? "" : args[0]),
                null,
                null,
                null,
                null,
                null,
                "use one of the commands supported by the shared command executor.",
                null));
    }

    private static boolean capturedCommand(String command) {
        return "validate".equals(command)
                || "components".equals(command)
                || "categories".equals(command)
                || "apply".equals(command)
                || "init".equals(command)
                || "set".equals(command);
    }

    private static CommandResult executeCaptured(String[] args, Map<String, String> environment, InputStream stdin) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        synchronized (J4aCommandExecutor.class) {
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            InputStream originalIn = System.in;
            try {
                System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8.name()));
                System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8.name()));
                if (stdin != null) {
                    System.setIn(stdin);
                }
                exitCode = runCaptured(args, environment, stdin);
            } catch (java.io.UnsupportedEncodingException exception) {
                throw new IllegalStateException("UTF-8 output encoding is unavailable.", exception);
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
                System.setIn(originalIn);
            }
        }

        String output = new String(stdout.toByteArray(), StandardCharsets.UTF_8);
        String diagnostics = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
        if (exitCode == 0) {
            try {
                Map<String, Object> data = structuredData(args, output);
                String text = "apply".equals(args[0]) ? new Yaml().dump(data) : output;
                return DefaultCommandResult.success(text, data);
            } catch (IllegalArgumentException exception) {
                return invalidSuccessfulOutput(args[0], exception.getMessage());
            }
        }
        return capturedFailure(exitCode, args, diagnostics);
    }

    private static int runCaptured(String[] args, Map<String, String> environment, InputStream stdin) {
        if (args.length == 0) {
            return Main.run(args, environment);
        }
        if ("apply".equals(args[0])) {
            return ApplyCliCommand.run(args, environment, false, stdin == null ? System.in : stdin);
        }
        if ("init".equals(args[0])) {
            return InitCliCommand.run(args, environment, false);
        }
        if ("set".equals(args[0])) {
            return SetCliCommand.run(args, environment, false);
        }
        return Main.run(args, environment);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> structuredData(String[] args, String textOutput) {
        String command = args[0];
        if ("components".equals(command) || "categories".equals(command)) {
            Object parsed;
            try {
                parsed = new Yaml().load(textOutput);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(command + " output must be valid YAML.", exception);
            }
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException(command + " output must be a YAML mapping.");
            }
            return (Map<String, Object>) parsed;
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("command", command);
        data.put("format", format(command));
        String output = outputPath(args);
        if (output != null) {
            data.put("output", output);
        }
        if ("init".equals(command)) {
            data.put("createdPlan", createdPlan(args));
        }
        if ("set".equals(command)) {
            data.put("changedProperty", changedProperty(args));
        }
        if ("apply".equals(command)) {
            boolean dryRun = hasOption(args, "--dry-run");
            data.put("dryRun", Boolean.valueOf(dryRun));
            data.put("writtenTarget", dryRun ? null : output);
            Map<String, Object> receipt = applyReceipt(textOutput);
            validateApplyReceiptMode(receipt, dryRun);
            for (Map.Entry<String, Object> entry : receipt.entrySet()) {
                if (!RESERVED_APPLY_RECEIPT_FIELDS.contains(entry.getKey())) {
                    data.put(entry.getKey(), entry.getValue());
                }
            }
            data.put("writeMode", dryRun ? "dry-run" : hasOption(args, "--override") ? "in-place" : "copy");
            data.put("appliedCount", receipt.get("appliedCount"));
            data.put("createdRefs", receipt.get("createdRefs"));
            data.put("deletedRefs", receipt.get("deletedRefs"));
            data.put("changeResults", receipt.get("changeResults"));
        }
        return data;
    }

    @SuppressWarnings("unchecked")
    private static void validateApplyReceiptMode(Map<String, Object> receipt, boolean dryRun) {
        List<Object> created = (List<Object>) receipt.get("createdRefs");
        List<String> deleted = (List<String>) receipt.get("deletedRefs");
        List<Object> changes = (List<Object>) receipt.get("changeResults");
        if (dryRun && (!created.isEmpty() || !deleted.isEmpty())) {
            throw new IllegalArgumentException("dry-run apply receipt must not publish references");
        }
        Map<String, String> referencesByAlias = new LinkedHashMap<String, String>();
        for (Object item : created) {
            Map<String, String> row = (Map<String, String>) item;
            referencesByAlias.put(row.get("alias"), row.get("ref"));
        }
        Set<String> matchedAliases = new HashSet<String>();
        String expectedStatus = dryRun ? "validated" : "committed";
        for (Object item : changes) {
            Map<Object, Object> row = (Map<Object, Object>) item;
            if (!expectedStatus.equals(row.get("status"))) {
                throw new IllegalArgumentException("apply receipt changeResults status disagrees with write mode");
            }
            Object resultRef = row.get("resultRef");
            if (resultRef != null) {
                Map<?, ?> context = (Map<?, ?>) row.get("context");
                String alias = (String) context.get("alias");
                if (!resultRef.equals(referencesByAlias.get(alias))) {
                    throw new IllegalArgumentException(
                            "apply receipt changeResults resultRef disagrees with createdRefs");
                }
                matchedAliases.add(alias);
            }
        }
        if (!matchedAliases.equals(referencesByAlias.keySet())) {
            throw new IllegalArgumentException(
                    "apply receipt createdRefs must match committed aliased add results");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> applyReceipt(String textOutput) {
        boolean hasReceipt = textOutput.startsWith("appliedCount:")
                || textOutput.contains(System.lineSeparator() + "appliedCount:");
        Object parsed = hasReceipt ? new Yaml().load(textOutput) : null;
        Map<String, Object> output = parsed instanceof Map
                ? (Map<String, Object>) parsed : java.util.Collections.<String, Object>emptyMap();
        requireNoForbiddenIdentityFields(output);
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        Object appliedCount = output.get("appliedCount");
        Number number = appliedCount instanceof Number ? (Number) appliedCount : Integer.valueOf(0);
        long count = number.longValue();
        if (count < 0 || count > Integer.MAX_VALUE || number.doubleValue() != (double) count) {
            throw new IllegalArgumentException("apply receipt appliedCount must be a nonnegative integer");
        }
        receipt.put("appliedCount", Integer.valueOf((int) count));
        for (Map.Entry<String, Object> entry : output.entrySet()) {
            if (!RESERVED_APPLY_RECEIPT_FIELDS.contains(entry.getKey())) {
                receipt.put(entry.getKey(), entry.getValue());
            }
        }
        Object createdRefs = output.get("createdRefs");
        receipt.put("createdRefs", identityRows(createdRefs));
        Object deletedRefs = output.get("deletedRefs");
        receipt.put("deletedRefs", identityStrings(deletedRefs));
        receipt.put("changeResults", changeRows(output.get("changeResults"), (int) count));
        return receipt;
    }

    private static List<Object> changeRows(Object value, int appliedCount) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("apply receipt changeResults must be a list");
        }
        List<?> source = (List<?>) value;
        if (source.size() != appliedCount) {
            throw new IllegalArgumentException("apply receipt changeResults length must equal appliedCount");
        }
        List<Object> rows = new java.util.ArrayList<Object>(source.size());
        for (int index = 0; index < source.size(); index++) {
            Object item = source.get(index);
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("apply receipt changeResults rows must be mappings");
            }
            Map<?, ?> row = (Map<?, ?>) item;
            if (!Integer.valueOf(index).equals(row.get("index"))
                    || !(row.get("operation") instanceof String)
                    || !(row.get("status") instanceof String)
                    || !(row.get("context") instanceof Map<?, ?>)) {
                throw new IllegalArgumentException(
                        "apply receipt changeResults rows require consecutive index, operation, status, and context");
            }
            validateChangeRow(row);
            rows.add(new LinkedHashMap<Object, Object>(row));
        }
        return rows;
    }

    private static void validateChangeRow(Map<?, ?> row) {
        String operation = (String) row.get("operation");
        String status = (String) row.get("status");
        if (!("validated".equals(status) || "committed".equals(status))) {
            throw new IllegalArgumentException("apply receipt changeResults status is invalid");
        }
        Map<?, ?> context = (Map<?, ?>) row.get("context");
        Set<String> required = new HashSet<String>();
        Set<String> optional = new HashSet<String>();
        if ("set".equals(operation)) {
            required.addAll(Arrays.asList("ref", "properties"));
            optional.add("component");
            requireAddresses(context.get("properties"), true);
        } else if ("add".equals(operation)) {
            required.addAll(Arrays.asList("component", "parent", "properties"));
            optional.addAll(Arrays.asList("alias", "before", "after", "position"));
            requirePlacement(context);
            requireAddresses(context.get("properties"), true);
        } else if ("move".equals(operation)) {
            required.addAll(Arrays.asList("ref", "parent"));
            optional.addAll(Arrays.asList("component", "before", "after", "position"));
            requirePlacement(context);
        } else if ("delete".equals(operation)) {
            required.add("ref");
            optional.add("component");
        } else if ("append".equals(operation)) {
            required.addAll(Arrays.asList("ref", "property"));
            requireAddresses(context.get("property"), false);
        } else if ("insert".equals(operation) || "remove".equals(operation)) {
            required.addAll(Arrays.asList("ref", "property", "index"));
            requireAddresses(context.get("property"), false);
            requireIndex(context.get("index"));
        } else {
            throw new IllegalArgumentException("apply receipt changeResults operation is invalid");
        }
        for (String name : required) {
            if (!context.containsKey(name)) {
                throw new IllegalArgumentException("apply receipt changeResults context is missing " + name);
            }
        }
        Set<Object> allowed = new HashSet<Object>();
        allowed.addAll(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(context.keySet())) {
            throw new IllegalArgumentException("apply receipt changeResults context contains an unexpected field");
        }
        requireStringFields(context, "ref", "component", "parent", "alias", "before", "after", "position");
        Object resultRef = row.get("resultRef");
        Set<String> rowKeys = new HashSet<String>(Arrays.asList(
                "index", "operation", "status", "context"));
        if (resultRef != null) {
            if (!(resultRef instanceof String) || !"add".equals(operation)
                    || !"committed".equals(status) || !context.containsKey("alias")) {
                throw new IllegalArgumentException("apply receipt changeResults resultRef is invalid");
            }
            rowKeys.add("resultRef");
        }
        if (!rowKeys.equals(row.keySet())) {
            throw new IllegalArgumentException("apply receipt changeResults row contains an unexpected field");
        }
    }

    private static void requirePlacement(Map<?, ?> context) {
        int selectors = (context.containsKey("before") ? 1 : 0)
                + (context.containsKey("after") ? 1 : 0)
                + (context.containsKey("position") ? 1 : 0);
        if (selectors != 1) {
            throw new IllegalArgumentException("apply receipt changeResults context requires one placement field");
        }
    }

    private static void requireStringFields(Map<?, ?> context, String... names) {
        for (String name : names) {
            if (context.containsKey(name) && !(context.get(name) instanceof String)) {
                throw new IllegalArgumentException("apply receipt changeResults context " + name + " must be a string");
            }
        }
    }

    private static void requireAddresses(Object value, boolean listOfAddresses) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("apply receipt changeResults property address must be a list");
        }
        List<?> addresses = listOfAddresses ? (List<?>) value
                : java.util.Collections.singletonList(value);
        for (Object address : addresses) {
            if (!(address instanceof List<?>) || ((List<?>) address).isEmpty()) {
                throw new IllegalArgumentException("apply receipt changeResults property address must be non-empty");
            }
            for (Object segment : (List<?>) address) {
                if (!(segment instanceof String || segment instanceof Integer)) {
                    throw new IllegalArgumentException("apply receipt changeResults property segment is invalid");
                }
            }
        }
    }

    private static void requireIndex(Object value) {
        if (!(value instanceof Integer) || ((Integer) value).intValue() < 0) {
            throw new IllegalArgumentException("apply receipt changeResults context index is invalid");
        }
    }

    private static void requireNoForbiddenIdentityFields(Object value) {
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (FORBIDDEN_APPLY_RECEIPT_FIELDS.contains(key)) {
                    throw new IllegalArgumentException(
                            "apply receipt contains forbidden identity field: " + key);
                }
                requireNoForbiddenIdentityFields(entry.getValue());
            }
        } else if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                requireNoForbiddenIdentityFields(item);
            }
        }
    }

    private static List<Object> identityRows(Object value) {
        if (!(value instanceof List<?>)) {
            return java.util.Collections.emptyList();
        }
        List<Object> rows = new java.util.ArrayList<Object>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("apply receipt createdRefs rows must be mappings");
            }
            Map<?, ?> source = (Map<?, ?>) item;
            if (source.size() != 2 || !(source.get("alias") instanceof String)
                    || !(source.get("ref") instanceof String)) {
                throw new IllegalArgumentException("apply receipt createdRefs rows require exactly alias and ref");
            }
            Map<String, String> row = new LinkedHashMap<String, String>();
            row.put("alias", (String) source.get("alias"));
            row.put("ref", (String) source.get("ref"));
            rows.add(row);
        }
        return rows;
    }

    private static List<String> identityStrings(Object value) {
        if (!(value instanceof List<?>)) {
            return java.util.Collections.emptyList();
        }
        List<String> refs = new java.util.ArrayList<String>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("apply receipt deletedRefs entries must be strings");
            }
            refs.add((String) item);
        }
        return refs;
    }

    private static CommandResult invalidSuccessfulOutput(String command, String message) {
        return DefaultCommandResult.failure(4, new CliError(
                "INVALID_COMMAND_OUTPUT",
                "runtime",
                message,
                null,
                null,
                null,
                null,
                command,
                "retry the command with the same local JMeter home; if invalid output persists, rerun with --debug and inspect the local runtime.",
                null));
    }

    private static Map<String, Object> createdPlan(String[] args) {
        Map<String, Object> created = new LinkedHashMap<String, Object>();
        String testPlanName = optionValue(args, "--name");
        String threadGroupName = optionValue(args, "--thread-group-name");
        created.put("testPlanName", testPlanName == null
                ? JmxInitialPlanFactory.DEFAULT_TEST_PLAN_NAME : testPlanName);
        created.put("threadGroupName", threadGroupName == null
                ? JmxInitialPlanFactory.DEFAULT_THREAD_GROUP_NAME : threadGroupName);
        return created;
    }

    private static Map<String, Object> changedProperty(String[] args) {
        Map<String, Object> changed = new LinkedHashMap<String, Object>();
        changed.put("locator", optionValue(args, "--locator"));
        changed.put("property", optionValue(args, "--property"));
        return changed;
    }

    private static String format(String command) {
        if ("components".equals(command) || "categories".equals(command) || "init".equals(command)) {
            return "yaml";
        }
        return "validate".equals(command) || "apply".equals(command) || "set".equals(command) ? "text" : "yaml";
    }

    private static String outputPath(String[] args) {
        if (args.length == 0) {
            return null;
        }
        if ("init".equals(args[0]) && args.length > 1) {
            return args[1];
        }
        String out = optionValue(args, "--out");
        if (out != null) {
            return out;
        }
        if (hasOption(args, "--override") && args.length > 1) {
            return args[1];
        }
        return null;
    }

    private static CommandResult capturedFailure(int exitCode, String[] args, String diagnostics) {
        String command = args[0];
        ParsedCliError parsed = ParsedCliError.parse(diagnostics);
        return DefaultCommandResult.failure(exitCode, new CliError(
                parsed.code() == null ? "COMMAND_FAILED" : parsed.code(),
                parsed.category() == null ? commandCategory(command) : parsed.category(),
                parsed.message().trim().isEmpty() ? "Command failed: " + command : parsed.message(),
                parsed.affectedFile(),
                parsed.locator() == null ? optionValue(args, "--locator") : parsed.locator(),
                parsed.propertyPath() == null ? optionValue(args, "--property") : parsed.propertyPath(),
                parsed.unresolvedSegment(),
                parsed.component(),
                parsed.suggestedNextAction() == null ? recoveryGuidance(command) : parsed.suggestedNextAction(),
                null,
                parsed.failureDiagnostic()));
    }

    private static String commandCategory(String command) {
        if ("validate".equals(command)) {
            return "validation";
        }
        if ("components".equals(command) || "categories".equals(command)) {
            return "catalog";
        }
        return "usage";
    }

    private static String recoveryGuidance(String command) {
        if ("validate".equals(command)) {
            return "pass a readable .jmx file and configure a local JMeter home.";
        }
        if ("components".equals(command)) {
            return "rerun categories ls to list valid category ids, then retry components --category, or rerun components with a listed runtime FQCN component.";
        }
        if ("categories".equals(command)) {
            return "rerun categories ls with --jmeter-home or an environment-selected local JMeter home.";
        }
        if ("apply".equals(command)) {
            return "pass a valid patch and a safe output target, or use --dry-run to check the patch.";
        }
        if ("init".equals(command)) {
            return "pass a writable output .jmx path and --force-out only when replacing an existing file.";
        }
        if ("set".equals(command)) {
            return "rerun read to refresh locators and property paths, then retry set with a safe output target.";
        }
        return "rerun the command with supported options.";
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static String optionValue(String[] args, String option) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
    }

}
