#!/bin/bash

toolPath=~/build-tools

yum install java-1.8.0-openjdk-devel
if [ ! -d "${toolPath}" ]; then mkdir ${toolPath}; fi;
cd ${toolPath} || exit

if [ ! -d "${toolPath}/android-studio/bin" ]; then
    wget https://r1---sn-ni5een7z.gvt1.com/edgedl/android/studio/ide-zips/2020.3.1.25/android-studio-2020.3.1.25-linux.tar.gz
    tar zvxf android-studio-2020.3.1.25-linux.tar.gz
    rm -f android-studio-2020.3.1.25-linux.tar.gz
else
    echo "build-tool $(ls | grep android-studio | head -1) exist"
fi
export PATH=${PATH}:${toolPath}/android-studio/bin

if [ ! -d "${toolPath}/cmdline-tools/bin" ]; then
    wget https://dl.google.com/android/repository/commandlinetools-linux-7583922_latest.zip
    unzip commandlinetools-linux-7583922_latest.zip
    rm -f commandlinetools-linux-7583922_latest.zip
else
    echo "build-tool $(ls | grep cmdline-tools | head -1) exist"
fi
export PATH=${PATH}:${toolPath}/cmdline-tools/bin

if [ ! -d "${toolPath}/sdk_root/platform-tools" ]; then
    sdkmanager "ndk-bundle" --channel=3 --channel=1 "cmake;3.18.1" 'ndk;23.0.7599858' "platform-tools" "build-tools;30.0.3" "platforms;android-31" "cmake;3.18.1" --sdk_root=`pwd`/sdk_root
    export ANDROID_NDK_HOME=$(pwd)/sdk_root/ndk-bundle
else
    echo "build-tool $(ls | grep sdk_root | head -1) exist"
fi
export PATH=${PATH}:${toolPath}/sdk_root/platform-tools
export ANDROID_SDK_ROOT=${toolPath}/sdk_root

if [ ! -d "${toolPath}/gradle-7.0.2/bin" ]; then
    wget https://services.gradle.org/distributions/gradle-7.0.2-bin.zip
    unzip gradle-7.0.2-bin.zip
    rm -f gradle-7.0.2-bin.zip
else
    echo "build-tool $(ls | grep gradle | head -1) exist"
fi
export PATH=${PATH}:${toolPath}/gradle-7.0.2/bin

cd - || exit
cd app/src/main/cpp/scadup || exit
git submodule update --init --recursive

cd - || exit
gradle clean assembleDebug --no-daemon --stacktrace
