package delivery.api;

import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.entity.ProgressInfo;
import delivery.dto.CleanupRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public interface IP2PApi {
    // Username management routes (highest priority - called during startup)
    void setRouteForCheckUsername(Callable<Boolean> callable);
    void setRouteForSetUsername(Function<String, Boolean> callable);

    void setRouteForCheckFile(Function<String, Boolean> callable);

    void setRouteForRefresh(Callable<Integer> callable);

    void setRouteForSharePublicFile(TriFunction<String, Integer, AtomicBoolean, String> callable);

    void setRouteForRemoveFile(Function<String, Integer> callable);

    void setRouteForDownloadFile(TriFunction<FileInfo, String, AtomicBoolean, String> callable);

    void setRouteForGetProgress(Callable<Map<String, ProgressInfo>> callable);

    void setRouteForCleanupProgress(Consumer<CleanupRequest> handler);

    void setRouteForCancelTask(Consumer<String> handler);

    void setRouteForResumeTask(Consumer<String> handler);

    void setRouteForSharePrivateFile(TriFunction<String, Integer, List<PeerInfo>, String> callable);

    void setRouteForGetKnownPeers(Callable<Set<PeerInfo>> callable);

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
