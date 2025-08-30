package main.java.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import main.java.model.FileInfor;
import main.java.model.PeerInfor;
import main.java.utils.LogTag;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static main.java.utils.Log.logError;
import static main.java.utils.Log.logInfo;

public class P2PApi implements  IP2PApi{
    private HttpServer server;
    private List<FileInfor> files = new ArrayList<>();
    private Runnable cancelTask = () -> {};
    private static final Gson gson = new Gson();

    public P2PApi() {
        initApiServer();
    }

    private void initApiServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.setExecutor(Executors.newFixedThreadPool(8));

            // CORS middleware
            server.createContext("/", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    sendResponse(exchange, LogTag.OK, "");
                }
            });

            server.start();
            logInfo("API Server started on port 8080");
        } catch (IOException e) {
            logError("Cannot init api server on port 8080", e);
        }
    }

    @Override
    public void setRouteForRefresh(Callable<Integer> callable) {
        server.createContext("/api/files/refresh", exchange -> {
            addCorsHeaders(exchange);
            try {
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "OPTIONS":
                        sendResponse(exchange, LogTag.OK, "");
                        break;
                    case "GET":
                        Integer res = callable.call();
                        if (res == LogTag.I_NOT_CONNECTION) {
                            sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE,
                                    jsonError(LogTag.S_NOT_CONNECTION));
                            return;
                        }
                        String response = gson.toJson(files);
                        logInfo(response);
                        sendResponse(exchange, LogTag.OK, response);
                        break;
                    default:
                        sendResponse(exchange, LogTag.METHOD_NOT_ALLOW,
                                jsonError("Method not allowed"));
                }
            } catch (Exception e) {
                logError("Refresh request error", e);
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                        jsonError("Internal server error"));
            }
        });
    }

    @Override
    public void setRouteForShareFile(TriFunction<String, Integer, AtomicBoolean, String> callable) {
        server.createContext("/api/files", exchange -> {
            addCorsHeaders(exchange);
            try {
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "OPTIONS":
                        sendResponse(exchange, LogTag.OK, "");
                        break;
                    case "POST":
                        logInfo("Share file request");
                        AtomicBoolean isCancelled = new AtomicBoolean(false);

                        JsonObject body = gson.fromJson(
                                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8),
                                JsonObject.class
                        );

                        String filePath = body.has("filePath") ? body.get("filePath").getAsString() : null;
                        int isReplace = body.has("isReplace") ? body.get("isReplace").getAsInt() : -1;

                        if (filePath == null || filePath.isEmpty()) {
                            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("File path is required"));
                            return;
                        }

                        String result = callable.apply(filePath, isReplace, isCancelled);
                        logInfo(result);

                        switch (result) {
                            case LogTag.S_NOT_CONNECTION:
                                sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE,
                                        jsonError(LogTag.S_NOT_CONNECTION));
                                break;
                            case LogTag.S_NOT_FOUND:
                                sendResponse(exchange, LogTag.NOT_FOUND,
                                        jsonError("File not found"));
                                break;
                            case LogTag.S_ERROR:
                                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                                        jsonError("Internal server error"));
                                break;
                            default:
                                sendResponse(exchange, LogTag.OK, result);
                        }
                        break;
                    default:
                        sendResponse(exchange, LogTag.METHOD_NOT_ALLOW,
                                jsonError("Method not allowed"));
                }
            } catch (Exception e) {
                cancelTask.run();
                logError("Error share file request", e);
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                        jsonError("Internal server error"));
            }
        });
    }

    @Override
    public void setRouteForRemoveFile(Function<String, Integer> callable) {
        server.createContext("/api/files/remove", exchange -> {
            addCorsHeaders(exchange);
            try {
                logInfo(exchange.getRequestMethod().toUpperCase());
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "OPTIONS":
                        sendResponse(exchange, LogTag.OK, "");
                        break;
                    case "DELETE":
                        logInfo("Remove file request");
                        JsonObject body = gson.fromJson(
                                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8),
                                JsonObject.class
                        );

                        String fileName = body.has("fileName") ? body.get("fileName").getAsString() : null;

                        if (fileName == null || fileName.isEmpty()) {
                            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("File name is required"));
                            return;
                        }

                        Integer result = callable.apply(fileName);
                        if (result == LogTag.I_NOT_CONNECTION) {
                            sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                                    jsonError(LogTag.S_NOT_CONNECTION));
                        } else if (result == LogTag.I_NOT_FOUND) {
                            sendResponse(exchange, LogTag.NOT_FOUND,
                                    jsonError("not-found"));
                        } else {
                            sendResponse(exchange, LogTag.OK, gson.toJson(Collections.singletonMap("status", "success")));
                        }
                        break;
                    default:
                        sendResponse(exchange, LogTag.METHOD_NOT_ALLOW,
                                jsonError("Method not allowed"));
                }
            } catch (Exception e) {
                logError("Error remove file request", e);
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                        jsonError("Internal server error"));
            }
        });
    }

    @Override
    public void setRouteForDownloadFile(TriFunction<FileInfor, String, AtomicBoolean, Integer> callable) {
        server.createContext("/api/files/download", exchange -> {
            addCorsHeaders(exchange);
            AtomicBoolean isCancelled = new AtomicBoolean(false);
            try {
                switch (exchange.getRequestMethod().toUpperCase()) {
                    case "OPTIONS":
                        sendResponse(exchange, LogTag.OK, "");
                        break;
                    case "GET":
                        String query = exchange.getRequestURI().getRawQuery();
                        Map<String, String> params = parseQuery(query);
                        String fileName = params.get("fileName");
                        String savePath = params.get("savePath");
                        String peerInfor = params.get("peerInfor");
                        String peerIp = peerInfor.split(":")[0];
                        int peerPort = Integer.parseInt(peerInfor.split(":")[1]);
                        if (fileName == null || fileName.isEmpty() || savePath == null || savePath.isEmpty() || peerInfor.isEmpty()) {
                            sendResponse(exchange, LogTag.BAD_REQUEST, jsonError("fileName, savePath and peerInfor are required"));
                            return;
                        }
                        PeerInfor peer = new PeerInfor(peerIp, peerPort);
                        for (FileInfor file : files) {
                            if (file.getFileName().equals(fileName) && file.getPeerInfor().equals(peer)) {
                                Integer res = callable.apply(file, savePath, isCancelled);
                                if (res == LogTag.I_NOT_CONNECTION) {
                                    sendResponse(exchange, LogTag.SERVICE_UNAVAILABLE,
                                            jsonError(LogTag.S_NOT_CONNECTION));
                                    return;
                                }
                                String response = gson.toJson(Collections.singletonMap("status", "downloaded"));
                                logInfo(response);
                                sendResponse(exchange, LogTag.OK, response);
                                return;
                            }
                        }
                        sendResponse(exchange, LogTag.NOT_FOUND, jsonError("File not found"));

                        break;
                    default:
                        sendResponse(exchange, LogTag.METHOD_NOT_ALLOW,
                                jsonError("Method not allowed"));
                }
            } catch (Exception e) {
                cancelTask.run();
                logError("Download request error", e);
                sendResponse(exchange, LogTag.INTERNAL_SERVER_ERROR,
                        jsonError("Internal server error"));
            }
        });
    }

    @Override
    public void setCancelTask(Runnable runnable) {
        cancelTask = runnable;
    }

    @Override
    public void setFiles(List<FileInfor> files) {
        this.files = files;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) {
        try {
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
