#include "AppSettings.h"

#include <fstream>
#include <iterator>
#include <objbase.h>

#include <windows.h>
#include <shlobj.h>

#include "common/Text.h"

namespace {

std::filesystem::path SettingsPath() {
    PWSTR folder = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, KF_FLAG_CREATE, nullptr, &folder))) {
        std::filesystem::path path = folder;
        CoTaskMemFree(folder);
        return path / L"Yerelkesif" / L"settings.ini";
    }
    return std::filesystem::temp_directory_path() / L"Yerelkesif-settings.ini";
}

std::filesystem::path DefaultDownloadDirectory() {
    PWSTR folder = nullptr;
    std::filesystem::path base = std::filesystem::temp_directory_path();
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Downloads, 0, nullptr, &folder))) {
        base = folder;
        CoTaskMemFree(folder);
    }
    return base / L"Yerelkesif";
}

}  // namespace

AppSettings LoadAppSettings() {
    AppSettings settings;
    settings.downloadDirectory = DefaultDownloadDirectory();
    wchar_t usernameBuffer[257]{};
    DWORD usernameLength = static_cast<DWORD>(std::size(usernameBuffer));
    if (GetUserNameW(usernameBuffer, &usernameLength) && usernameLength > 1)
        settings.username = Utf8FromWide(std::wstring_view(usernameBuffer, usernameLength - 1));
    std::ifstream input(SettingsPath(), std::ios::binary);
    std::string username, directory;
    if (std::getline(input, username) && std::getline(input, directory)) {
        if (!username.empty()) settings.username = std::move(username);
        if (!directory.empty()) settings.downloadDirectory = PathFromUtf8(directory);
    }
    return settings;
}

bool SaveAppSettings(const AppSettings& settings) {
    const auto path = SettingsPath();
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    if (ec) return false;
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) return false;
    output << settings.username << '\n' << Utf8FromWide(settings.downloadDirectory.native()) << '\n';
    return static_cast<bool>(output);
}
