package com.bot.spreadengine.model;

public class SpreadEvent {
    private String type;
    private String message;
    private Object data;

    public SpreadEvent() {}

    public SpreadEvent(String type, String message, Object data) {
        this.type = type;
        this.message = message;
        this.data = data;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }
}
