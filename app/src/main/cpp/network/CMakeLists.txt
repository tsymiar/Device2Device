add_library(Network STATIC
        SocketNet.cpp
        kcp/ikcp.c)
target_link_libraries(Network log)
