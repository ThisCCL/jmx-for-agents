package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.AgentGuidance;

final class CliHelp {
    private static final String PATCH_FIELD_VOCABULARY = String.join(System.lineSeparator(),
            "Patch field vocabulary:",
            "  ref",
            "  component",
            "  property",
            "  value",
            "  type",
            "  parent",
            "  before",
            "  after",
            "  position");

    private static final String HELP_TEXT = String.join(System.lineSeparator(),
            "JMX Agent CLI",
            "",
            "Usage: j4a <command> [options]",
            "",
            "Commands:",
            "  read <file> [--depth <n>] [--ref <ref>] [--properties "
                    + AgentGuidance.cliReadPropertyModes() + "] [--include-disabled-details] [--jmeter-home <path>]",
            "  " + setCommand(),
            "  validate <file> [--jmeter-home <path>]",
            "  components [<component>|--component-token <opaque>] [--category <category> --details true [--limit <1..50>] [--max-bytes <4096..65536>] [--cursor <opaque>]] [--jmeter-home <path>] [--diagnostics true]",
            "  categories ls [--jmeter-home <path>]",
            "  apply <file> --patch <file|-> ((--out <file> [--force-out])|--override|--dry-run [--out <ignored-file>]) [--jmeter-home <path>]",
            "  init <out.jmx> [--force-out] [--jmeter-home <path>] [--name <test-plan-name>] [--thread-group-name <name>]",
            "",
            "Options:",
            "  --depth <n>",
            "  --ref <ref>",
            "  --properties " + AgentGuidance.cliReadPropertyModes(),
            "  --include-disabled-details",
            "  --patch <file|->",
            "  --locator <id>",
            "  --property <json-array>",
            "  --value <value>",
            "  --out <file>",
            "  --override",
            "  --force-out",
            "  --type " + setTypes(),
            "  --dry-run",
            "  --name <test-plan-name>",
            "  --thread-group-name <name>",
            "  --category <category>",
            "  --details true",
            "  --diagnostics true",
            "  --max-bytes <4096..65536>",
            "  --component-token <opaque>",
            "  --jmeter-home <path>",
            "  --debug");

    private static final String READ_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a read <file> [--depth <n>] [--ref <ref>] [--properties "
                    + AgentGuidance.cliReadPropertyModes() + "] [--include-disabled-details] [--jmeter-home <path>]",
            "",
            "Reads one JMeter .jmx file and prints agent-readable YAML.",
            "",
            "Use this first to discover stable refs, component ids, parent/child structure, and copyable property arrays.",
            "Start shallow, then focus by --ref before requesting properties on real or proprietary files.",
            "CLI property modes are exactly " + AgentGuidance.cliReadPropertyModes()
                    + "; writable is the reusable graph-capability subset of all.",
            "--properties all omits GUI/class-loader bookkeeping: TestElement\\.gui_class and TestElement\\.test_class.",
            "Property addresses are non-empty scalar arrays of strings and non-negative integer indexes.",
            "Example: property: [\"HTTPSampler.path\"]",
            "Canonical generic recursive collection: " + AgentGuidance.genericCollectionExample(),
            "Universal runtime-proven row list: " + AgentGuidance.semanticRowExample(),
            "Invalid collection-shape recovery: "
                    + AgentGuidance.recoveryTemplate("invalid_collection_shape"),
            "",
            PATCH_FIELD_VOCABULARY);

