#include "FinderClient.h"

#include <chrono>
#include <cstdlib>
#include <memory>
#include <fstream>
#include <filesystem>
#include <algorithm>
#include <windows.h>
#include <objbase.h>
#include <string_view>

#include "common/Logger.h"
#include "Protocol.h"
#include "common/Text.h"

FinderClient::FinderClient(asio::io_context& io, unsigned short port)
    : m_socket(io), m_broadcast(asio::ip::address_v4::broadcast(), port), m_timer(io) {
    std::error_code ec;
    m_socket.open(asio::ip::udp::v4(), ec);
    if (!ec) m_socket.set_option(asio::socket_base::broadcast(true), ec);
    if (ec) LOG() << "ERR -> FinderClient soketi açılamadı: " << ec.message();
}

void FinderClient::start() {
    if (m_username.empty()) m_username = getSystemUsername();
    m_identity = loadIdentity();
    Find();
}

void FinderClient::Find() {
    send(std::string(Protocol::kDiscoverPrefix) + ":" + m_username + ":" + m_identity, m_broadcast);

    m_timer.expires_after(std::chrono::milliseconds(500));
    m_timer.async_wait([this](const std::error_code& ec) {
        if (!ec) Find();
    });
}

void FinderClient::Match(const asio::ip::udp::endpoint& requester) {
    // Yanıt her zaman keşif portuna gider; istekteki kaynak port geçici olabilir
    const asio::ip::udp::endpoint reply(requester.address(), m_broadcast.port());
    send(std::string(Protocol::kMatchPrefix) + ":" + m_username + ":" + m_identity, reply);
}

void FinderClient::Probe(const std::string& ip) {
    // io thread'inde istisna dönmemeli: geçersiz adres sessizce reddedilir
    std::error_code ec;
    const auto address = asio::ip::make_address(ip, ec);
    if (ec) {
        LOG() << "ERR -> Geçersiz keşif adresi: " << ip;
        return;
    }
    send(std::string(Protocol::kDiscoverPrefix) + ":" + m_username + ":" + m_identity,
         asio::ip::udp::endpoint(address, m_broadcast.port()));
}

std::string FinderClient::loadIdentity() {
    const char* localAppData = std::getenv("LOCALAPPDATA");
    std::filesystem::path file = localAppData && *localAppData
        ? std::filesystem::path(localAppData) / L"Yerelkesif" / L"device-id.txt"
        : std::filesystem::temp_directory_path() / L"Yerelkesif-device-id.txt";
    std::error_code ec;
    std::filesystem::create_directories(file.parent_path(), ec);
    std::ifstream in(file, std::ios::binary);
    std::string id;
    if (in && std::getline(in, id) && !id.empty()) return id;

    GUID guid{};
    if (FAILED(CoCreateGuid(&guid))) return "device-" + std::to_string(GetTickCount64());
    wchar_t value[39]{};
    StringFromGUID2(guid, value, 39);
    id = Utf8FromWide(std::wstring_view(value));
    id.erase(std::remove_if(id.begin(), id.end(), [](char c) { return c == '{' || c == '}' || c == '-'; }), id.end());
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    if (out) out << id;
    return id;
}

void FinderClient::send(const std::string& text, const asio::ip::udp::endpoint& to) {
    auto packet = std::make_shared<const std::string>(text);
    m_socket.async_send_to(asio::buffer(*packet), to,
        [packet](const std::error_code& ec, std::size_t) {
            if (ec) LOG() << "ERR -> Keşif paketi gönderilemedi: " << ec.message();
        });
}

std::string FinderClient::getSystemUsername() {
    // GetUserNameW, oturum açmış kullanıcının adını UTF-16 olarak verir (Türkçe karakterler dahil)
    constexpr DWORD kBufferSize = 257;
    wchar_t buffer[kBufferSize];
    DWORD size = kBufferSize;
    if (GetUserNameW(buffer, &size) && size > 1) {
        return Utf8FromWide(std::wstring_view(buffer, size - 1));
    }

    if (const char* envUser = std::getenv("USERNAME"); envUser && *envUser) {
        return envUser;
    }
    return "UnknownUser";
}
