package utils;

public interface LogTag {
    String S_ERROR = "ERROR";
    String S_CANCELLED = "CANCELLED";
    String S_NOT_FOUND = "NOT_FOUND";
    String S_SUCCESS = "SUCCESS";
    String S_INVALID = "INVALID";
    int I_ERROR = 0;
    int I_SUCCESS = 1;
    int I_NOT_FOUND = 2;
    int I_INVALID = -1;
    int I_NOT_EXIST = 3;
    int I_NOT_PERMISSION = 4;
    int I_NOT_READY = 5;
    int I_FAILURE = 6;
    int I_CANCELLED = 7;
}
