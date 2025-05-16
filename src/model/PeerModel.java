package model;

import com.google.gson.Gson;

import java.net.MulticastSocket;
import java.net.ServerSocket;

public class PeerModel {
    private PeerInfor host;
    private Gson gson;
    private ServerSocket serverSocket;
    private MulticastSocket multicastSocket;
    private boolean isRunning;

}
