#include <windows.h>
#include <objbase.h>
#include <shellapi.h>
#include <shobjidl.h>

#include <exception>

#include "AppController.h"
#include "common/Logger.h"
#include "ui/MainWindow.h"
#include "ui/UiBridge.h"

int WINAPI WinMain(HINSTANCE instance, HINSTANCE, LPSTR, int) {
    Logger::init();

    // Pencere ve WebView2 fiziksel piksellerle çizilsin; Windows bulanık ölçeklemesin
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    // WebView2, dış pencereyle kullanılırken COM'un STA olarak başlatılmasını ister
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    SetCurrentProcessExplicitAppUserModelID(L"MehmetFatih8.Yerelkesif");

    // Explorer can cache the EXE's file icon separately from the running window icon.
    wchar_t executablePath[MAX_PATH]{};
    const DWORD pathLength = GetModuleFileNameW(nullptr, executablePath, MAX_PATH);
    if (pathLength > 0 && pathLength < MAX_PATH)
        SHChangeNotify(SHCNE_UPDATEITEM, SHCNF_PATHW, executablePath, nullptr);

    try {
        AppController app;
        MainWindow window(instance);
        UiBridge ui(window, app);
        ui.run();
    } catch (const std::exception& e) {
        LOG() << "ERR -> Yerelkeşif başlatılamadı: " << e.what();
        MessageBoxA(nullptr, e.what(), "Yerelkeşif", MB_ICONERROR);
        return 1;
    }

    return 0;
}
