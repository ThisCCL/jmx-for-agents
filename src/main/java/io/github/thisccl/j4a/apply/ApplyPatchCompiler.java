package io.github.thisccl.j4a.apply;

import io.github.thisccl.j4a.reference.BoundReferences;
import io.github.thisccl.j4a.reference.ReferenceResolution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ApplyPatchCompiler {
    public ResolvedApplyPlan compile(ApplyPatch patch, BoundReferences references) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(references, "references");

        Map<String, Integer> firstDeclarations = firstDeclarations(patch);
        Map<String, ResolvedApplyPlan.SymbolSlot> availableSymbols =
                new LinkedHashMap<String, ResolvedApplyPlan.SymbolSlot>();
        List<ResolvedApplyPlan.SymbolSlot> symbolSlots = new ArrayList<ResolvedApplyPlan.SymbolSlot>();
        List<ResolvedApplyPlan.Change> changes = new ArrayList<ResolvedApplyPlan.Change>();

        for (int changeIndex = 0; changeIndex < patch.changes().size(); changeIndex++) {
            ApplyPatch.Change change = patch.changes().get(changeIndex);
            ApplyPatch.Operation operation = change == null ? null : change.operation();
            ResolvedApplyPlan.Operation compiled = compileOperation(
                    operation,
                    changeIndex,
                    references,
                    firstDeclarations,
                    availableSymbols,
                    symbolSlots);
            changes.add(new ResolvedApplyPlan.Change(
                    changeIndex, compiled, MutationChangeContext.from(operation)));
        }
        return new ResolvedApplyPlan(changes, symbolSlots);
    }

    private ResolvedApplyPlan.Operation compileOperation(
            ApplyPatch.Operation operation,
            int changeIndex,
            BoundReferences references,
            Map<String, Integer> firstDeclarations,
            Map<String, ResolvedApplyPlan.SymbolSlot> availableSymbols,
            List<ResolvedApplyPlan.SymbolSlot> symbolSlots) {
        if (operation instanceof ApplyPatch.SetOperation) {
            ApplyPatch.SetOperation set = (ApplyPatch.SetOperation) operation;
            return new ResolvedApplyPlan.SetOperation(
                    compileReference(set.refExpression(), changeIndex, "set", "ref",
                            references, firstDeclarations, availableSymbols),
                    set.component(),
                    set.properties());
        }
        if (operation instanceof ApplyPatch.AddOperation) {
            ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) operation;
            ResolvedApplyPlan.NodeReference parent = compileReference(
                    add.parentExpression(), changeIndex, "add", "parent",
                    references, firstDeclarations, availableSymbols);
            Optional<ResolvedApplyPlan.NodeReference> before = compileOptionalReference(
                    add.beforeExpression(), changeIndex, "add", "before",
                    references, firstDeclarations, availableSymbols);
            Optional<ResolvedApplyPlan.NodeReference> after = compileOptionalReference(
                    add.afterExpression(), changeIndex, "add", "after",
                    references, firstDeclarations, availableSymbols);
            Optional<ResolvedApplyPlan.SymbolSlot> declared = declareSymbol(
                    add.as(), changeIndex, availableSymbols, symbolSlots, firstDeclarations);
            return new ResolvedApplyPlan.AddOperation(
                    parent, before, after, add.position(), add.component(), add.properties(), declared);
        }
        if (operation instanceof ApplyPatch.MoveOperation) {
            ApplyPatch.MoveOperation move = (ApplyPatch.MoveOperation) operation;
            return new ResolvedApplyPlan.MoveOperation(
                    compileReference(move.refExpression(), changeIndex, "move", "ref",
                            references, firstDeclarations, availableSymbols),
                    move.component(),
                    compileReference(move.parentExpression(), changeIndex, "move", "parent",
                            references, firstDeclarations, availableSymbols),
                    compileOptionalReference(move.beforeExpression(), changeIndex, "move", "before",
                            references, firstDeclarations, availableSymbols),
                    compileOptionalReference(move.afterExpression(), changeIndex, "move", "after",
                            references, firstDeclarations, availableSymbols),
                    move.position());
        }
        if (operation instanceof ApplyPatch.DeleteOperation) {
            ApplyPatch.DeleteOperation delete = (ApplyPatch.DeleteOperation) operation;
            return new ResolvedApplyPlan.DeleteOperation(
                    compileReference(delete.refExpression(), changeIndex, "delete", "ref",
                            references, firstDeclarations, availableSymbols),
                    delete.component());
        }
        if (operation instanceof ApplyPatch.AppendOperation) {
            ApplyPatch.AppendOperation append = (ApplyPatch.AppendOperation) operation;
            return new ResolvedApplyPlan.AppendOperation(
                    compileReference(append.refExpression(), changeIndex, "append", "ref",
                            references, firstDeclarations, availableSymbols),
                    append.property(),
                    append.row());
        }
        if (operation instanceof ApplyPatch.InsertOperation) {
            ApplyPatch.InsertOperation insert = (ApplyPatch.InsertOperation) operation;
            return new ResolvedApplyPlan.InsertOperation(
                    compileReference(insert.refExpression(), changeIndex, "insert", "ref",
                            references, firstDeclarations, availableSymbols),
                    insert.property(),
                    insert.index(),
                    insert.row());
        }
        if (operation instanceof ApplyPatch.RemoveOperation) {
            ApplyPatch.RemoveOperation remove = (ApplyPatch.RemoveOperation) operation;
            return new ResolvedApplyPlan.RemoveOperation(
                    compileReference(remove.refExpression(), changeIndex, "remove", "ref",
                            references, firstDeclarations, availableSymbols),
                    remove.property(),
                    remove.index());
        }
        String operationName = operation == null ? "<missing>" : operation.name();
        throw failure(
                Reason.UNSUPPORTED_OPERATION,
                changeIndex,
                operationName,
                "<operation>",
                operationName,
                "operation is not supported");
    }

    private Optional<ResolvedApplyPlan.SymbolSlot> declareSymbol(
            Optional<String> alias,
            int changeIndex,
            Map<String, ResolvedApplyPlan.SymbolSlot> availableSymbols,
            List<ResolvedApplyPlan.SymbolSlot> symbolSlots,
            Map<String, Integer> firstDeclarations) {
        if (!alias.isPresent()) {
            return Optional.empty();
        }
        String name = alias.get();
        ResolvedApplyPlan.SymbolSlot previous = availableSymbols.get(name);
        if (previous != null) {
            throw failure(
                    Reason.DUPLICATE_ALIAS,
                    changeIndex,
                    "add",
                    "as",
                    name,
                    "alias '" + name + "' was already declared at changes[" + previous.changeIndex() + "]");
        }
        Integer firstDeclaration = firstDeclarations.get(name);
        if (firstDeclaration == null || firstDeclaration.intValue() != changeIndex) {
            throw failure(
                    Reason.DUPLICATE_ALIAS,
                    changeIndex,
                    "add",
                    "as",
                    name,
                    "alias '" + name + "' has an invalid declaration order");
        }
        ResolvedApplyPlan.SymbolSlot symbol =
                new ResolvedApplyPlan.SymbolSlot(name, symbolSlots.size(), changeIndex);
        symbolSlots.add(symbol);
        availableSymbols.put(name, symbol);
        return Optional.of(symbol);
    }

    private Optional<ResolvedApplyPlan.NodeReference> compileOptionalReference(
            Optional<ApplyPatch.ReferenceExpression> expression,
            int changeIndex,
            String operation,
            String field,
            BoundReferences references,
            Map<String, Integer> firstDeclarations,
            Map<String, ResolvedApplyPlan.SymbolSlot> availableSymbols) {
        if (!expression.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(compileReference(
                expression.get(), changeIndex, operation, field,
                references, firstDeclarations, availableSymbols));
    }

    private ResolvedApplyPlan.NodeReference compileReference(
            ApplyPatch.ReferenceExpression expression,
            int changeIndex,
            String operation,
            String field,
            BoundReferences references,
            Map<String, Integer> firstDeclarations,
            Map<String, ResolvedApplyPlan.SymbolSlot> availableSymbols) {
        if (expression instanceof ApplyPatch.OrdinaryReference) {
            String spelling = expression.spelling();
            ReferenceResolution resolution = references.resolve(spelling);
            if (resolution == null
                    || resolution.status() != ReferenceResolution.Status.RESOLVED
                    || !resolution.handle().isPresent()) {
                throw failure(
                        Reason.REFERENCE_UNAVAILABLE,
                        changeIndex,
                        operation,
                        field,
                        spelling,
                        "reference '" + spelling + "' is unavailable");
            }
            return new ResolvedApplyPlan.InputNodeReference(resolution.handle().get());
        }
        if (expression instanceof ApplyPatch.AliasReference) {
            String alias = ((ApplyPatch.AliasReference) expression).alias();
            ResolvedApplyPlan.SymbolSlot symbol = availableSymbols.get(alias);
            if (symbol != null) {
                return new ResolvedApplyPlan.SymbolReference(symbol);
            }
            Integer declarationIndex = firstDeclarations.get(alias);
            if (declarationIndex != null && declarationIndex.intValue() >= changeIndex) {
                throw failure(
                        Reason.FORWARD_ALIAS,
                        changeIndex,
                        operation,
                        field,
                        "$" + alias,
                        "alias '$" + alias + "' is declared at changes[" + declarationIndex
                                + "] and cannot be used before its declaration");
            }
            throw failure(
                    Reason.UNKNOWN_ALIAS,
                    changeIndex,
                    operation,
                    field,
                    "$" + alias,
                    "alias '$" + alias + "' is not declared in this patch");
        }
        String spelling = expression == null ? "<missing>" : expression.spelling();
        throw failure(
                Reason.UNSUPPORTED_REFERENCE_EXPRESSION,
                changeIndex,
                operation,
                field,
                spelling,
                "reference expression is not a typed ordinary-ref or alias variant");
    }

    private static Map<String, Integer> firstDeclarations(ApplyPatch patch) {
        Map<String, Integer> declarations = new LinkedHashMap<String, Integer>();
        for (int changeIndex = 0; changeIndex < patch.changes().size(); changeIndex++) {
            ApplyPatch.Change change = patch.changes().get(changeIndex);
            if (change == null || !(change.operation() instanceof ApplyPatch.AddOperation)) {
                continue;
            }
            ApplyPatch.AddOperation add = (ApplyPatch.AddOperation) change.operation();
            if (add.as().isPresent() && !declarations.containsKey(add.as().get())) {
                declarations.put(add.as().get(), changeIndex);
            }
        }
        return declarations;
    }

    private static CompilationException failure(
            Reason reason,
            int changeIndex,
            String operation,
            String field,
            String expression,
            String detail) {
        String path = "changes[" + changeIndex + "]." + operation + "." + field;
        return new CompilationException(reason, changeIndex, operation, field, expression, path + ": " + detail);
    }

    public enum Reason {
        DUPLICATE_ALIAS,
        FORWARD_ALIAS,
        UNKNOWN_ALIAS,
        REFERENCE_UNAVAILABLE,
        UNSUPPORTED_REFERENCE_EXPRESSION,
        UNSUPPORTED_OPERATION
    }

    public static final class CompilationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Reason reason;
        private final int changeIndex;
        private final String operation;
        private final String field;
        private final String expression;

        private CompilationException(
                Reason reason,
                int changeIndex,
                String operation,
                String field,
                String expression,
                String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.changeIndex = changeIndex;
            this.operation = Objects.requireNonNull(operation, "operation");
            this.field = Objects.requireNonNull(field, "field");
            this.expression = expression;
        }

        public Reason reason() {
            return reason;
        }

        public int changeIndex() {
            return changeIndex;
        }

        public String operation() {
            return operation;
        }

        public String field() {
            return field;
        }

        public String expression() {
            return expression;
        }
    }
}
