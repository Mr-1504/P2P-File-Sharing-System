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
    /**
     * Routes setup method for check username API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForCheckUsername(Callable<Boolean> callable);

    /**
     * Routes setup method for set username API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForSetUsername(Function<String, Boolean> callable);

    /**
     * Routes setup method for check file API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForCheckFile(Function<String, Boolean> callable);

    /**
     * Routes setup method for refresh files API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForRefresh(Callable<Integer> callable);

    /**
     * Routes setup method for share public file API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForSharePublicFile(TriFunction<String, Integer, AtomicBoolean, String> callable);

    /**
     * Routes setup method for remove file API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForRemoveFile(Function<String, Integer> callable);

    /**
     * Routes setup method for download file API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForDownloadFile(TriFunction<FileInfo, String, AtomicBoolean, String> callable);

    /**
     * Routes setup method for get progress API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForGetProgress(Callable<Map<String, ProgressInfo>> callable);

    /**
     * Routes setup method for set progress API endpoint
     *
     * @param handler the function or consumer to handle the route
     */
    void setRouteForCleanupProgress(Consumer<CleanupRequest> handler);

    /**
     * Routes setup method for pause task API endpoint
     *
     * @param handler the function or consumer to handle the route
     */
    void setRouteForCancelTask(Consumer<String> handler);

    /**
     * Routes setup method for resume task API endpoint
     *
     * @param handler the function or consumer to handle the route
     */
    void setRouteForResumeTask(Consumer<String> handler);

    /**
     * Routes setup method for pause download API endpoint
     *
     * @param handler the function or consumer to handle the route
     */
    void setRouteForPauseDownload(Function<String, Boolean> handler);

    /**
     * Routes setup method for resume download API endpoint
     *
     * @param handler the function or consumer to handle the route
     */
    void setRouteForResumeDownload(Function<String, Boolean> handler);

    /**
     * Routes setup method for share private file API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForSharePrivateFile(TriFunction<String, Integer, List<PeerInfo>, String> callable);

    /**
     * Routes setup method for get known peers API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForGetKnownPeers(Callable<Set<PeerInfo>> callable);


    /**
     * Routes setup method for edit permissions API endpoint
     *
     * @param callable the function or consumer to handle the route
     */
    void setRouteForEditPermissions(TriFunction<String, String, List<PeerInfo>, Boolean> callable);

    /**
     * Routes setup method for get sharing peers of file API endpoint
     * @param callable the function or consumer to handle the route
     */
    void setRouteForGetSharedPeers(Function<String, List<PeerInfo>> callable);

    /**
     * Routes setup method for set files
     *
     * @param files List of FileInfo objects to be set
     */
    void setFiles(List<FileInfo> files);

    /**
     * BiFunction interface for two-argument functions.
     *
     * @param <T>
     * @param <U>
     * @param <R>
     */
    @FunctionalInterface
    public interface BiFunction<T, U, R> {
        R apply(T t, U u);
    }

    /**
     * TriFunction interface for three-argument functions.
     *
     * @param <T>
     * @param <U>
     * @param <V>
     * @param <R>
     */
    @FunctionalInterface
    public interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }
}
