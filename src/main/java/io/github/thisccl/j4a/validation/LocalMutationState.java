package io.github.thisccl.j4a.validation;

import io.github.thisccl.j4a.reference.BoundReferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LocalMutationState {
    private LocalMutationState() {
    }

    static final class Bound {
        private final BoundReferences references;
        private final SessionReferenceSpace sessionSpace;

        Bound(BoundReferences references, SessionReferenceSpace sessionSpace) {
            this.references = references;
            this.sessionSpace = sessionSpace;
        }

        BoundReferences references() {
            return references;
        }

        SessionReferenceSpace sessionSpace() {
            return sessionSpace;
        }
    }

    static final class Commit {
        private final List<MutationResult.CreatedReference> created;
        private final List<String> deleted;
        private final SessionReferenceRegistry.PreparedPublication publication;

        Commit(
                List<MutationResult.CreatedReference> created,
                List<String> deleted,
                SessionReferenceRegistry.PreparedPublication publication) {
            this.created = created;
            this.deleted = deleted;
            this.publication = publication;
        }

        static List<MutationResult.CreatedReference> created(
                List<SessionApplyReceipt.CreatedRef> source) {
            List<MutationResult.CreatedReference> values =
                    new ArrayList<MutationResult.CreatedReference>();
            for (SessionApplyReceipt.CreatedRef ref : source) {
                values.add(new MutationResult.CreatedReference(
                        ref.alias(), ref.publicReference()));
            }
            return Collections.unmodifiableList(values);
        }

        List<MutationResult.CreatedReference> created() {
            return created;
        }

        List<String> deleted() {
            return deleted;
        }

        void publish() {
            if (publication != null) {
                publication.publish();
            }
        }
    }
}
