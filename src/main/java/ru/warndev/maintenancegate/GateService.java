package ru.warndev.maintenancegate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

public final class GateService implements AutoCloseable {
    private final GateStore store;
    private final ThreadPoolExecutor worker;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile GateState state;
    private volatile boolean recoveryRequired;

    public GateService(GateStore store) {
        this(store, false);
    }

    public GateService(GateStore store, boolean configurationFailed) {
        this.store = store;
        try {
            if (configurationFailed) {
                throw new IOException("Invalid startup configuration");
            }
            state = store.load();
        } catch (IOException | RuntimeException error) {
            recoveryRequired = true;
            state = new GateState(0, new GateWindow(Instant.EPOCH, null, "Ошибка хранилища; требуется администратор"),
                    Set.of(), List.of());
        }
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
            Thread thread = new Thread(runnable, "MaintenanceGate-IO");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public GateState snapshot() {
        return state;
    }

    public boolean recoveryRequired() {
        return recoveryRequired;
    }

    public CompletableFuture<GateState> change(long expectedRevision, UnaryOperator<GateState> operation) {
        if (closed.get()) {
            return failed("Сервис остановлен");
        }
        if (recoveryRequired) {
            return failed("Требуется восстановление config.yml или gate.dat и перезапуск сервера");
        }
        CompletableFuture<GateState> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> pending.remove(result));
        try {
            worker.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                GateState current = state;
                if (current.revision() != expectedRevision) {
                    result.completeExceptionally(new IllegalArgumentException("Состояние изменилось; проверьте status и повторите"));
                    return;
                }
                try {
                    GateState replacement = operation.apply(current);
                    if (replacement.revision() != current.revision() + 1) {
                        throw new IllegalArgumentException("Неверная ревизия изменения");
                    }
                    store.save(replacement);
                    state = replacement;
                    result.complete(replacement);
                } catch (IllegalArgumentException error) {
                    result.completeExceptionally(error);
                } catch (IOException error) {
                    result.completeExceptionally(new IllegalStateException("Запись не выполнена; прежнее состояние сохранено"));
                } catch (RuntimeException error) {
                    result.completeExceptionally(new IllegalStateException("Не удалось применить изменение"));
                }
            });
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(new IllegalStateException("Очередь заполнена; повторите позже"));
        }
        if (closed.get()) {
            result.cancel(false);
        }
        return result;
    }

    private static CompletableFuture<GateState> failed(String message) {
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException error) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        pending.forEach(future -> future.cancel(false));
    }
}
