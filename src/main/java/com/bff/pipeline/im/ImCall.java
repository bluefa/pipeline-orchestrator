package com.bff.pipeline.im;

import com.bff.pipeline.config.PipelineSettings;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Runs one InfraManager call under the per-call timeout, on the bounded pool so a slow call ties up
 * only its own thread, not the reconciler tick. A timeout becomes {@link CallTimeoutException}; any
 * other failure thrown by the call propagates unchanged. This is the external-call boundary — every
 * exception it surfaces is translated to an {@code ErrorCode} by the caller
 * (see {@code docs/exception-strategy.md}).
 */
@Component
public class ImCall {

    private final ExecutorService pool;
    private final PipelineSettings settings;

    public ImCall(ExecutorService imCallPool, PipelineSettings settings) {
        this.pool = imCallPool;
        this.settings = settings;
    }

    public <T> T withTimeout(Supplier<T> call) {
        Future<T> future = pool.submit(call::get);
        try {
            return future.get(settings.perCallTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CallTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallInterruptedException(e);
        } catch (ExecutionException e) {
            // Unwrap the failure thrown by the call itself so the caller sees the real cause.
            throw e.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(e.getCause());
        }
    }

    /** A single InfraManager call exceeded the per-call timeout (→ {@code ErrorCode.CALL_TIMEOUT}). */
    public static final class CallTimeoutException extends RuntimeException {
        public CallTimeoutException() {
            super("InfraManager call exceeded the per-call timeout");
        }
    }

    /**
     * The calling thread was interrupted (e.g. shutdown). This is a fail-fast runtime signal, NOT a
     * business outcome — the reconciler rethrows it rather than recording an {@code ErrorCode}.
     */
    public static final class CallInterruptedException extends RuntimeException {
        public CallInterruptedException(InterruptedException cause) {
            super("InfraManager call interrupted", cause);
        }
    }
}
