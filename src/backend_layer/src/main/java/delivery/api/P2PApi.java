package delivery.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import domain.entity.FileInfo;
import domain.entity.PeerInfo;
import domain.entity.ProgressInfo;
import delivery.dto.CleanupRequest;
import utils.Log;
import utils.LogTag;

import java.util.concurrent.Callable;
import java.util.function.Function;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static utils.Log.logError;
import static utils.Log.logInfo;

/**
 * P2PApi class (Refactored)
 * Implements IP2PApi with a RESTful API.
 * This version breaks down large handler methods (like handleFilesRoutes)
 * into smaller, single-responsibility methods for each specific endpoint.
 */
public class P2PApi implements IP2PApi {
    private HttpServer server;
    private List<FileInfo> files = new ArrayList<>();
    private static final Gson gson = new Gson();

    // --- Handler Storage ---
    private TriFunction<String, String, List<PeerInfo>, Boolean> editPermissionsHandler;
    private Function<String, List<PeerInfo>> getSharedPeersHandler;
    private Callable<Boolean> checkUsernameHandler;
    private Function<String, Boolean> setUsernameHandler;
    private Function<String, Boolean> checkFileHandler;
    private Callable<Integer> refreshHandler;
    private TriFunction<String, Integer, AtomicBoolean, String> sharePublicFileHandler;
    private Function<String, Integer> removeFileHandler;
    private TriFunction<FileInfo, String, AtomicBoolean, String> downloadFileHandler;
    private Callable<Map<String, ProgressInfo>> getProgressHandler;
    private Consumer<CleanupRequest> cleanupProgressHandler;
    private Consumer<String> cancelTaskHandler;
    private Consumer<String> resumeTaskHandler;
    private TriFunction<String, Integer, List<PeerInfo>, String> sharePrivateFileHandler;
    private Callable<Set<PeerInfo>> getKnownPeersHandler;

    /**
     * Constructor to initialize the P2PApi and start the API server.
     */
    public P2PApi() {
        initApiServer();
    }

    /**
     * Initializes the API server, sets up the executor, and creates master handlers.
     */
    private void initApiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.setExecutor(Executors.newFixedThreadPool(8));

