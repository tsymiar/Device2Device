#ifndef DEVIDROID_TIMESTAMP_H
#define DEVIDROID_TIMESTAMP_H

class TimeStamp {
private:
    static TimeStamp *m_Instance;

    TimeStamp() {};

    ~TimeStamp() {};
public:
    static TimeStamp *get();

    static unsigned long long BootTime();

    static unsigned long long AbsoluteTime();
};

#endif //DEVIDROID_TIMESTAMP_H
