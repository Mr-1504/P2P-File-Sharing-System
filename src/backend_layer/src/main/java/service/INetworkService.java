package main.java.service;

import java.util.List;

public interface INetworkService {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
    List<String> queryOnlinePeerList();
}
