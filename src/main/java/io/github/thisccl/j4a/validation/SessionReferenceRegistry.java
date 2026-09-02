package io.github.thisccl.j4a.validation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

final class SessionReferenceRegistry {
    private static final int DEFAULT_DOCUMENT_LIMIT = 64;
    private static final int DEFAULT_REFERENCE_LIMIT = 100_000;

    private final int documentLimit;
    private final int referenceLimit;
    private final AtomicReference<State> liveState = new AtomicReference<>(State.empty());

    SessionReferenceRegistry() {
        this(DEFAULT_DOCUMENT_LIMIT, DEFAULT_REFERENCE_LIMIT);
    }

    SessionReferenceRegistry(int documentLimit, int referenceLimit) {
        if (documentLimit <= 0) {
            throw new IllegalArgumentException("documentLimit must be positive");
        }
        if (referenceLimit <= 0) {
            throw new IllegalArgumentException("referenceLimit must be positive");
        }
        this.documentLimit = documentLimit;
        this.referenceLimit = referenceLimit;
    }

    int documentLimit() {
        return documentLimit;
    }

    int referenceLimit() {
        return referenceLimit;
    }

    PreparedState prepare(DocumentIdentity... protectedDocuments) {
        Objects.requireNonNull(protectedDocuments, "protectedDocuments");
        return prepare(new LinkedHashSet<>(Arrays.asList(protectedDocuments)));
    }

    PreparedState prepare(Set<DocumentIdentity> protectedDocuments) {
        Objects.requireNonNull(protectedDocuments, "protectedDocuments");
        State base = liveState.get();
        return new PreparedState(this, base, protectedDocuments, documentLimit, referenceLimit);
    }

    PreparedState prepareReplacement(DocumentIdentity target) {
        Objects.requireNonNull(target, "target");
        PreparedState preparedState = prepare(target);
        preparedState.removeDocument(target);
        return preparedState;
    }

    PreparedState prepareCopy(DocumentIdentity source, DocumentIdentity target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        PreparedState preparedState = prepare(source, target);
        preparedState.removeDocument(target);
        return preparedState;
    }

    PreparedState prepareInvalidation(DocumentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        PreparedState preparedState = prepare();
        preparedState.removeDocument(identity);
        preparedState.readyWithoutSuccessfulUse();
        return preparedState;
    }

