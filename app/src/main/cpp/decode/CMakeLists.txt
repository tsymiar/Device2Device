add_library(converter STATIC Pcm2Wav.cpp Yuv2Rgb.cpp)
target_link_libraries(converter log)
