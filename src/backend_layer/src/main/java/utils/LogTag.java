package main.java.utils;

public interface LogTag {
    String S_ERROR = "ERROR";
    String S_NOT_PERMISSION = "NOT_PERMISSION";
    String S_CANCELLED = "CANCELLED";
    String S_NOT_FOUND = "NOT_FOUND";
    String S_SUCCESS = "SUCCESS";
    String S_INVALID = "INVALID";
    String S_NOT_CONNECTION = "NOT_CONNECT_TO_TRACKER";
    int I_ERROR = 0;
    int I_SUCCESS = 1;
    int I_NOT_FOUND = 2;
    int I_INVALID = -1;
    int I_NOT_EXIST = 3;
    int I_NOT_PERMISSION = 4;
    int I_NOT_READY = 5;
    int I_FAILURE = 6;
    int I_CANCELLED = 7;
    int I_HASH_MISMATCH = 8;
    int I_NOT_CONNECTION = 9;

    int SERVICE_UNAVAILABLE = 503;
    int OK = 200;
    int METHOD_NOT_ALLOW = 405;
    int BAD_REQUEST = 400;
    int INTERNAL_SERVER_ERROR = 500;
    int NOT_FOUND = 404;
}
