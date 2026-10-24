#!/bin/bash
# Device2Device 打包脚本 —— 不依赖 Android Studio
#
#   ./build.sh             打 debug 包（默认）
#   ./build.sh release     打 release 包（工程没配签名，出的是未签名包）
#   ./build.sh clean       清构建产物：停 daemon + 删 app/build、app/.cxx、dist
#   ./build.sh cleanall    再清 ~/build-tools 工具链与 local.properties（下次按环境变量重建）
#   BUILD_OFFLINE=1 ...    断网打包，只吃 gradle 本地缓存
#
# 产物：dist/Device2Device-<版本>-<类型>-<abi>-<日期>.apk
#
# 依赖（Linux 安装，macOS 本地）：
#   JDK 8 · Gradle 7.0.2（AGP 4.1 认死这版）· SDK platforms;31 + build-tools;30.0.3 + platform-tools
#   NDK 23.0.7599858 · CMake 3.18.1（need by native .so）

set -u
os=$(uname -s)
main_pwd=$(pwd)
OUT_DIR="${main_pwd}/dist"

CMD="${1:-}"

# gradle：AGP 4.1 只认 7.0.2，固定位置排系统 PATH 前避免撞上新版本
find_gradle() {
    for g in "${main_pwd}/gradlew" \
             $(ls -d "${HOME}"/.gradle/wrapper/dists/gradle-7.0.2-bin/*/gradle-7.0.2/bin/gradle \
                     "${HOME}"/build-tools/gradle-7.0.2/bin/gradle 2>/dev/null) \
             gradle; do
        [ -x "$g" ] || g=$(command -v "$g" 2>/dev/null) || continue
        echo "$g"; return
    done
}
GRADLE=$(find_gradle)
if [ -z "${GRADLE}" ]; then
    echo "找不到 gradle 7.0.2" >&2; exit 1
fi

# local.properties 是纯 properties，不支持 ${VAR} 展开，由脚本按环境变量代写
set_prop() {
    local key="$1" val="$2" f="${main_pwd}/local.properties"
    [ -f "$f" ] || : > "$f"
    if grep -q "^${key}=" "$f" 2>/dev/null; then
        if [ "$os" = "Darwin" ]; then
            sed -i '' "s|^${key}=.*|${key}=${val}|" "$f"
        else
            sed -i "s|^${key}=.*|${key}=${val}|" "$f"
        fi
    else
        echo "${key}=${val}" >> "$f"
    fi
}

# SDK 路径：环境变量优先，其次平台默认位置
find_sdk() {
    for d in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
             "${HOME}/Library/Android/SDK" "${HOME}/Android/Sdk"; do
        [ -n "$d" ] && [ -d "$d" ] && { echo "$d"; return; }
    done
}

clean_output() {
    echo "清理：停 gradle daemon + 删 app/build、app/.cxx、app/jniLibs、dist"
    "${GRADLE}" --stop >/dev/null 2>&1 || true
    rm -rf "${main_pwd}/app/build" "${main_pwd}/app/.cxx" "${main_pwd}/app/jniLibs" "${OUT_DIR}"
    rm -rf "${main_pwd}/logs" "${main_pwd}/*.log"
}

case "${CMD}" in
    clean)
        clean_output; exit 0 ;;
    cleanall)
        clean_output
        echo "清理：工具链 ~/build-tools 与 local.properties"
        rm -rf "${HOME}/build-tools" "${main_pwd}/local.properties"
        exit 0 ;;
    release|Release|r) VARIANT=Release ;;
    debug|Debug|d|"")  VARIANT=Debug ;;
    *) echo "用法: $0 [debug|release|clean|cleanall]" >&2; exit 1 ;;
esac
BUILD_TYPE=$(echo "${VARIANT}" | tr '[:upper:]' '[:lower:]')
echo "Building variant: ${VARIANT} (${BUILD_TYPE})"

if [ "$os" = "Linux" ]; then

build_tools=~/build-tools

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

# Gradle 7.0.2 要 JDK 8
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
echo "Using JAVA_HOME: ${JAVA_HOME:-未设置}"

if [ ! -d "${build_tools}" ]; then mkdir ${build_tools}; fi;
cd ${build_tools} || exit

if [ ! -d "${build_tools}/cmdline-tools/bin" ]; then
    wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip commandlinetools-linux-11076708_latest.zip
    rm -f commandlinetools-linux-11076708_latest.zip
    mv cmdline-tools ${build_tools}/ 2>/dev/null || true
else
    echo "build-tool cmdline-tools exist"
fi
export PATH=${PATH}:${build_tools}/cmdline-tools/bin

# sdkmanager 幂等：装过的包会跳过。cmake 用 3.18.1（AGP 4.1 自带版本）——
yes | sdkmanager --licenses --sdk_root=${build_tools}/sdk_root >/dev/null
sdkmanager --sdk_root=${build_tools}/sdk_root \
    'platform-tools' 'build-tools;30.0.3' 'platforms;android-31' \
    'ndk;23.0.7599858' 'cmake;3.18.1'
export PATH=${PATH}:${build_tools}/sdk_root/platform-tools
ANDROID_SDK_ROOT=${build_tools}/sdk_root

set_prop cmake.dir "${ANDROID_SDK_ROOT}/cmake/3.18.1"
set_prop ndk.dir "${ANDROID_SDK_ROOT}/ndk/23.0.7599858"

cd ${main_pwd}/app/src/main/cpp/scadup
git submodule update --init --recursive

else
    echo "macOS: 跳过工具链安装"
fi

SDK_DIR=$(find_sdk)
if [ -z "${SDK_DIR}" ]; then
    echo "找不到 Android SDK：请设 ANDROID_SDK_ROOT 或 ANDROID_HOME" >&2
    exit 1
fi
export ANDROID_HOME="${SDK_DIR}"
export ANDROID_SDK_ROOT="${SDK_DIR}"
set_prop sdk.dir "${SDK_DIR}"

cd "${main_pwd}" || exit

# 单一真源：每次打包前按 C++ Message.h 的 enum MASSAGER 重生成 Receiver.java，杜绝 C/JNI 数值偏离
if command -v python3 >/dev/null 2>&1; then
    if ! python3 "${main_pwd}/tools/gen_receiver_java.py"; then
        echo "生成 Receiver.java 失败" >&2
        exit 1
    fi
else
    echo "警告：未找到 python3，跳过 Receiver.java 重新生成（依赖已提交的版本）" >&2
fi

GRADLE_EXTRA=()
[ "${BUILD_OFFLINE:-}" = "1" ] && GRADLE_EXTRA=(--offline)

echo "Start building: $(pwd)"
echo "  JAVA_HOME: ${JAVA_HOME:-系统默认}"
echo "  sdk.dir  : ${SDK_DIR}"
echo "  gradle   : ${GRADLE}"

if ! "${GRADLE}" clean "assemble${VARIANT}" --no-daemon --stacktrace "${GRADLE_EXTRA[@]+"${GRADLE_EXTRA[@]}"}"; then
    echo "打包失败" >&2
    exit 1
fi

# 产物归置：app-<abi>-<type>.apk → Device2Device-<version>-<type>-<abi>-<date>.apk
APK_DIR="app/build/outputs/apk/${BUILD_TYPE}"
if [ ! -d "${APK_DIR}" ] || [ -z "$(ls -A "${APK_DIR}"/*.apk 2>/dev/null)" ]; then
    echo "没找到产物：${APK_DIR} 下没有 apk" >&2
    exit 1
fi

VERSION_NAME=$(sed -n 's/.*versionName[[:space:]]*"\([^"]*\)".*/\1/p' app/build.gradle | head -1)
VERSION_NAME=${VERSION_NAME:-1.0}
STAMP=$(date +%Y%m%d)
mkdir -p "${OUT_DIR}"
rm -f "${OUT_DIR}"/*.apk

echo
echo "产物："
for apk in "${APK_DIR}"/*.apk; do
    abi=$(basename "${apk}" .apk | sed -e 's/^app-//' -e "s/-${BUILD_TYPE}\$//")
    [ -z "${abi}" ] && abi="universal"
    dest="${OUT_DIR}/Device2Device-${VERSION_NAME}-${BUILD_TYPE}-${abi}-${STAMP}.apk"
    cp "${apk}" "${dest}"
    sum=$(shasum -a 256 "${dest}" 2>/dev/null | awk '{print $1}')
    [ -z "${sum}" ] && sum=$(sha256sum "${dest}" 2>/dev/null | awk '{print $1}')
    printf '  %s  %-6s  %s\n' "${sum:0:16}" "$(du -h "${dest}" | awk '{print $1}')" "$(basename "${dest}")"
done

echo
echo "安装："
echo "  adb install -r ${OUT_DIR}/Device2Device-${VERSION_NAME}-${BUILD_TYPE}-arm64-v8a-${STAMP}.apk"
