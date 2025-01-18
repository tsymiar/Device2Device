package com.tsymiar.device2device.event;

import androidx.annotation.NonNull;

public class EventEntity {
    private Object event = null;

    public EventEntity() {
    }

    public EventEntity(Object event) {
        this.event = event;
    }

    public Object getEvent() {
        return event;
    }

    public void setEvent(Object event) {
        this.event = event;
    }

    @NonNull
    @Override
    public String toString() {
        if (event == null)
            return "";
        if (event instanceof String) {
            return (String) event;
        }
        return super.toString();
    }
}