    private static final String SET_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a " + setCommand(),
            "",
            "Edits one existing property on one component, writes the JMX, then validates the written file.",
            "",
            "Use --out for a safe copy while exploring. Use --override only when in-place mutation is intentional.",
            "Copy the locator and property array from read output; do not invent XPath or raw XML paths.",
            "Pass that array as one JSON argument, for example: --property '[\"HTTPSampler.path\"]'.",
            "For collection, map, element, opaque, and null, --value is one safe YAML value fragment: the exact mapping below value: in read output.",
            "Existing scalar behavior is unchanged: scalar values remain literal; float is a numeric scalar.",
            "Structured object/list values require an explicit type copied from focused read.",
            "Universal rows use --type rows with either a bare row list or {row_type, rows}; row_type is an assertion, not class authorization.",
            "Canonical generic recursive collection: " + AgentGuidance.genericCollectionExample(),
            "Universal runtime-proven row list: " + AgentGuidance.semanticRowExample(),
            "Invalid collection-shape recovery: "
                    + AgentGuidance.recoveryTemplate("invalid_collection_shape"),
            "Same-path write recovery: " + AgentGuidance.recoveryTemplate("same_path_write"),
            "Existing-target recovery: " + AgentGuidance.recoveryTemplate("existing_target"),
            "");

    private static final String VALIDATE_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a validate <file> [--jmeter-home <path>]",
            "",
            "Validates one JMeter .jmx file by loading and traversing it with JMeter semantics.",
            "",
            "Validation does not run a load test, execute samplers, install plugins, or call remote services.",
            "Use --jmeter-home when overriding the environment-selected JMeter installation.");

    private static final String COMPONENTS_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a components [<component>|--component-token <opaque>] [--category <category> --details true [--limit <1..50>] [--max-bytes <4096..65536>] [--cursor <opaque>]] [--jmeter-home <path>] [--diagnostics true]",
            "",
            "Prints agent-readable YAML for component identities observed in one selected local JMeter runtime.",
            "",
            "Default list output includes category, component, and label fields.",
            "Copy stable lower-kebab category ids from categories ls into components --category.",
            "Selecting a component returns compact writable authoring metadata; --details true is a compatibility marker for the same result.",
            "Category details page in exact FQCN order; limit defaults to 20 (1..50) and max-bytes defaults to 16384 (4096..65536). Reuse only the opaque cursor returned for the same runtime/category/projection/limit/max-bytes.",
            "When a complete first detail cannot fit, copy its componentToken into the exclusive --component-token selector to recover exact ordinary untruncated detail.",
            "Ordinary property fields are property, type, and applicable default, value_shape, row_type, row_properties, and value_template.",
            "Property addresses are non-empty scalar arrays of strings and non-negative integer indexes.",
            "Example: property: [\"HTTPSampler.path\"]",
            "Use --diagnostics true for the full capability projection, including non-writable properties and runtime-observed fields.",
            "Diagnostics runtime_metadata_status is exactly runtime-proven or runtime-metadata-unavailable and reports metadata-source availability, not GUI completeness.",
            "A successful empty semantic observation is runtime-proven; ambiguous, inaccessible, or budget-limited observation fails softly as runtime-metadata-unavailable while known rows remain usable.",
            "JMeter 5.6.3 semantic evidence is lazy and cached once per component for the worker lifetime, including negative results; only worker replacement may observe again.",
            "Discovery may construct non-visible worker-local component objects, but commands never open top-level windows, run GUI-openability validation, execute samplers/load, or start the desktop GUI.",
            "Use exact runtime FQCN component ids from read/components output.",
            "For one release, mapped legacy category inputs remain compatibility aliases; output always uses stable category ids.",
            "Select the local home with --jmeter-home, JMX_AGENT_JMETER_HOME, or JMETER_HOME, in that order.",
            "",
            "Use this before authoring add patches. Catalog output is not a pasteable JMX node; JMeter decides creation and placement during apply.",
            "",
            PATCH_FIELD_VOCABULARY);

    private static final String CATEGORIES_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a categories ls [--jmeter-home <path>]",
            "",
            "Lists stable lower-kebab component category ids from one selected local JMeter runtime.",
            "",
            "Output fields: category, label, component_count.",
            "Copy a listed category id into components --category <category>.",
            "For one release, mapped legacy category inputs remain compatibility aliases; output always uses stable category ids.",
            "Select the local home with --jmeter-home, JMX_AGENT_JMETER_HOME, or JMETER_HOME, in that order.");

    private static final String APPLY_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a apply <file> --patch <file|-> ((--out <file> [--force-out])|--override|--dry-run [--out <ignored-file>]) [--jmeter-home <path>]",
            "",
            "Applies a strict YAML patch to one JMeter .jmx file.",
            "",
            "Dry-run before writing. Write to --out for a safe copy unless in-place mutation is intentional.",
            "Copy refs and property arrays from read; select exact runtime component ids with components.",
            "A complete property/type/value record from read can be copied unchanged into a set or add properties list.",
            "Runtime-proven row cards execute in declaration order against prior candidate state.",
            "A successful receipt returns one value-free changeResults row per card. Surviving aliased adds return refs proven against the reloaded target snapshot.",
            "Use returned created refs only against the exact unchanged target; run read for every other ref or after the target may have changed.",
            "append adds after the current final row; insert accepts indexes from zero through the current row count; remove requires an existing row index.",
            "Example append card: {append: {ref: <ref>, property: [\"HeaderManager.headers\"], row: {Header.name: X-Trace, Header.value: enabled}}}.",
            "Copy row_type and ordered row_properties descriptors from focused read; optional omissions use only emitted defaults.",
            "Author position, before, or after in the patch; local JMeter MenuFactory.canAddTo rejects illegal placement.",
            "Same-path write recovery: " + AgentGuidance.recoveryTemplate("same_path_write"),
            "Existing-target recovery: " + AgentGuidance.recoveryTemplate("existing_target"),
            "",
            PATCH_FIELD_VOCABULARY);

    private static final String INIT_HELP_TEXT = String.join(System.lineSeparator(),
            "Usage: j4a init <out.jmx> [--force-out] [--jmeter-home <path>] [--name <test-plan-name>] [--thread-group-name <name>]",
            "",
            "Creates a minimal valid JMeter plan from JMeter model objects and prints read-style YAML.",
            "",
            "Default Test Plan name: JMX Agent Test Plan",
            "Default Thread Group name: Thread Group",
            "Use --force-out only when replacing an existing output is intentional.");

    private CliHelp() {
    }

    private static String setCommand() {
        return "set <file> --locator <id> --property <json-array> --value <value> (--out <file>|--override) [--force-out] [--type "
                + setTypes() + "] [--jmeter-home <path>]";
    }

    private static String setTypes() {
        return "string|boolean|int|long|float|double|null|raw|collection|map|element|rows|opaque";
    }

    static void print() {
        System.out.println(HELP_TEXT);
    }

    static int printCommand(String command) {
        if ("read".equals(command)) {
            System.out.println(READ_HELP_TEXT);
            return 0;
        }
        if ("set".equals(command)) {
            System.out.println(SET_HELP_TEXT);
            return 0;
        }
        if ("validate".equals(command)) {
            System.out.println(VALIDATE_HELP_TEXT);
            return 0;
        }
        if ("components".equals(command)) {
            System.out.println(COMPONENTS_HELP_TEXT);
            return 0;
        }
        if ("categories".equals(command)) {
            System.out.println(CATEGORIES_HELP_TEXT);
            return 0;
        }
        if ("apply".equals(command)) {
            System.out.println(APPLY_HELP_TEXT);
            return 0;
        }
        if ("init".equals(command)) {
            System.out.println(INIT_HELP_TEXT);
            return 0;
        }
        print();
        return 2;
    }
}
