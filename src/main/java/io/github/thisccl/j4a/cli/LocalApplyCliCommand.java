package io.github.thisccl.j4a.cli;

import io.github.thisccl.j4a.apply.ApplyPatch;
import io.github.thisccl.j4a.apply.ApplyWriteModeResolver;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerRequest;
import io.github.thisccl.j4a.validation.LocalJMeterWorkerResponse;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

final class LocalApplyCliCommand {
    private LocalApplyCliCommand() {
    }

    static int run(
            Path input,
            String patchSource,
            String yaml,
            ApplyPatch patch,
            LocalJMeterRuntime runtime,
            boolean dryRun,
            boolean override,
            String out,
            boolean debug) throws IOException {
        return run(input, patchSource, yaml, patch, runtime, dryRun, override, out, debug,
                CliSupport::executeLocalWorker);
    }

    static int run(
            Path input, String patchSource, String yaml, ApplyPatch patch,
            LocalJMeterRuntime runtime, ApplyWriteModeResolver.Resolution writeMode,
            String out, boolean debug) throws IOException {
        return run(input, patchSource, yaml, patch, runtime, writeMode, out, debug,
                CliSupport::executeLocalWorker);
    }

    static int run(
            Path input, String patchSource, String yaml, ApplyPatch patch,
            LocalJMeterRuntime runtime, ApplyWriteModeResolver.Resolution writeMode,
            String out, boolean debug, LocalWorkerExecutor workerExecutor) throws IOException {
        boolean dryRun = writeMode.mode() == ApplyWriteModeResolver.Mode.DRY_RUN;
        ApplyWriteModeResolver.AuthorizedCommit authorized = dryRun ? null : writeMode.authorizeCommit();
        Path target = authorized == null ? null : authorized.target();
        return runAuthorized(input, patchSource, yaml, patch, runtime, dryRun, target,
                authorized == null || authorized.replaceExisting(), debug, workerExecutor);
    }

    static int run(
            Path input,
            String patchSource,
            String yaml,
            ApplyPatch patch,
            LocalJMeterRuntime runtime,
            boolean dryRun,
            boolean override,
            String out,
            boolean debug,
            LocalWorkerExecutor workerExecutor) throws IOException {
        Path target = dryRun ? null : (override ? input : Paths.get(out));
        return runAuthorized(input, patchSource, yaml, patch, runtime, dryRun, target,
                true, debug, workerExecutor);
    }

    private static int runAuthorized(
            Path input, String patchSource, String yaml, ApplyPatch patch,
            LocalJMeterRuntime runtime, boolean dryRun, Path target,
            boolean replaceExisting, boolean debug, LocalWorkerExecutor workerExecutor) throws IOException {
        LocalJMeterWorkerResponse response = workerExecutor.execute(
                    LocalJMeterWorkerRequest.applyPatchYaml(
                            input, runtime.home(), yaml, target, replaceExisting));
            if (!response.success()) {
                CliSupport.printLocalWorkerFailure(input, response, debug);
                return CliSupport.localWorkerExitCode(response);
            }
            if (response.payload() != null && !response.payload().isEmpty()) {
                System.out.print(response.payload());
                return 0;
            }
            if (!dryRun) {
                System.out.println("Wrote: " + target);
                System.out.println("Validation passed: " + target);
                return 0;
            }
            ApplyCliCommand.printDryRun(patch);
            return 0;
    }

    interface LocalWorkerExecutor {
        LocalJMeterWorkerResponse execute(LocalJMeterWorkerRequest request);
    }
}
