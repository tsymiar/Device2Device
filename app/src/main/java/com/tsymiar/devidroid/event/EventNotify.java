package com.tsymiar.devidroid.event;

import java.util.LinkedList;
import java.util.List;

public class EventNotify {
    private final List<EventHandle> list = new LinkedList<>();

    public void register(EventHandle eventHandle) {
        if (!list.contains(eventHandle)) {
            list.add(eventHandle);
        }
    }

    public void remove(EventHandle eventHandle) {
        list.remove(eventHandle);
    }

    public void notifyListeners(EventEntity event) {
        for (EventHandle mel : list) {
            mel.handle(event);
        }
    }
}
