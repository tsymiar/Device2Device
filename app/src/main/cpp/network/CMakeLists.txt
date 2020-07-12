add_library(Network STATIC
        SocketTrans.cpp
        SocketNet.cpp
        kcp/ikcp.c)
target_link_libraries(Network log)
