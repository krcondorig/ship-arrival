package com.shiparrival.adapter.hapaglloyd;

public class HapagLloydTrackingException extends RuntimeException {

    public HapagLloydTrackingException(String message) {
        super(message);
    }

    public HapagLloydTrackingException(String message, Throwable cause) {
        super(message, cause);
    }
}
