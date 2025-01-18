#!/bin/bash

os=$(uname -s)
if [ "$os" == "Darwin" ]; then
    echo Nothing to do with macOS
    exit 0
fi

build_tools=~/build-tools

yum install java-1.8.0-openjdk-devel
if [ ! -d "${build_tools}" ]; then mkdir ${build_tools}; fi;
cd ${build_tools} || exit

if [ ! -d "${build_tools}/android-studio/bin" ]; then
    wget https://r1---sn-ni5een7z.gvt1.com/edgedl/android/studio/ide-zips/2020.3.1.25/android-studio-2020.3.1.25-linux.tar.gz
    tar zvxf android-studio-2020.3.1.25-linux.tar.gz
    rm -f android-studio-2020.3.1.25-linux.tar.gz
else
    echo "build-tool $(ls | grep android-studio | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/android-studio/bin

if [ ! -d "${build_tools}/cmdline-tools/bin" ]; then
    wget https://dl.google.com/android/repository/commandlinetools-linux-7583922_latest.zip
    unzip commandlinetools-linux-7583922_latest.zip
    rm -f commandlinetools-linux-7583922_latest.zip
else
    echo "build-tool $(ls | grep cmdline-tools | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/cmdline-tools/bin

if [ ! -d "${build_tools}/sdk_root/platform-tools" ]; then
    sdkmanager "ndk-bundle" --channel=3 --channel=1 "cmake;3.18.1" 'ndk;23.0.7599858' "platform-tools" "build-tools;30.0.3" "platforms;android-31" "cmake;3.18.1" --sdk_root=`pwd`/sdk_root
    export ANDROID_NDK_HOME=$(pwd)/sdk_root/ndk-bundle
else
    echo "build-tool $(ls | grep sdk_root | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/sdk_root/platform-tools
export ANDROID_SDK_ROOT=${build_tools}/sdk_root

if [ ! -d "${build_tools}/gradle-7.0.2/bin" ]; then
    wget https://services.gradle.org/distributions/gradle-7.0.2-bin.zip
    unzip gradle-7.0.2-bin.zip
    rm -f gradle-7.0.2-bin.zip
else
    echo "build-tool $(ls | grep gradle | head -1) exist"
fi
export PATH=${PATH}:${build_tools}/gradle-7.0.2/bin

cd - || exit
cd app/src/main/cpp/scadup || exit
git submodule update --init --recursive

cd - || exit
gradle clean assembleDebug --no-daemon --stacktrace
