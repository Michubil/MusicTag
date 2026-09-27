cmake_minimum_required(VERSION 3.22.1)
project(musictag_fingerprint LANGUAGES C CXX)

set(VENDOR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/chromaprint/src)
set(KISSFFT ${VENDOR}/3rdparty/kissfft)

add_library(chromaprint SHARED
    ${VENDOR}/audio_processor.cpp
    ${VENDOR}/chroma.cpp
    ${VENDOR}/chroma_resampler.cpp
    ${VENDOR}/chroma_filter.cpp
    ${VENDOR}/spectrum.cpp
    ${VENDOR}/fft.cpp
    ${VENDOR}/fft_lib_kissfft.cpp
    ${VENDOR}/fingerprinter.cpp
    ${VENDOR}/image_builder.cpp
    ${VENDOR}/simhash.cpp
    ${VENDOR}/silence_remover.cpp
    ${VENDOR}/fingerprint_calculator.cpp
    ${VENDOR}/fingerprint_compressor.cpp
    ${VENDOR}/fingerprint_decompressor.cpp
    ${VENDOR}/fingerprinter_configuration.cpp
    ${VENDOR}/fingerprint_matcher.cpp
    ${VENDOR}/utils/base64.cpp
    ${VENDOR}/avresample/resample2.c
    ${VENDOR}/chromaprint.cpp
    ${KISSFFT}/kiss_fft.c
    ${KISSFFT}/kiss_fftr.c
)
target_compile_features(chromaprint PRIVATE cxx_std_17)
target_compile_definitions(chromaprint PRIVATE HAVE_CONFIG_H=1)
target_include_directories(chromaprint PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${VENDOR}
    ${VENDOR}/include
    ${KISSFFT}
)
target_link_libraries(chromaprint PRIVATE m)

add_library(musictag_fingerprint SHARED fingerprint_jni.cpp)
target_compile_features(musictag_fingerprint PRIVATE cxx_std_17)
target_include_directories(musictag_fingerprint PRIVATE ${VENDOR}/include)
target_link_libraries(musictag_fingerprint PRIVATE chromaprint)
