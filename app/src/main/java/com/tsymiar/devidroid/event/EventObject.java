package com.tsymiar.devidroid.event;

public class EventObject {
    private Object event;

    public EventObject(Object event) {
        this.event = event;
    }

    public Object getEvent() {
        return event;
    }

    public void setEvent(Object event) {
        this.event = event;
    }
}
