#pragma once

#include <filesystem>
#include <string>

struct AppSettings {
    std::string username;
    std::filesystem::path downloadDirectory;
};

AppSettings LoadAppSettings();
bool SaveAppSettings(const AppSettings& settings);
