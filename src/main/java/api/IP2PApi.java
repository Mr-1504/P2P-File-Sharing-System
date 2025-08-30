package main.java.api;

import main.java.model.FileInfor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public interface IP2PApi {
    void setRouteForRefresh(Callable<Integer> callable);

    void setRouteForShareFile(P2PApi.TriFunction<String, Integer, AtomicBoolean, String> callable);

    void setRouteForRemoveFile(Function<String, Integer> callable);

    void setRouteForDownloadFile(P2PApi.TriFunction<FileInfor, String, AtomicBoolean, Integer> callable);

    void setCancelTask(Runnable runnable);

    void setFiles(List<FileInfor> files);

    @FunctionalInterface
    public interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }
}
