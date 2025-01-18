package com.tsymiar.device2device.event;

public interface EventHandle {
    void handle(EventEntity... event);
}
