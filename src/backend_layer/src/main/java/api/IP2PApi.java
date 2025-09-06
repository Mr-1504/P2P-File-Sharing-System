package main.java.api;

import main.java.domain.entities.FileInfo;
import main.java.domain.entities.ProgressInfo;
import main.java.request.CleanupRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public interface IP2PApi {
    void setRouteForCheckFile(Function<String, Boolean> callable);

    void setRouteForRefresh(Callable<Integer> callable);

    void setRouteForShareFile(P2PApi.TriFunction<String, Integer, AtomicBoolean, String> callable);

    void setRouteForRemoveFile(Function<String, Integer> callable);

    void setRouteForDownloadFile(P2PApi.TriFunction<FileInfo, String, AtomicBoolean, String> callable);

    void setRouteForGetProgress(Callable<Map<String, ProgressInfo>> callable);

    void setRouteForCleanupProgress(Consumer<CleanupRequest> handler);

    void setRouteForCancelTask(Consumer<String> handler);

    void setRouteForShareToPeers(P2PApi.BiFunction<String, List<String>, String> callable);

    void setRouteForGetKnownPeers(Callable<List<String>> callable);

    void setFiles(List<FileInfo> files);

    @FunctionalInterface
    public interface BiFunction<T, U, R> {
        R apply(T t, U u);
    }

    @FunctionalInterface
    public interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }
}