    DocumentStatus documentStatus(DocumentIdentity identity, String fingerprint) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(fingerprint, "fingerprint");
        DocumentEntry document = liveState.get().documents.get(identity);
        if (document == null) {
            return DocumentStatus.ABSENT;
        }
        return document.fingerprint.equals(fingerprint) ? DocumentStatus.MATCH : DocumentStatus.MISMATCH;
    }

    PreparedPublication preparePublication(PreparedState preparedState) {
        requireOwned(preparedState);
        State candidate = preparedState.takeCandidateForPublication();
        if (candidate == null) {
            throw new IllegalStateException("Prepared state is not ready for publication");
        }
        if (liveState.get() != preparedState.baseState()) {
            throw new IllegalStateException("Session reference state changed before publication was prepared");
        }
        return new PreparedPublication(liveState, candidate);
    }

    boolean publish(PreparedState preparedState) {
        requireOwned(preparedState);
        State candidate = preparedState.takeCandidateForPublication();
        if (candidate == null) {
            return false;
        }
        return liveState.compareAndSet(preparedState.baseState(), candidate);
    }

    void discard(PreparedState preparedState) {
        requireOwned(preparedState);
        preparedState.discard();
    }

    Snapshot snapshot() {
        return new Snapshot(liveState.get());
    }

    private void requireOwned(PreparedState preparedState) {
        Objects.requireNonNull(preparedState, "preparedState");
        if (preparedState.owner() != this) {
            throw new IllegalArgumentException("Prepared state belongs to another registry");
        }
    }

    static final class CapacityExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private static final String ERROR_CODE = "MCP_REF_CAPACITY_EXCEEDED";

        private CapacityExceededException() {
            super("Session component-reference capacity exceeded");
        }

        String code() {
            return ERROR_CODE;
        }

        String getCode() {
            return ERROR_CODE;
        }
    }

    enum DocumentStatus {
        ABSENT,
        MATCH,
        MISMATCH
    }

    static final class PreparedPublication {
        private final AtomicReference<State> liveState;
        private final State candidate;

        private PreparedPublication(AtomicReference<State> liveState, State candidate) {
            this.liveState = liveState;
            this.candidate = candidate;
        }

        void publish() {
            liveState.set(candidate);
        }
    }

    static final class PreparedState {
        private enum Status {
            OPEN,
            READY,
            FAILED,
            FINISHED
        }

        private final SessionReferenceRegistry owner;
        private final State baseState;
        private final Map<DocumentIdentity, MutableDocument> documents = new LinkedHashMap<>();
        private final Set<DocumentIdentity> protectedDocuments;
        private final Set<String> liveTokens;
        private final Set<String> reservedTokens = new HashSet<>();
        private final int documentLimit;
        private final int referenceLimit;
        private long nextAccessOrder;
        private int referenceCount;
        private Status status = Status.OPEN;
        private State candidateState;

        private PreparedState(
                SessionReferenceRegistry owner,
                State baseState,
                Collection<DocumentIdentity> protectedDocuments,
                int documentLimit,
                int referenceLimit) {
            this.owner = owner;
            this.baseState = baseState;
            this.protectedDocuments = new LinkedHashSet<>();
            for (DocumentIdentity identity : protectedDocuments) {
                this.protectedDocuments.add(Objects.requireNonNull(identity, "protected document"));
            }
            for (Map.Entry<DocumentIdentity, DocumentEntry> entry : baseState.documents.entrySet()) {
                MutableDocument copy = new MutableDocument(entry.getValue());
                documents.put(entry.getKey(), copy);
                referenceCount += copy.referenceCount();
            }
            liveTokens = baseState.tokens();
            nextAccessOrder = baseState.nextAccessOrder;
            this.documentLimit = documentLimit;
            this.referenceLimit = referenceLimit;
        }

        synchronized void bindDocument(DocumentIdentity identity, String fingerprint) {
            requireOpen();
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(fingerprint, "fingerprint");
            protectedDocuments.add(identity);
            MutableDocument current = documents.get(identity);
            if (current == null) {
                documents.put(identity, MutableDocument.empty(fingerprint, ++nextAccessOrder));
            } else if (!current.fingerprint.equals(fingerprint)) {
                referenceCount -= current.referenceCount();
                documents.put(identity, MutableDocument.empty(fingerprint, current.accessOrder));
            }
            ensureCapacity();
        }

        synchronized String expose(
                DocumentIdentity identity,
                String fingerprint,
                String locator,
                String expectedClass,
                ReferenceTokenGenerator tokenGenerator) {
            requireOpen();
            MutableDocument document = requireDocument(identity, fingerprint);
            String existingToken = document.tokenByLocator.get(locator);
            if (existingToken != null) {
                ReferenceRecord record = document.recordsByToken.get(existingToken);
                if (record != null && record.expectedClass.equals(expectedClass)) {
                    return existingToken;
                }
                status = Status.FAILED;
                throw new IllegalStateException("Stored component class cannot be proven");
            }

            String token = tokenGenerator.allocate(liveTokens, reservedTokens);
            document.recordsByToken.put(token, new ReferenceRecord(locator, expectedClass));
            document.tokenByLocator.put(locator, token);
            referenceCount++;
            ensureCapacity();
            return token;
        }

        synchronized ReferenceRecord resolveRecord(
                DocumentIdentity identity, String fingerprint, String publicReference) {
            requireOpen();
            if (publicReference == null || publicReference.trim().isEmpty()) {
                return null;
            }
            MutableDocument document = documents.get(identity);
            if (document == null || !document.fingerprint.equals(fingerprint)) {
                return null;
            }
            return document.recordsByToken.get(publicReference);
        }

        synchronized Map<String, ReferenceRecord> retainedRecords(
                DocumentIdentity identity, String fingerprint) {
            requireOpen();
            MutableDocument document = documents.get(identity);
            if (document == null || !document.fingerprint.equals(fingerprint)) {
                return Collections.emptyMap();
            }
            return Collections.unmodifiableMap(
                    new LinkedHashMap<String, ReferenceRecord>(document.recordsByToken));
        }

        synchronized void successfulUse(DocumentIdentity identity) {
            requireOpen();
            MutableDocument document = documents.get(Objects.requireNonNull(identity, "identity"));
            if (document == null) {
                throw new IllegalStateException("Document is not present in prepared state");
            }
            document.accessOrder = ++nextAccessOrder;
            candidateState = freezeCandidate();
            status = Status.READY;
        }

        synchronized void replaceFingerprint(
                DocumentIdentity identity, String expectedFingerprint, String committedFingerprint) {
            requireOpen();
            MutableDocument document = requireDocument(identity, expectedFingerprint);
            document.fingerprint = Objects.requireNonNull(committedFingerprint, "committedFingerprint");
        }

        synchronized List<SessionApplyReceipt.CreatedRef> reconcileDocument(
                DocumentIdentity identity,
                String expectedFingerprint,
                String committedFingerprint,
                PreparedReferenceState proposal,
                ReferenceTokenGenerator tokenGenerator) {
            requireOpen();
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(tokenGenerator, "tokenGenerator");
            MutableDocument document = requireDocument(identity, expectedFingerprint);
            Map<String, ReferenceRecord> survivingRecords = new LinkedHashMap<String, ReferenceRecord>();
            Map<String, String> survivingLocators = new LinkedHashMap<String, String>();
            for (PreparedReferenceState.TrackedReference survivor : proposal.survivingReferences()) {
                ReferenceRecord retained = document.recordsByToken.get(survivor.publicReference());
                if (retained == null || !retained.expectedClass.equals(survivor.expectedClass())) {
                    status = Status.FAILED;
                    throw new IllegalStateException("Tracked survivor cannot be proven in prepared state");
                }
                survivingRecords.put(survivor.publicReference(),
                        new ReferenceRecord(survivor.locator(), survivor.expectedClass()));
                survivingLocators.put(survivor.locator(), survivor.publicReference());
            }
            referenceCount -= document.referenceCount();
            document.recordsByToken.clear();
            document.recordsByToken.putAll(survivingRecords);
            document.tokenByLocator.clear();
            document.tokenByLocator.putAll(survivingLocators);
            referenceCount += document.referenceCount();

            List<SessionApplyReceipt.CreatedRef> createdRefs = new ArrayList<SessionApplyReceipt.CreatedRef>();
            for (PreparedReferenceState.CreatedAlias alias : proposal.createdAliases()) {
                String token = expose(identity, expectedFingerprint, alias.locator(), alias.expectedClass(), tokenGenerator);
                createdRefs.add(new SessionApplyReceipt.CreatedRef(alias.alias(), token));
            }
            document.fingerprint = Objects.requireNonNull(committedFingerprint, "committedFingerprint");
            return Collections.unmodifiableList(createdRefs);
        }

        synchronized List<SessionApplyReceipt.CreatedRef> createDocumentAliases(
                DocumentIdentity identity,
                String fingerprint,
                PreparedReferenceState proposal,
                ReferenceTokenGenerator tokenGenerator) {
            requireOpen();
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(tokenGenerator, "tokenGenerator");
            MutableDocument document = requireDocument(identity, fingerprint);
            if (document.referenceCount() != 0) {
                status = Status.FAILED;
                throw new IllegalStateException("Copy target was not replaced before alias allocation");
            }
            List<SessionApplyReceipt.CreatedRef> createdRefs = new ArrayList<SessionApplyReceipt.CreatedRef>();
            for (PreparedReferenceState.CreatedAlias alias : proposal.createdAliases()) {
                String token = expose(identity, fingerprint, alias.locator(), alias.expectedClass(), tokenGenerator);
                createdRefs.add(new SessionApplyReceipt.CreatedRef(alias.alias(), token));
            }
            return Collections.unmodifiableList(createdRefs);
        }

        private synchronized void removeDocument(DocumentIdentity identity) {
            requireOpen();
            MutableDocument removed = documents.remove(Objects.requireNonNull(identity, "identity"));
            if (removed != null) {
                referenceCount -= removed.referenceCount();
            }
        }

        private synchronized void readyWithoutSuccessfulUse() {
            requireOpen();
            candidateState = freezeCandidate();
            status = Status.READY;
        }

        private MutableDocument requireDocument(DocumentIdentity identity, String fingerprint) {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(fingerprint, "fingerprint");
            MutableDocument document = documents.get(identity);
            if (document == null || !document.fingerprint.equals(fingerprint)) {
                throw new IllegalStateException("Document is not bound to this prepared state");
            }
            return document;
        }

        private void ensureCapacity() {
            while (documents.size() > documentLimit || referenceCount > referenceLimit) {
                DocumentIdentity eviction = leastRecentlyUsedEligibleDocument();
                if (eviction == null) {
                    status = Status.FAILED;
                    throw new CapacityExceededException();
                }
                MutableDocument removed = documents.remove(eviction);
                referenceCount -= removed.referenceCount();
            }
        }

        private DocumentIdentity leastRecentlyUsedEligibleDocument() {
            DocumentIdentity selected = null;
            long selectedOrder = Long.MAX_VALUE;
            for (Map.Entry<DocumentIdentity, MutableDocument> entry : documents.entrySet()) {
                if (!protectedDocuments.contains(entry.getKey()) && entry.getValue().accessOrder < selectedOrder) {
                    selected = entry.getKey();
                    selectedOrder = entry.getValue().accessOrder;
                }
            }
            return selected;
        }

        private void requireOpen() {
            if (status != Status.OPEN) {
                throw new IllegalStateException("Prepared state is no longer open");
            }
        }

        private synchronized State takeCandidateForPublication() {
            if (status != Status.READY) {
                return null;
            }
            status = Status.FINISHED;
            return candidateState;
        }

        private State freezeCandidate() {
            Map<DocumentIdentity, DocumentEntry> frozen = new LinkedHashMap<>();
            for (Map.Entry<DocumentIdentity, MutableDocument> entry : documents.entrySet()) {
                frozen.put(entry.getKey(), entry.getValue().freeze());
            }
            return new State(frozen, nextAccessOrder);
        }

        private synchronized void discard() {
            status = Status.FINISHED;
        }

        private SessionReferenceRegistry owner() {
            return owner;
        }

        private State baseState() {
            return baseState;
        }
    }

    static final class ReferenceRecord {
        private final String locator;
        private final String expectedClass;

        private ReferenceRecord(String locator, String expectedClass) {
            this.locator = Objects.requireNonNull(locator, "locator");
            this.expectedClass = Objects.requireNonNull(expectedClass, "expectedClass");
        }

        String locator() {
            return locator;
        }

        String expectedClass() {
            return expectedClass;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ReferenceRecord)) {
                return false;
            }
            ReferenceRecord that = (ReferenceRecord) other;
            return locator.equals(that.locator) && expectedClass.equals(that.expectedClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(locator, expectedClass);
        }
    }

    static final class Snapshot {
        private final State state;

        private Snapshot(State state) {
            this.state = state;
        }

        int documentCount() {
            return state.documents.size();
        }

        int referenceCount() {
            int count = 0;
            for (DocumentEntry document : state.documents.values()) {
                count += document.recordsByToken.size();
            }
            return count;
        }

        boolean contains(DocumentIdentity identity) {
            return state.documents.containsKey(identity);
        }

        String fingerprint(DocumentIdentity identity) {
            DocumentEntry document = state.documents.get(identity);
            return document == null ? null : document.fingerprint;
        }

        Set<String> tokens(DocumentIdentity identity) {
            DocumentEntry document = state.documents.get(identity);
            if (document == null) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(document.recordsByToken.keySet()));
        }

        List<DocumentIdentity> identitiesInLruOrder() {
            List<DocumentIdentity> identities = new ArrayList<>(state.documents.keySet());
            identities.sort(Comparator.comparingLong(identity -> state.documents.get(identity).accessOrder));
            return Collections.unmodifiableList(identities);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Snapshot && state.equals(((Snapshot) other).state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            List<String> entries = new ArrayList<>();
            for (DocumentIdentity identity : identitiesInLruOrder()) {
                entries.add(identity.documentPath() + "=" + tokens(identity));
            }
            return entries.toString();
        }
    }

    private static final class MutableDocument {
        private String fingerprint;
        private final Map<String, ReferenceRecord> recordsByToken;
        private final Map<String, String> tokenByLocator;
        private long accessOrder;

        private MutableDocument(DocumentEntry source) {
            this(source.fingerprint, source.accessOrder, source.recordsByToken, source.tokenByLocator);
        }

        private MutableDocument(
                String fingerprint,
                long accessOrder,
                Map<String, ReferenceRecord> recordsByToken,
                Map<String, String> tokenByLocator) {
            this.fingerprint = fingerprint;
            this.accessOrder = accessOrder;
            this.recordsByToken = new LinkedHashMap<>(recordsByToken);
            this.tokenByLocator = new LinkedHashMap<>(tokenByLocator);
        }

        private static MutableDocument empty(String fingerprint, long accessOrder) {
            return new MutableDocument(
                    fingerprint,
                    accessOrder,
                    Collections.<String, ReferenceRecord>emptyMap(),
                    Collections.<String, String>emptyMap());
        }

        private int referenceCount() {
            return recordsByToken.size();
        }

        private DocumentEntry freeze() {
            return new DocumentEntry(fingerprint, accessOrder, recordsByToken, tokenByLocator);
        }
    }

    private static final class DocumentEntry {
        private final String fingerprint;
        private final long accessOrder;
        private final Map<String, ReferenceRecord> recordsByToken;
        private final Map<String, String> tokenByLocator;

        private DocumentEntry(
                String fingerprint,
                long accessOrder,
                Map<String, ReferenceRecord> recordsByToken,
                Map<String, String> tokenByLocator) {
            this.fingerprint = fingerprint;
            this.accessOrder = accessOrder;
            this.recordsByToken = Collections.unmodifiableMap(new LinkedHashMap<>(recordsByToken));
            this.tokenByLocator = Collections.unmodifiableMap(new LinkedHashMap<>(tokenByLocator));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof DocumentEntry)) {
                return false;
            }
            DocumentEntry that = (DocumentEntry) other;
            return accessOrder == that.accessOrder
                    && fingerprint.equals(that.fingerprint)
                    && recordsByToken.equals(that.recordsByToken)
                    && tokenByLocator.equals(that.tokenByLocator);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fingerprint, accessOrder, recordsByToken, tokenByLocator);
        }
    }

    private static final class State {
        private final Map<DocumentIdentity, DocumentEntry> documents;
        private final long nextAccessOrder;

        private State(Map<DocumentIdentity, DocumentEntry> documents, long nextAccessOrder) {
            this.documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
            this.nextAccessOrder = nextAccessOrder;
        }

        private static State empty() {
            return new State(Collections.<DocumentIdentity, DocumentEntry>emptyMap(), 0L);
        }

        private Set<String> tokens() {
            Set<String> tokens = new HashSet<>();
            for (DocumentEntry document : documents.values()) {
                tokens.addAll(document.recordsByToken.keySet());
            }
            return Collections.unmodifiableSet(tokens);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof State)) {
                return false;
            }
            State that = (State) other;
            return nextAccessOrder == that.nextAccessOrder && documents.equals(that.documents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documents, nextAccessOrder);
        }
    }
}
