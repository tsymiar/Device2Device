#!/bin/bash

os=$(uname -s)
if [ "$os" == "Darwin" ]; then
    echo Nothing to do with macOS
    exit 0
fi

build_tools=~/build-tools
main_pwd=$(pwd)

if [ "$os" = "Linux" ]; then
    if command -v apt-get >/dev/null 2>&1 || [ -f /etc/debian_version ]; then
        echo "Detected Debian/Ubuntu - using apt"
        sudo apt-get update
        sudo apt-get install -y openjdk-8-jdk wget unzip zip git curl ca-certificates
    elif command -v yum >/dev/null 2>&1; then
        echo "Detected RHEL/CentOS - using yum"
        yum clean all
        yum install java-1.8.0-openjdk-devel wget unzip zip git curl ca-certificates -y
    else
        echo "Unsupported Linux distribution" >&2
        exit 1
    fi
fi

# Prefer JDK 8 for compatibility with Gradle 7.0.2 (Groovy 3.0.7)
if command -v javac >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export PATH="${JAVA_HOME}/bin:${PATH}"
else
    JAVA8_HOME=$(find /usr/lib/jvm -maxdepth 1 -name 'java-8-openjdk-*' -o -name 'java-1.8.0-openjdk-*' 2>/dev/null | head -1)
    if [ -n "${JAVA8_HOME}" ] && [ -d "${JAVA8_HOME}/bin" ]; then
        export JAVA_HOME="${JAVA8_HOME}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
fi
echo "Using JAVA_HOME: $JAVA_HOME"

if [ ! -d "${build_tools}" ]; then mkdir ${build_tools}; fi;
cd ${build_tools} || exit

if [ ! -d "${build_tools}/android-studio/bin" ]; then
    if [ "$USER" != "jetson" ]; then
        wget https://r1---sn-ni5een7z.gvt1.com/edgedl/android/studio/ide-zips/2020.3.1.25/android-studio-2020.3.1.25-linux.tar.gz
        tar -zvxf android-studio-2020.3.1.25-linux.tar.gz
        rm -f android-studio-2020.3.1.25-linux.tar.gz
    else
        echo "current ${USER} user, skip downloading android-studio"
    fi
else
    echo "build-tool $(ls | grep android-studio | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/android-studio/bin

if [ ! -d "${build_tools}/cmdline-tools/bin" ]; then
    wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip commandlinetools-linux-11076708_latest.zip
    rm -f commandlinetools-linux-11076708_latest.zip
    mv cmdline-tools ${build_tools}/ 2>/dev/null || true
else
    echo "build-tool $(ls | grep cmdline-tools | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/cmdline-tools/bin

if [ ! -d "${build_tools}/sdk_root/platform-tools" ]; then
    yes | sdkmanager --licenses --sdk_root=`pwd`/sdk_root
    sdkmanager 'ndk;23.0.7599858' "platform-tools" "build-tools;30.0.3" "platforms;android-31" --sdk_root=${build_tools}/sdk_root
else
    echo "build-tool $(ls | grep sdk_root | head -1) exist"
    yes | sdkmanager --licenses --sdk_root=${build_tools}/sdk_root
fi
export PATH=${PATH}:${build_tools}/sdk_root/platform-tools
ANDROID_NDK_HOME=${build_tools}/sdk_root/ndk/23.0.7599858
ANDROID_SDK_ROOT=${build_tools}/sdk_root

CMAKE_3102=cmake-3.10.2
if [ ! -d "${build_tools}/${CMAKE_3102}" ]; then
    wget https://cmake.org/files/v3.10/${CMAKE_3102}.tar.gz
    tar -xzf ${CMAKE_3102}.tar.gz -C ~/build-tools/
    rm -f ${CMAKE_3102}.tar.gz
    cd ${build_tools}/${CMAKE_3102} || exit
    if [ ! -f "/usr/lib/libuv.so" ]; then
        wget https://github.com/libuv/libuv/archive/v1.19.1/libuv-1.19.1.tar.gz
        tar -xzf libuv-1.19.1.tar.gz -C ${build_tools}/${CMAKE_3102}/
        rm -f libuv-1.19.1.tar.gz
        cd libuv-1.19.1 || exit
        sh autogen.sh && ./configure --prefix=/usr --disable-static
        make -j$(nproc)
        sudo make install
        cd ..
    fi
    ./bootstrap --prefix=${build_tools}/${CMAKE_3102}/cmake   \
            --system-libs        \
            --no-system-jsoncpp  \
            --no-system-librhash
    make -j$(nproc)
    make install
    cd ${build_tools}
else
    echo "build-tool $(ls | grep cmake-3.10.2 | head -1) exist"
fi
if ! command -v cmake >/dev/null 2>&1; then
    wget -O - https://apt.kitware.com/keys/kitware-archive-latest.asc 2>/dev/null | sudo apt-key add -
    sudo apt-add-repository 'deb https://apt.kitware.com/ubuntu/ bionic main'
    sudo apt-get update
    sudo apt-get install -y cmake
fi
export PATH=${build_tools}/${CMAKE_3102}/cmake/bin:${PATH}
echo "cmake.dir=${build_tools}/${CMAKE_3102}/cmake" > ${main_pwd}/local.properties
echo "Using cmake version: $(cmake --version | head -1)"

the_gradle=gradle-7.0.2
if [ ! -d "${build_tools}/${the_gradle}/bin" ]; then
    if [ ! -f "${the_gradle}-bin.zip" ]; then
        wget https://services.gradle.org/distributions/${the_gradle}-bin.zip
    fi
    unzip ${the_gradle}-bin.zip
    rm -f ${the_gradle}-bin.zip
    chmod +x ${the_gradle}/bin/gradle
else
    echo "build-tool $(ls | grep gradle | head -1) exist"
fi
export PATH=${build_tools}/${the_gradle}/bin:$PATH

cd ${main_pwd}/app/src/main/cpp/scadup
git submodule update --init --recursive

cd ${main_pwd}
echo "Start building Device2Device with Gradle"
echo "Current directory: $(pwd)"
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
echo "ndk.dir=$ANDROID_NDK_HOME" >> local.properties
echo "ANDROID_SDK_ROOT: $ANDROID_SDK_ROOT"
echo "sdk.dir=$ANDROID_SDK_ROOT" >> local.properties
gradle clean assembleDebug --no-daemon --stacktrace

echo "build.sh finished"

