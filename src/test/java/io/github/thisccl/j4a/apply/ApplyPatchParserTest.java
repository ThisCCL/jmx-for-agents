package io.github.thisccl.j4a.apply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.thisccl.j4a.path.PropertyAddress;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApplyPatchParserTest {
    @Test
    void schemaFailureCarriesOnlyKnownValueFreeCardContext() {
        String patch = "changes:\n"
                + "  - add:\n"
                + "      as: child\n"
                + "      parent: jmx_parent\n"
                + "      component: org.example.Sampler\n"
                + "      properties:\n"
                + "        - property: [secret.property]\n"
                + "          type: string\n"
                + "          value: wrong-secret-value\n"
                + "      unexpected: rejected\n";

        org.assertj.core.api.ThrowableAssert.ThrowingCallable action = () -> new ApplyPatchParser().parse(patch);

        org.assertj.core.api.Assertions.assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApplyPatchParseException.class, failure -> {
                    assertThat(failure.changeIndex()).contains(0);
                    assertThat(failure.operation()).contains("add");
                    assertThat(failure.context().get().toMap())
                            .containsEntry("alias", "child")
                            .containsEntry("parent", "jmx_parent")
                            .containsEntry("component", "org.example.Sampler")
                            .containsKey("properties")
                            .doesNotContainKeys("value", "type", "before", "after", "position");
                    assertThat(failure.context().get().toMap().toString())
                            .doesNotContain("wrong-secret-value");
                });
    }
    private final ApplyPatchParser parser = new ApplyPatchParser();

    @Test
    void parsesNativeYamlPropertySegmentsWithoutReinterpretingTheirContents() {
        ApplyPatch patch = parser.parse("changes:\n"
                + "  - set:\n"
                + "      ref: thread-group-1\n"
                + "      properties:\n"
                + "        - property:\n"
                + "            - ThreadGroup.main_controller\n"
                + "            - LoopController.loops\n"
                + "          value: 7\n"
                + "          type: int\n");

        ApplyPatch.PropertyChange property = ((ApplyPatch.SetOperation)
                patch.changes().get(0).operation()).properties().get(0);
        assertThat(new java.util.ArrayList<Object>(property.property().segments())).containsExactly(
                "ThreadGroup.main_controller",
                "LoopController.loops");
    }

    @Test
    void parsesJsonArraySetPropertyThroughTheSameAddressDecoder() {
        assertThat(new java.util.ArrayList<Object>(parser.parsePropertyAddress(
                "[\"ThreadGroup.main_controller\",\"LoopController.loops\"]").segments()))
                .containsExactly(
                        "ThreadGroup.main_controller",
                        "LoopController.loops");
    }

    @Test
    void rejectsLegacyStringPropertyBeforeCompilingOrMutating() {
        assertInvalid("changes:\n"
                + "  - set:\n"
                + "      ref: http-request-1\n"
                + "      properties:\n"
                + "        - property: HTTPSampler\\.domain\n"
                + "          value: api.example.test\n"
                + "          type: string\n",
                "property address must be a non-empty scalar array");
    }

    @Test
    void rejectsEmptyMalformedAndUnknownPropertyRecordInputAtTheParserBoundary() {
        assertInvalid(propertyRecord("[]", "value"),
                "property address requires at least one segment");
        assertInvalid(propertyRecord("[true]", "value"),
                "segment[0] must be a string or integer");
        assertInvalid(propertyRecord("[2147483648]", "value"),
                "integer exceeds 2147483647");
        assertInvalid(propertyRecord("[{property: name}]", "value"),
                "segment[0] must be a string or integer");
        assertInvalid("changes:\n"
                + "  - set:\n"
                + "      ref: http-request-1\n"
                + "      properties:\n"
                + "        - property: [HTTPSampler.domain]\n"
                + "          value: value\n"
                + "          type: string\n"
                + "          injected: true\n",
                "unknown field 'injected'");
    }

    @Test
    void parsesValidPatchWithAllOperationCardsInOrder() {
        ApplyPatch patch = parser.parse("changes:\n  - set:\n      ref: http-request-1\n      component: http.request\n      properties:\n        - property: [HTTPSampler.domain]\n          value: api.example.test\n          type: string\n  - add:\n      parent: thread-group-1\n      after: http-request-1\n      component: http.request\n      properties:\n        - property: [TestElement.name]\n          value: GET /health\n          type: string\n        - property: [HTTPSampler.path]\n          value: /health\n          type: string\n  - move:\n      ref: response-assertion-1\n      component: response.assertion\n      parent: http-request-1\n      position: last\n  - delete:\n      ref: debug-sampler-1\n      component: debug.sampler\n");

        assertThat(patch.changes()).hasSize(4);
        assertThat(patch.changes()).extracting(change -> change.operation().name())
                .containsExactly("set", "add", "move", "delete");

        ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) patch.changes().get(0).operation();
        assertThat(set.ref()).isEqualTo("http-request-1");
        assertThat(set.component()).contains("http.request");
        assertThat(set.properties()).extracting(change -> change.property().segments())
                .containsExactly(java.util.Collections.<Object>singletonList("HTTPSampler.domain"));
        assertThat(set.properties()).extracting(ApplyPatch.PropertyChange::value)
                .containsExactly("api.example.test");
        assertThat(set.properties()).extracting(ApplyPatch.PropertyChange::type)
                .containsExactly(ApplyPatch.ValueType.STRING);

        ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) patch.changes().get(1).operation();
        assertThat(add.parent()).isEqualTo("thread-group-1");
        assertThat(add.after()).contains("http-request-1");
        assertThat(add.before()).isEmpty();
        assertThat(add.position()).isEmpty();
        assertThat(add.properties()).extracting(change -> change.property().segments())
                .containsExactly(
                        java.util.Collections.<Object>singletonList("TestElement.name"),
                        java.util.Collections.<Object>singletonList("HTTPSampler.path"));

        ApplyPatch.MoveOperation move = (ApplyPatch.MoveOperation) patch.changes().get(2).operation();
        assertThat(move.ref()).isEqualTo("response-assertion-1");
        assertThat(move.parent()).isEqualTo("http-request-1");
        assertThat(move.position()).contains("last");

        ApplyPatch.DeleteOperation delete = (ApplyPatch.DeleteOperation) patch.changes().get(3).operation();
        assertThat(delete.ref()).isEqualTo("debug-sampler-1");
        assertThat(delete.component()).contains("debug.sampler");
    }

    @Test
    void parsesStrictIncrementalRowCardsInDeclarationOrder() {
        ApplyPatch patch = parser.parse("changes:\n"
                + "  - append:\n"
                + "      ref: header-manager-ref\n"
                + "      property: [HeaderManager.headers]\n"
                + "      row: {Header.name: '$literal', Header.value: value}\n"
                + "  - insert:\n"
                + "      ref: header-manager-ref\n"
                + "      property: ['$literal-property']\n"
                + "      index: 0\n"
                + "      row: {Header.name: inserted, Header.value: '$literal'}\n"
                + "  - remove:\n"
                + "      ref: header-manager-ref\n"
                + "      property: [HeaderManager.headers]\n"
                + "      index: 1\n");

        assertThat(patch.changes()).extracting(change -> change.operation().name())
                .containsExactly("append", "insert", "remove");
        ApplyPatch.AppendOperation append =
                (ApplyPatch.AppendOperation) patch.changes().get(0).operation();
        assertThat(append.ref()).isEqualTo("header-manager-ref");
        assertThat(append.property().segments()).containsExactly("HeaderManager.headers");
        assertThat(append.row()).containsEntry("Header.name", "$literal")
                .containsEntry("Header.value", "value");
        assertThatThrownBy(() -> append.row().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        ApplyPatch.InsertOperation insert =
                (ApplyPatch.InsertOperation) patch.changes().get(1).operation();
        assertThat(insert.property().segments()).containsExactly("$literal-property");
        assertThat(insert.index()).isZero();
        assertThat(insert.row()).containsEntry("Header.value", "$literal");
        ApplyPatch.RemoveOperation remove =
                (ApplyPatch.RemoveOperation) patch.changes().get(2).operation();
        assertThat(remove.index()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedAndInapplicableIncrementalCardFields() {
        assertInvalid(incrementalCard("append",
                "      property: [HeaderManager.headers]\n"
                        + "      index: 0\n"
                        + "      row: {Header.name: value}\n"),
                "unknown field 'index'");
        assertInvalid(incrementalCard("insert",
                "      property: [HeaderManager.headers]\n"
                        + "      row: {Header.name: value}\n"),
                "field 'index' is required");
        assertInvalid(incrementalCard("insert",
                "      property: [HeaderManager.headers]\n"
                        + "      index: -1\n"
                        + "      row: {Header.name: value}\n"),
                "must be zero or greater");
        assertInvalid(incrementalCard("insert",
                "      property: [HeaderManager.headers]\n"
                        + "      index: 2147483648\n"
                        + "      row: {Header.name: value}\n"),
                "must be an integer from 0 through 2147483647");
        assertInvalid(incrementalCard("remove",
                "      property: [HeaderManager.headers]\n"
                        + "      index: 0\n"
                        + "      row: {Header.name: forbidden}\n"),
                "unknown field 'row'");
        assertInvalid(incrementalCard("append",
                "      property: $property_alias\n"
                        + "      row: {Header.name: value}\n"),
                "property address must be a non-empty scalar array");
        assertInvalid(incrementalCard("append",
                "      property: [HeaderManager.headers]\n"
                        + "      row: [not, a, mapping]\n"),
                "field 'row' must be a map");
    }

    @Test
    void parsesChainedAliasesOnlyInReferenceBearingFieldsAndPreservesLiteralDollarFields() {
        ApplyPatch patch = parser.parse("changes:\n"
                + "  - add:\n"
                + "      parent: root-ref\n"
                + "      position: $position-literal\n"
                + "      component: $component-literal\n"
                + "      as: root_alias\n"
                + "      properties:\n"
                + "        - property: [$property-literal]\n"
                + "          value: $value-literal\n"
                + "          type: string\n"
                + "  - add:\n"
                + "      parent: $root_alias\n"
                + "      before: $before_alias\n"
                + "      component: child.component\n"
                + "      as: before_alias\n"
                + "  - add:\n"
                + "      parent: $root_alias\n"
                + "      after: $after_alias\n"
                + "      component: child.component\n"
                + "      as: after_alias\n"
                + "  - set:\n"
                + "      ref: $set_alias\n"
                + "      properties:\n"
                + "        - property: [TestElement.name]\n"
                + "          value: $value-literal\n"
                + "          type: string\n"
                + "  - move:\n"
                + "      ref: $move_alias\n"
                + "      parent: $root_alias\n"
                + "      before: $before_alias\n"
                + "  - move:\n"
                + "      ref: $move_after_alias\n"
                + "      parent: $root_alias\n"
                + "      after: $after_alias\n"
                + "  - delete:\n"
                + "      ref: $delete_alias\n");

        ApplyPatch.AddOperation firstAdd = (ApplyPatch.AddOperation) patch.changes().get(0).operation();
        assertThat(firstAdd.as()).contains("root_alias");
        assertThat(firstAdd.component()).isEqualTo("$component-literal");
        assertThat(firstAdd.position()).contains("$position-literal");
        assertThat(firstAdd.properties().get(0).property().segments())
                .containsExactly("$property-literal");
        assertThat(firstAdd.properties().get(0).value()).isEqualTo("$value-literal");

        ApplyPatch.AddOperation parentAlias = (ApplyPatch.AddOperation) patch.changes().get(1).operation();
        assertThat(parentAlias.parentExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(parentAlias.parentExpression().spelling()).isEqualTo("$root_alias");
        assertThat(parentAlias.beforeExpression()).get().isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(parentAlias.beforeExpression()).get().extracting(ApplyPatch.ReferenceExpression::spelling)
                .isEqualTo("$before_alias");

        ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) patch.changes().get(3).operation();
        assertThat(set.refExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(set.ref()).isEqualTo("$set_alias");

        ApplyPatch.MoveOperation move = (ApplyPatch.MoveOperation) patch.changes().get(4).operation();
        assertThat(move.refExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(move.parentExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(move.beforeExpression()).get().isInstanceOf(ApplyPatch.AliasReference.class);

        ApplyPatch.DeleteOperation delete = (ApplyPatch.DeleteOperation) patch.changes().get(6).operation();
        assertThat(delete.refExpression()).isInstanceOf(ApplyPatch.AliasReference.class);
        assertThat(((ApplyPatch.AliasReference) delete.refExpression()).alias()).isEqualTo("delete_alias");
    }

    @Test
    void rejectsInvalidAliasNamesAndAliasMarkerWithoutAName() {
        String maximumAlias = "a" + repeat("b", 63);
        ApplyPatch.AddOperation maximum = (ApplyPatch.AddOperation) parser.parse(aliasOnAdd(maximumAlias))
                .changes().get(0).operation();
        assertThat(maximum.as()).contains(maximumAlias);

        assertInvalid(aliasOnAdd("1starts-with-digit"), "alias name");
        assertInvalid(aliasOnAdd("_starts-with-underscore"), "alias name");
        assertInvalid(aliasOnAdd("contains.dot"), "alias name");
        assertInvalid(aliasOnAdd("contains space"), "alias name");
        assertInvalid(aliasOnAdd("a" + repeat("b", 64)), "alias name");
        assertInvalid(aliasOnAdd("$"), "alias name");
        assertInvalid("changes:\n  - delete:\n      ref: $1not_valid\n", "alias name");
    }

    @Test
    void rejectsAliasDeclarationsOnNonAddCardsAndDuplicateYamlAsKeys() {
        assertInvalid("changes:\n  - set:\n      ref: ordinary-ref\n      as: forbidden\n      properties:\n        - property: [TestElement.name]\n          value: name\n          type: string\n", "unknown field 'as'");
        assertInvalid("changes:\n  - move:\n      ref: ordinary-ref\n      parent: parent-ref\n      as: forbidden\n", "unknown field 'as'");
        assertInvalid("changes:\n  - delete:\n      ref: ordinary-ref\n      as: forbidden\n", "unknown field 'as'");
        assertInvalid("changes:\n  - add:\n      parent: parent-ref\n      component: child.component\n      as: first\n      as: second\n", "malformed YAML patch");
    }

    @Test
    void keepsDollarStringsLiteralOutsideReferenceBearingFields() {
        ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) parser.parse(
                "changes:\n  - add:\n      parent: parent-ref\n      position: $literal-position\n      component: $literal-component\n      properties:\n        - property: [$literal-property]\n          value: $literal-value\n          type: string\n").changes().get(0).operation();

        assertThat(add.position()).contains("$literal-position");
        assertThat(add.component()).isEqualTo("$literal-component");
        assertThat(add.properties().get(0).property().segments()).containsExactly("$literal-property");
        assertThat(add.properties().get(0).value()).isEqualTo("$literal-value");
        assertInvalid("changes:\n  - add:\n      parent: parent-ref\n      component: child.component\n      properties:\n        - property: [TestElement.name]\n          value: $literal-value\n          type: $literal-type\n", "unsupported value type '$literal-type'");
    }

    @Test
    void parsesAddWithoutPropertiesAsDefaultsOnly() {
        ApplyPatch patch = parser.parse("changes:\n  - add:\n      parent: thread-group-1\n      position: last\n      component: http.request\n");

        ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) patch.changes().get(0).operation();
        assertThat(add.parent()).isEqualTo("thread-group-1");
        assertThat(add.position()).contains("last");
        assertThat(add.component()).isEqualTo("http.request");
        assertThat(add.properties()).isEmpty();
    }

    @Test
    void parsesAddWithEmptyPropertiesAsDefaultsOnly() {
        ApplyPatch patch = parser.parse("changes:\n  - add:\n      parent: thread-group-1\n      position: last\n      component: http.request\n      properties: []\n");

        ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) patch.changes().get(0).operation();
        assertThat(add.properties()).isEmpty();
    }

    @Test
    void rejectsUnknownOperation() {
        assertInvalid("changes:\n  - replace:\n      ref: http-request-1\n", "unknown operation key 'replace'");
    }

    @Test
    void rejectsJsonDocumentsAndDuplicateYamlKeys() {
        assertInvalid("{\"changes\":[]}", "JSON patch documents are not accepted");
        assertInvalid("# generated by a JSON-speaking tool\n{\"changes\":[]}\n", "JSON patch documents are not accepted");
        assertInvalid("%YAML 1.2\n---\n[changes, []]\n", "JSON patch documents are not accepted");

        assertInvalid("changes: []\nchanges: []\n", "malformed YAML patch");
    }

    @Test
    void rejectsMultipleOperationKeysInOneChange() {
        assertInvalid("changes:\n  - set:\n      ref: http-request-1\n      properties:\n        - property: HTTPSampler\\.domain\n          value: api.example.test\n          type: string\n    delete:\n      ref: debug-sampler-1\n", "exactly one operation key");
    }

    @Test
    void rejectsUnknownTopLevelAndOperationFields() {
        assertInvalid("author: agent\nchanges: []\n", "unknown top-level field 'author'");

        assertInvalid("changes:\n  - delete:\n      ref: debug-sampler-1\n      locator: legacy-id\n", "unknown field 'locator'");
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertInvalid("changes:\n  - set:\n      ref: http-request-1\n", "field 'properties' is required");
    }

    @Test
    void rejectsInvalidPropertyTypeAndInvalidValueScalar() {
        assertInvalid("changes:\n  - set:\n      ref: http-request-1\n      properties:\n        - property: [HTTPSampler.domain]\n          value: api.example.test\n          type: object\n", "unsupported value type 'object'");

        assertInvalid("changes:\n  - set:\n      ref: http-request-1\n      properties:\n        - property: [HTTPSampler.domain]\n          value:\n            nested: not-a-scalar\n          type: string\n", "$.value.presence: missing required field");

        assertInvalidTypedValue("not-an-int", "int");
        assertInvalidTypedValue("not-a-long", "long");
        assertInvalidTypedValue("not-a-double", "double");
        assertInvalidTypedValue("not-a-boolean", "boolean");
    }

    @Test
    void rejectsScalarCoercion() {
        assertInvalid(propertyPatch("HTTPSampler.method", "100", "string"),
                "expected type 'string'");
        assertInvalid(propertyPatch("qa.count", "\"100\"", "int"),
                "expected type 'int'");
        assertInvalid(propertyPatch("qa.enabled", "\"true\"", "boolean"),
                "expected type 'boolean'");
        assertInvalid(propertyPatch("qa.float", "1e100", "float"),
                "finite Java float range");
        assertInvalid(propertyPatch("qa.null", "value", "null"),
                "expected type 'null'");
    }

    @ParameterizedTest(name = "{0}-rejects-{1}")
    @MethodSource("wrongNativeNumericKinds")
    void rejectsEveryWrongNativeNumericKind(
            ApplyPatch.ValueType expected, Object actual, String actualType) {
        assertExactScalarFailure(expected, actual)
                .hasMessageContaining("expected type '" + expected.name().toLowerCase(java.util.Locale.ROOT) + "'")
                .hasMessageContaining("actual type '" + actualType + "'")
                .hasMessageContaining("actual value '" + actual + "'")
                .hasMessageContaining("address [qa.scalar]");
    }

    @ParameterizedTest(name = "{0}-rejects-{1}")
    @MethodSource("nonFiniteNativeValues")
    void rejectsNativeNaNAndInfinity(ApplyPatch.ValueType expected, Object actual) {
        assertExactScalarFailure(expected, actual)
                .hasMessageContaining("expected type '" + expected.name().toLowerCase(java.util.Locale.ROOT) + "'")
                .hasMessageContaining("actual type '" + actual.getClass().getSimpleName() + "'")
                .hasMessageContaining("actual value '" + actual + "'")
                .hasMessageContaining("address [qa.scalar]");
    }

    @Test
    void rejectsIntegerAndLongOverflowWithNativeTypeValueAndAddress() {
        assertThatThrownBy(() -> parser.parse(propertyPatch("qa.scalar", "2147483648", "int")))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("expected type 'int'")
                .hasMessageContaining("actual type 'Long'")
                .hasMessageContaining("actual value '2147483648'")
                .hasMessageContaining("address [qa.scalar]");
        assertThatThrownBy(() -> parser.parse(propertyPatch(
                "qa.scalar", "9223372036854775808", "long")))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("expected type 'long'")
                .hasMessageContaining("actual type 'BigInteger'")
                .hasMessageContaining("actual value '9223372036854775808'")
                .hasMessageContaining("address [qa.scalar]");
    }

    @Test
    void rejectsNullMismatchWithNativeTypeValueAndAddress() {
        assertExactScalarFailure(ApplyPatch.ValueType.INT, null)
                .hasMessageContaining("expected type 'int'")
                .hasMessageContaining("actual type 'null'")
                .hasMessageContaining("actual value 'null'")
                .hasMessageContaining("address [qa.scalar]");
    }

    @Test
    void boundsRejectedScalarValueInDiagnostics() {
        String actual = repeat("x", 4096);

        Throwable failure = captureExactScalarFailure(ApplyPatch.ValueType.INT, actual);

        assertThat(failure.getMessage()).contains("actual type 'String'")
                .contains("actual value '")
                .contains("[truncated]")
                .hasSizeLessThan(512);
    }

    @Test
    void keepsExactNativeFloatAndDoubleValuesDistinct() {
        Object floatValue = parseExactScalar(ApplyPatch.ValueType.FLOAT, Float.valueOf(1.25F));
        Object doubleValue = parseExactScalar(ApplyPatch.ValueType.DOUBLE, Double.valueOf(2.5D));

        assertThat(floatValue).isExactlyInstanceOf(Float.class).isEqualTo(1.25F);
        assertThat(doubleValue).isExactlyInstanceOf(Double.class).isEqualTo(2.5D);
    }

    @Test
    void rejectsFieldsForbiddenForSelectedOperation() {
        assertInvalid("changes:\n  - delete:\n      ref: debug-sampler-1\n      properties:\n        - property: TestElement\\.name\n          value: Debug Sampler\n          type: string\n", "unknown field 'properties'");
    }

    @Test
    void parsesEmptyChangesAndRejectsVersionAndInvalidChanges() {
        ApplyPatch patch = parser.parse("changes: []\n");

        assertThat(patch.changes()).isEmpty();
        assertInvalid("version: 1\nchanges: []\n", "unknown top-level field 'version'");
        assertInvalid("version: 2\nchanges: []\n", "unknown top-level field 'version'");
        assertInvalid("author: agent\n", "unknown top-level field 'author'");
        assertInvalid("changes: not-a-list\n", "field 'changes' must be a list");
    }

    @Test
    void rejectsDuplicateAmbiguousRefsWithinParserScope() {
        assertInvalid("changes:\n  - delete:\n      ref: duplicate-ref\n  - move:\n      ref: duplicate-ref\n      parent: thread-group-1\n      position: last\n", "duplicate ref 'duplicate-ref'");
    }

    @Test
    void rejectsMalformedRecursiveResponseAssertionCollectionItem() {
        assertInvalid("changes:\n"
                + "  - set:\n"
                + "      ref: response-assertion-1\n"
                + "      properties:\n"
                + "        - property: [Asserion.test_strings]\n"
                + "          type: collection\n"
                + "          value:\n"
                + "            presence: present\n"
                + "            property_class: org.apache.jmeter.testelement.property.CollectionProperty\n"
                + "            items:\n"
                + "              - access denied\n",
                "$.value.items[0]: expected mapping");
    }

    @Test
    void rejectsStrictRecursiveUnknownFieldsKindsTagsAndExtraDocuments() {
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "presence: present\nproperty_class: x.Collection\nitems: []\ninjected: true\n",
                address("qa.collection"), ApplyPatch.ValueType.COLLECTION))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("$.value.injected: unknown field");
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "presence: present\nproperty_class: x.Map\nentries: wrong\n",
                address("qa.map"), ApplyPatch.ValueType.MAP))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("$.value.entries: expected list");
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "presence: present\nproperty_class: x.ElementProperty\n"
                        + "element_class: x.Element\nproperties: {}\n",
                address("qa.element"), ApplyPatch.ValueType.ELEMENT))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("$.value.properties: expected list");
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "presence: present\nproperty_class: x.Opaque\nformat: bad\n"
                        + "base_digest: abc\nouter_property_class: x.Opaque\n"
                        + "runtime_fingerprint: runtime\npayload: value\n",
                address("qa.opaque"), ApplyPatch.ValueType.OPAQUE))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("$.value.format: unrecognized opaque format");
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "!!java.lang.Runtime {}\n", address("qa.collection"), ApplyPatch.ValueType.COLLECTION))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("malformed YAML patch");
        assertThatThrownBy(() -> parser.parseStructuredValue(
                "presence: absent\nproperty_class: x.Collection\n---\ntrailing\n",
                address("qa.collection"), ApplyPatch.ValueType.COLLECTION))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining("malformed YAML patch");
    }

    private void assertInvalid(String yaml, String message) {
        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(ApplyPatchParseException.class)
                .hasMessageContaining(message);
    }

    private void assertInvalidTypedValue(String value, String type) {
        assertInvalid(propertyPatch("HTTPSampler.domain", value, type),
                "expected type '" + type + "'");
    }

    private org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertExactScalarFailure(
            ApplyPatch.ValueType expected, Object actual) {
        return assertThatThrownBy(() -> parseExactScalar(expected, actual))
                .isInstanceOf(ApplyPatchParseException.class);
    }

    private Throwable captureExactScalarFailure(ApplyPatch.ValueType expected, Object actual) {
        try {
            parseExactScalar(expected, actual);
            throw new AssertionError("expected scalar parsing to fail");
        } catch (ApplyPatchParseException exception) {
            return exception;
        }
    }

    private Object parseExactScalar(ApplyPatch.ValueType expected, Object actual) {
        try {
            Method method = ApplyPatchParser.class.getDeclaredMethod(
                    "parseScalar", Object.class, ApplyPatch.ValueType.class,
                    io.github.thisccl.j4a.path.PropertyAddress.class, String.class);
            method.setAccessible(true);
            return method.invoke(parser, actual, expected,
                    parser.parsePropertyAddress("[\"qa.scalar\"]"), "property record[0]");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Stream<Arguments> wrongNativeNumericKinds() {
        return Stream.of(
                Arguments.of(ApplyPatch.ValueType.INT, Long.valueOf(1L), "Long"),
                Arguments.of(ApplyPatch.ValueType.INT, Float.valueOf(1F), "Float"),
                Arguments.of(ApplyPatch.ValueType.INT, Double.valueOf(1D), "Double"),
                Arguments.of(ApplyPatch.ValueType.LONG, Integer.valueOf(1), "Integer"),
                Arguments.of(ApplyPatch.ValueType.LONG, Float.valueOf(1F), "Float"),
                Arguments.of(ApplyPatch.ValueType.LONG, Double.valueOf(1D), "Double"),
                Arguments.of(ApplyPatch.ValueType.FLOAT, Integer.valueOf(1), "Integer"),
                Arguments.of(ApplyPatch.ValueType.FLOAT, Long.valueOf(1L), "Long"),
                Arguments.of(ApplyPatch.ValueType.FLOAT, Double.valueOf(1D), "Double"),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Integer.valueOf(1), "Integer"),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Long.valueOf(1L), "Long"),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Float.valueOf(1F), "Float"));
    }

    private static Stream<Arguments> nonFiniteNativeValues() {
        return Stream.of(
                Arguments.of(ApplyPatch.ValueType.FLOAT, Float.valueOf(Float.NaN)),
                Arguments.of(ApplyPatch.ValueType.FLOAT, Float.valueOf(Float.POSITIVE_INFINITY)),
                Arguments.of(ApplyPatch.ValueType.FLOAT, Float.valueOf(Float.NEGATIVE_INFINITY)),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Double.valueOf(Double.NaN)),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Double.valueOf(Double.POSITIVE_INFINITY)),
                Arguments.of(ApplyPatch.ValueType.DOUBLE, Double.valueOf(Double.NEGATIVE_INFINITY)));
    }

    private static String propertyPatch(String property, String value, String type) {
        return String.format("changes:\n  - set:\n      ref: http-request-1\n      properties:\n"
                + "        - property: [%s]\n          value: %s\n          type: '%s'\n",
                property, value, type);
    }

    private static String propertyRecord(String property, String value) {
        return "changes:\n"
                + "  - set:\n"
                + "      ref: http-request-1\n"
                + "      properties:\n"
                + "        - property: " + property + "\n"
                + "          value: " + value + "\n"
                + "          type: string\n";
    }

    private static String incrementalCard(String operation, String fields) {
        return "changes:\n"
                + "  - " + operation + ":\n"
                + "      ref: header-manager-ref\n"
                + fields;
    }

    private static String aliasOnAdd(String alias) {
        return "changes:\n  - add:\n      parent: parent-ref\n      component: child.component\n      as: " + alias + "\n";
    }

    private static PropertyAddress address(String property) {
        return new PropertyAddress(Collections.<Object>singletonList(property));
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

}
