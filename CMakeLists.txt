cmake_minimum_required(VERSION 3.20)
project(Yerelkesif CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# webview: yalnızca kütüphane olarak kullanılır, örnek ve testler derlenmez
set(WEBVIEW_BUILD_EXAMPLES OFF)
set(WEBVIEW_BUILD_TESTS OFF)
set(WEBVIEW_BUILD_DOCS OFF)

# Sürükle-bırakta dosya yolunu almak için yeni WebView2 SDK (ICoreWebView2File) gerekir.
# webview kendi sürümünü önbelleğe yazdığı için FORCE ile sabitlenir.
set(WEBVIEW_MSWEBVIEW2_VERSION "1.0.2739.15" CACHE STRING "MS WebView2 version" FORCE)

FetchContent_Declare(webview
        GIT_REPOSITORY https://github.com/webview/webview
        GIT_TAG 0.12.0)

FetchContent_Declare(asio
        GIT_REPOSITORY https://github.com/chriskohlhoff/asio.git
        GIT_TAG master)

FetchContent_MakeAvailable(webview asio)

# WIN32: konsol penceresi açılmaz
add_executable(Yerelkesif WIN32
        main.cpp
        settings/AppSettings.cpp
        AppController.cpp
        network/ClientSocket.cpp
        network/FinderClient.cpp
        network/FinderServer.cpp
        network/PeerConnection.cpp
        network/ServerSocket.cpp
        common/Text.cpp
        ui/MainWindow.cpp
        ui/NativeFiles.cpp
        ui/UiBridge.cpp
        app_resources.rc)

# MinGW windres, .rc içindeki ICON dosyasını her zaman Ninja bağımlılığına yazmıyor.
# İkon değişince RC nesnesi yeniden derlensin ve yeni ikon EXE'ye gerçekten gömülsün.
set_source_files_properties(app_resources.rc PROPERTIES
        OBJECT_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/Yerelkesif.ico")

target_include_directories(Yerelkesif PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${asio_SOURCE_DIR}/include)

# WIN32_LEAN_AND_MEAN: asio winsock2'yi kendisi ekler, windows.h'nin winsock.h'yi çekmesi çakışma yaratır.
# webview bu makroyu koşulsuz tanımlar; boş değerle tanımlamak yeniden tanım uyarısını önler.
target_compile_definitions(Yerelkesif PRIVATE
        UNICODE
        _UNICODE
        NOMINMAX
        WIN32_LEAN_AND_MEAN=
        ASIO_STANDALONE
        _WIN32_WINNT=0x0A00)

target_link_libraries(Yerelkesif PRIVATE
        webview::core
        ws2_32
        mswsock
        ole32
        uuid
        shell32
        shlwapi
        comdlg32
        dwmapi
        advapi32
        gdi32
        user32)

# MinGW: çalışma zamanı DLL'leri olmadan da açılsın
if(MINGW)
    target_link_options(Yerelkesif PRIVATE
            -static -static-libgcc -static-libstdc++)

    # Link the static archive explicitly. A bare `winpthread` can resolve to
    # MinGW's DLL import library, leaving libwinpthread-1.dll as a runtime
    # dependency even when the rest of the C++ runtime is linked statically.
    execute_process(
            COMMAND ${CMAKE_CXX_COMPILER} -print-file-name=libwinpthread.a
            OUTPUT_VARIABLE WINPTHREAD_STATIC_LIBRARY
            OUTPUT_STRIP_TRAILING_WHITESPACE
            RESULT_VARIABLE WINPTHREAD_LOOKUP_RESULT)
    if(WINPTHREAD_LOOKUP_RESULT EQUAL 0 AND EXISTS "${WINPTHREAD_STATIC_LIBRARY}")
        target_link_libraries(Yerelkesif PRIVATE "${WINPTHREAD_STATIC_LIBRARY}")
    else()
        message(FATAL_ERROR "MinGW static libwinpthread.a was not found")
    endif()
endif()