            server.createContext("/", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, LogTag.OK, "");
                } else {
                    sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Not Found"));
                }
            });

            // Master handler for all API routes
            server.createContext("/api/", this::handleApiRoutes);

            server.start();
            logInfo("API Server started on port 8080");
        } catch (IOException e) {
            logError("Cannot init api server on port 8080", e);
        }
    }

    /**
     * Main dispatcher for all requests under /api/
     * It delegates to resource-specific routers.
     */
    private void handleApiRoutes(HttpExchange exchange) {
        try {
            addCorsHeaders(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, LogTag.OK, "");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            Log.logInfo("API Request Path: " + path);
            String[] parts = path.split("/");

            if (parts.length < 3) {
                sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Invalid API route"));
                return;
            }

            String resource = parts[2]; // "files", "progress", etc.

            switch (resource) {
                case "files":
                    handleFilesRoutes(exchange, parts);
                    break;
                case "check-username":
                    handleCheckUsername(exchange);
                    break;
                case "set-username":
                    handleSetUsername(exchange);
                    break;
                case "progress":
                    handleProgressRoutes(exchange, parts);
                    break;
                case "cancel":
                    handleCancelTask(exchange);
                    break;
                case "resume":
                    handleResumeTask(exchange);
                    break;
                case "peers":
                    handlePeersRoutes(exchange, parts);
                    break;
                default:
                    sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Unknown API resource: " + resource));
            }
        } catch (Exception e) {
            logError("Unhandled API error", e);
            sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR, jsonError("Internal server error"));
        }
    }

    // --- Resource Routers ---

    /**
     * Router for /api/files/
     * Delegates to specific endpoint handlers.
     */
    private void handleFilesRoutes(HttpExchange exchange, String[] parts) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();
        Log.logInfo("Files Route Method: " + method);

        // 1. Collection routes: /api/files
        if (parts.length == 3) {
            if (method.equals("GET")) {
                handleGetFiles(exchange); // GET /api/files
            } else if (method.equals("POST")) {
                handleSharePublicFile(exchange); // POST /api/files
            } else {
                sendResponse(exchange, LogTag.METHOD_NOT_ALLOW, jsonError("Method not allowed for /api/files"));
            }
            return;
        }

        // 2. Static action routes: /api/files/{action}
        if (parts.length == 4) {
            String action = parts[3];
            if (action.equals("exists") && method.equals("GET")) {
                handleCheckFileExists(exchange); // GET /api/files/exists
                return;
            }
            if (action.equals("share-to-peers") && method.equals("POST")) {
                handleSharePrivateFile(exchange); // POST /api/files/share-to-peers
                return;
            }
        }

        // 3. Specific resource routes: /api/files/{fileName}
        if (parts.length == 4) { // Catches DELETE /api/files/{fileName}
            String fileName = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
            if (method.equals("DELETE")) {
                handleRemoveFile(exchange, fileName); // DELETE /api/files/{fileName}
                return;
            }
        }

        // 4. Specific resource action routes: /api/files/{fileName}/{action}
        if (parts.length == 5) {
            String fileName = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
            String action = parts[4];

            if (method.equals("GET")) {
                switch (action) {
                    case "shared-peers":
                        handleGetSharedPeers(exchange, fileName); // GET /api/files/{fileName}/shared-peers
                        return;
                    case "download":
                        handleDownloadFile(exchange, fileName); // GET /api/files/{fileName}/download
                        return;
                }
            } else if (method.equals("PUT")) {
                if (action.equals("permission")) {
                    handleEditPermission(exchange, fileName); // PUT /api/files/{fileName}/permission
                    return;
                }
            }
        }

        sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Unknown file route"));
    }

    /**
     * Router for /api/progress/
     * Delegates to specific endpoint handlers.
     */
    private void handleProgressRoutes(HttpExchange exchange, String[] parts) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();

        // GET /api/progress
        if (method.equals("GET") && parts.length == 3) {
            handleGetProgress(exchange);
            return;
        }

        // POST /api/progress/cleanup
        if (method.equals("POST") && parts.length == 4 && parts[3].equals("cleanup")) {
            handleCleanupProgress(exchange);
            return;
        }

        sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Unknown progress route"));
    }

    /**
     * Router for /api/peers/
     * Delegates to specific endpoint handlers.
     */
    private void handlePeersRoutes(HttpExchange exchange, String[] parts) throws Exception {
        String method = exchange.getRequestMethod().toUpperCase();

        // GET /api/peers/known
        if (method.equals("GET") && parts.length == 4 && parts[3].equals("known")) {
            handleGetKnownPeers(exchange);
            return;
        }

        sendResponse(exchange, LogTag.NOT_FOUND, jsonError("Unknown peers route"));
    }


    // --- Specific Endpoint Handlers for /api/files ---

    /**
     * Handles GET /api/files (Refresh file list)
     */
    private void handleGetFiles(HttpExchange exchange) throws Exception {
        if (refreshHandler == null) throw new UnsupportedOperationException("Refresh handler not set");

        Integer res = refreshHandler.call();
        if (res == LogTag.I_NOT_CONNECTION) {
            sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE, jsonError(LogTag.S_NOT_CONNECTION));
            return;
        }
        String response = gson.toJson(files);
        logInfo(response);
        sendResponse(exchange, LogTag.OK, response);
    }

    /**
     * Handles POST /api/files (Share public file)
     */
    private void handleSharePublicFile(HttpExchange exchange) {
        if (sharePublicFileHandler == null) throw new UnsupportedOperationException("Share public file handler not set");

        logInfo("Share file request");
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        JsonObject body = gson.fromJson(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8), JsonObject.class);
        String filePath = body.has("filePath") ? body.get("filePath").getAsString() : null;
        int isReplace = body.has("isReplace") ? body.get("isReplace").getAsInt() : -1;

        if (filePath == null || filePath.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("File path is required"));
            return;
        }
        String result = sharePublicFileHandler.apply(filePath, isReplace, isCancelled);
        logInfo(result);

        switch (result) {
            case LogTag.S_NOT_CONNECTION:
                sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE, jsonError(LogTag.S_NOT_CONNECTION));
                break;
            case LogTag.S_NOT_FOUND:
                sendResponse(exchange, LogTag.NOT_FOUND, jsonError("File not found"));
                break;
            case LogTag.S_ERROR:
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR, jsonError("Internal server error"));
                break;
            default:
                sendResponse(exchange, LogTag.OK, result);
        }
    }

    /**
     * Handles GET /api/files/exists
     */
    private void handleCheckFileExists(HttpExchange exchange) {
        if (checkFileHandler == null) throw new UnsupportedOperationException("Check file handler not set");

        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        String fileName = params.get("fileName");
        if (fileName == null || fileName.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("fileName is required"));
            return;
        }
        boolean exists = checkFileHandler.apply(fileName);
        String response = gson.toJson(Collections.singletonMap("exists", exists));
        sendResponse(exchange, LogTag.OK, response);
    }

    /**
     * Handles POST /api/files/share-to-peers
     */
    private void handleSharePrivateFile(HttpExchange exchange) {
        if (sharePrivateFileHandler == null) throw new UnsupportedOperationException("Share private file handler not set");

        logInfo("Share to peers request");
        JsonObject body = gson.fromJson(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8), JsonObject.class);
        String filePath = body.has("filePath") ? body.get("filePath").getAsString() : null;
        int isReplace = body.has("isReplace") ? body.get("isReplace").getAsInt() : 0;
        List<PeerInfo> peers = parsePeers(body);

        if (filePath == null || filePath.isEmpty() || peers.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("filePath and peers are required"));
            return;
        }

        String result = sharePrivateFileHandler.apply(filePath, isReplace, peers);
        logInfo("Share to peers result: " + result);

        switch (result) {
            case LogTag.S_NOT_CONNECTION:
                sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE, jsonError(LogTag.S_NOT_CONNECTION));
                break;
            case LogTag.S_INVALID:
                sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Invalid peer list"));
                break;
            case LogTag.S_ERROR:
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR, jsonError("Internal server error"));
                break;
            default:
                sendResponse(exchange, LogTag.OK, gson.toJson(Collections.singletonMap("status", "shared")));
        }
    }

    /**
     * Handles DELETE /api/files/{fileName}
     */
    private void handleRemoveFile(HttpExchange exchange, String fileName) {
        if (removeFileHandler == null) throw new UnsupportedOperationException("Remove file handler not set");

        logInfo("Remove file request: " + fileName);
        Integer result = removeFileHandler.apply(fileName);
        if (result == LogTag.I_NOT_CONNECTION) {
            sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR, jsonError(LogTag.S_NOT_CONNECTION));
        } else if (result == LogTag.I_NOT_FOUND) {
            sendResponse(exchange, LogTag.NOT_FOUND, jsonError("not-found"));
        } else {
            sendResponse(exchange, LogTag.OK, gson.toJson(Collections.singletonMap("status", "success")));
        }
    }

    /**
     * Handles GET /api/files/{fileName}/shared-peers
     */
    private void handleGetSharedPeers(HttpExchange exchange, String fileName) {
        if (getSharedPeersHandler == null) throw new UnsupportedOperationException("Get shared peers handler not set");

        logInfo("Getting Shared Peers of File: " + fileName);
        List<PeerInfo> peers = getSharedPeersHandler.apply(fileName);
        String response = gson.toJson(Collections.singletonMap("peers", peers));
        sendResponse(exchange, LogTag.OK, response);
    }

    /**
     * Handles PUT /api/files/{fileName}/permission
     */
    private void handleEditPermission(HttpExchange exchange, String fileName) {
        if (editPermissionsHandler == null) throw new UnsupportedOperationException("Edit permissions handler not set");

        logInfo("Edit permission request for file: " + fileName);
        JsonObject body = gson.fromJson(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8), JsonObject.class);

        String permission = body.has("permission") ? body.get("permission").getAsString() : null;
        if (permission == null || permission.trim().isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Permission is required"));
            return;
        }
        if (!permission.equals("PRIVATE") && !permission.equals("PUBLIC")) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Invalid permission"));
            return;
        }
        List<PeerInfo> peers = parsePeers(body);

        Log.logInfo(" Permission: " + permission + ", Peers: " + peers);
        boolean success = editPermissionsHandler.apply(fileName, permission, peers);
        String response = gson.toJson(Collections.singletonMap("status", success ? "success" : "failure"));
        sendResponse(exchange, LogTag.OK, response);
    }

    /**
     * Handles GET /api/files/{fileName}/download
     */
    private void handleDownloadFile(HttpExchange exchange, String fileName) {
        if (downloadFileHandler == null) throw new UnsupportedOperationException("Download file handler not set");

        AtomicBoolean isCancelled = new AtomicBoolean(false);
        String query = exchange.getRequestURI().getRawQuery();
        logInfo(query);
        Map<String, String> params = parseQuery(query);

        String savePath = params.get("savePath");
        String peerInfor = params.get("peerInfo");

        if (savePath == null || savePath.isEmpty() || peerInfor == null || peerInfor.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("savePath and peerInfo are required"));
            return;
        }

        String peerIp = peerInfor.split(":")[0];
        int peerPort = Integer.parseInt(peerInfor.split(":")[1]);
        PeerInfo peer = new PeerInfo(peerIp, peerPort);

        for (FileInfo file : files) {
            if (file.getFileName().equals(fileName) && file.getPeerInfo().equals(peer)) {
                String res = downloadFileHandler.apply(file, savePath, isCancelled);
                if (res.equals(LogTag.S_NOT_CONNECTION)) {
                    sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE, jsonError(LogTag.S_NOT_CONNECTION));
                    return;
                }
                String response = gson.toJson(Collections.singletonMap("status", "starting"));
                logInfo(response);
                sendResponse(exchange, LogTag.OK, response);
                return;
            }
        }
        sendResponse(exchange, LogTag.NOT_FOUND, jsonError("File not found"));
    }


    // --- Other Root-Level Resource Handlers ---

    /**
     * Handles GET /api/check-username
     */
    private void handleCheckUsername(HttpExchange exchange) throws Exception {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, LogTag.METHOD_NOT_ALLOW, jsonError("Method not allowed"));
            return;
        }
        if (checkUsernameHandler == null) throw new UnsupportedOperationException("CheckUsername handler not set");

        logInfo("Check username request");
        boolean hasUsername = checkUsernameHandler.call();
        String response = gson.toJson(Collections.singletonMap("hasUsername", hasUsername));
        sendResponse(exchange, LogTag.OK, response);
    }

    /**
     * Handles POST /api/set-username
     */
    private void handleSetUsername(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, LogTag.METHOD_NOT_ALLOW, jsonError("Method not allowed"));
            return;
        }
        if (setUsernameHandler == null) throw new UnsupportedOperationException("SetUsername handler not set");

        logInfo("Set username request");
        JsonObject body = gson.fromJson(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8), JsonObject.class);
        String username = body.has("username") ? body.get("username").getAsString() : null;

        if (username == null || username.trim().isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Username is required"));
            return;
        }

        boolean success = setUsernameHandler.apply(username.trim());
        if (success) {
            String response = gson.toJson(Collections.singletonMap("status", "success"));
            sendResponse(exchange, LogTag.OK, response);
        } else {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Failed to set username"));
        }
    }

    /**
     * Handles GET /api/progress
     */
    private void handleGetProgress(HttpExchange exchange) throws Exception {
        if (getProgressHandler == null) throw new UnsupportedOperationException("GetProgress handler not set");

        logInfo("Get progress request");
        Map<String, ProgressInfo> progresses = getProgressHandler.call();
        if (progresses != null) {
            String response = gson.toJson(progresses);
            logInfo("Response: " + response);
            sendResponse(exchange, 200, response);
        } else {
            sendResponse(exchange, 404, "{\"error\":\"Process not found\"}");
        }
    }

    /**
     * Handles POST /api/progress/cleanup
     */
    private void handleCleanupProgress(HttpExchange exchange) throws IOException {
        if (cleanupProgressHandler == null) throw new UnsupportedOperationException("CleanupProgress handler not set");

        logInfo("Cleanup progress request");
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        logInfo("Request body: " + requestBody);
        CleanupRequest request = gson.fromJson(requestBody, CleanupRequest.class);
        if (request == null) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("Invalid request body"));
            return;
        }
        cleanupProgressHandler.accept(request);
        sendResponse(exchange, LogTag.OK, "{\"status\":\"success\"}");
    }

    /**
     * Handles DELETE /api/cancel
     */
    private void handleCancelTask(HttpExchange exchange) {
        if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, LogTag.METHOD_NOT_ALLOW, jsonError("Method not allowed"));
            return;
        }
        if (cancelTaskHandler == null) throw new UnsupportedOperationException("CancelTask handler not set");

        logInfo("Cancel task request");
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        String taskId = params.get("taskId");
        if (taskId == null || taskId.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("taskId is required"));
            return;
        }
        cancelTaskHandler.accept(taskId);
        sendResponse(exchange, LogTag.OK, "{\"status\":\"success\"}");
    }

    /**
     * Handles POST /api/resume
     */
    private void handleResumeTask(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, LogTag.METHOD_NOT_ALLOW, jsonError("Method not allowed"));
            return;
        }
        if (resumeTaskHandler == null) throw new UnsupportedOperationException("ResumeTask handler not set");

        logInfo("Resume task request");
        String query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = parseQuery(query);
        String taskId = params.get("taskId");
        if (taskId == null || taskId.isEmpty()) {
            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("taskId is required"));
            return;
        }
        resumeTaskHandler.accept(taskId);
        sendResponse(exchange, LogTag.OK, "{\"status\":\"success\"}");
    }

    /**
     * Handles GET /api/peers/known
     */
    private void handleGetKnownPeers(HttpExchange exchange) throws Exception {
        if (getKnownPeersHandler == null) throw new UnsupportedOperationException("GetKnownPeers handler not set");

        logInfo("Get known peers request");
        Set<PeerInfo> peers = getKnownPeersHandler.call();
        String response = gson.toJson(peers);
        logInfo("Known peers response: " + response);
        sendResponse(exchange, LogTag.OK, response);
    }


    // --- Route Setter Implementations (Store Handlers) ---

    @Override
    public void setRouteForEditPermissions(TriFunction<String, String, List<PeerInfo>, Boolean> callable) {
        this.editPermissionsHandler = callable;
    }

    @Override
    public void setRouteForGetSharedPeers(Function<String, List<PeerInfo>> callable) {
        this.getSharedPeersHandler = callable;
    }

    @Override
    public void setRouteForCheckUsername(Callable<Boolean> callable) {
        this.checkUsernameHandler = callable;
    }

    @Override
    public void setRouteForSetUsername(Function<String, Boolean> callable) {
        this.setUsernameHandler = callable;
    }

    @Override
    public void setRouteForCheckFile(Function<String, Boolean> callable) {
        this.checkFileHandler = callable;
    }

    @Override
    public void setRouteForRefresh(Callable<Integer> callable) {
        this.refreshHandler = callable;
    }

    @Override
    public void setRouteForSharePublicFile(TriFunction<String, Integer, AtomicBoolean, String> callable) {
        this.sharePublicFileHandler = callable;
    }

    @Override
    public void setRouteForRemoveFile(Function<String, Integer> callable) {
        this.removeFileHandler = callable;
    }

    @Override
    public void setRouteForDownloadFile(TriFunction<FileInfo, String, AtomicBoolean, String> callable) {
        this.downloadFileHandler = callable;
    }

    @Override
    public void setRouteForGetProgress(Callable<Map<String, ProgressInfo>> callable) {
        this.getProgressHandler = callable;
    }

    @Override
    public void setRouteForCleanupProgress(Consumer<CleanupRequest> handler) {
        this.cleanupProgressHandler = handler;
    }

    @Override
    public void setRouteForCancelTask(Consumer<String> handler) {
        this.cancelTaskHandler = handler;
    }

    @Override
    public void setRouteForResumeTask(Consumer<String> handler) {
        this.resumeTaskHandler = handler;
    }

    @Override
    public void setRouteForSharePrivateFile(TriFunction<String, Integer, List<PeerInfo>, String> callable) {
        this.sharePrivateFileHandler = callable;
    }

    @Override
    public void setRouteForGetKnownPeers(Callable<Set<PeerInfo>> callable) {
        this.getKnownPeersHandler = callable;
    }

    @Override
    public void setFiles(List<FileInfo> files) {
        this.files = files;
    }

    // --- Utility Methods ---

    /**
     * Helper to parse PeerInfo list from JSON body
     */
    private List<PeerInfo> parsePeers(JsonObject body) {
        List<PeerInfo> peers = new ArrayList<>();
        if (body.has("peers")) {
            body.get("peers").getAsJsonArray().forEach(peerJson -> {
                JsonObject p = peerJson.getAsJsonObject();
                String ip = p.has("ip") ? p.get("ip").getAsString() : null;
                int port = p.has("port") ? p.get("port").getAsInt() : 0;
                String username = p.has("username") ? p.get("username").getAsString() : null;
                if (username != null) {
                    peers.add(new PeerInfo(ip, port, username));
                } else {
                    peers.add(new PeerInfo(ip, port));
                }
            });
        }
        return peers;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE, PUT");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) {
        try {
            logInfo("Response: " + response + " with status code: " + statusCode);
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
            logError("Error sending response", e);
        }
    }

    private static String jsonError(String message) {
        return gson.toJson(Collections.singletonMap("error", message));
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null) return result;

        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }
}