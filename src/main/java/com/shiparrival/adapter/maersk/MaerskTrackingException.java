package com.shiparrival.adapter.maersk;

public class MaerskTrackingException extends RuntimeException {

    public MaerskTrackingException(String message) {
        super(message);
    }

    public MaerskTrackingException(String message, Throwable cause) {
        super(message, cause);
    }
}
