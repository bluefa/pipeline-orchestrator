package com.bff.pipeline.utils;

import com.bff.pipeline.exception.CallInterruptedException;
import com.bff.pipeline.exception.CallTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 외부 호출을 별도 풀에서 수행하고 호출 전체의 대기 시간을 제한한다. timeout과 인터럽트는 작업 취소를 요청하며
 * 인터럽트 표시를 복원한다. 외부 경계의 통제된 예외와 코드 오류는 원래 종류 그대로 호출자에게 전달한다.
 */
public final class BoundedCallExecutor {
    private BoundedCallExecutor() { }

    public static <T> T execute(ExecutorService pool, Duration timeout, Supplier<T> call) {
        Future<T> future = pool.submit(call::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new CallTimeoutException();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallInterruptedException();
        } catch (ExecutionException executionFailure) {
            throw unwrap(executionFailure.getCause());
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new IllegalStateException("External call failed", cause);
    }
}
