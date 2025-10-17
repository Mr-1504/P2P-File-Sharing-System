package main.java.model.submodel;

public interface INetworkModel {
    void initializeServerSocket() throws Exception;
    void startServer();
    void startUDPServer();
    int registerWithTracker();
}
