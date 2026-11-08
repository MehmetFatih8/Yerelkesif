#include "AppController.h"

#include <chrono>
#include <algorithm>
#include <exception>

#include "common/Logger.h"
#include "network/Protocol.h"
#include "common/Text.h"
#include "settings/AppSettings.h"

AppController::AppController()
    : m_work(asio::make_work_guard(m_io)),
      m_finderClient(m_io, Protocol::kDiscoveryPort) {
    auto net = std::make_shared<NetEvents>();
    net->onMatch = [this](const std::string& ip, const std::string& name, const std::string& id) { onMatch(ip, name, id); };
    net->onPeerClosed = [this](const std::string& ip) { onPeerClosed(ip); };
    net->onMessage = [this](const std::string& ip, const std::string& text) {
        if (m_ui.onMessage) {
            const auto found = m_identityByIp.find(ip);
            m_ui.onMessage(found == m_identityByIp.end() ? ip : found->second, text);
        }
    };
    net->onTransfer = [this](const TransferInfo& info) {
        if (m_ui.onTransfer) {
            auto translated = info;
            const auto found = m_identityByIp.find(info.ip);
            if (found == m_identityByIp.end()) return; // Eski IP'de kopan aktarımı yeni oturuma hata olarak yansıtma
            translated.ip = found->second;
            if (!info.incoming && info.stage == TransferInfo::Stage::Done) {
                auto& pending = m_pendingFiles[found->second];
                const auto file = std::find_if(pending.begin(), pending.end(), [&](const auto& path) {
                    return path.remoteName == info.fileName;
                });
                if (file != pending.end()) pending.erase(file);
                if (pending.empty()) m_pendingFiles.erase(found->second);
            } else if (!info.incoming && info.stage == TransferInfo::Stage::Failed && info.batchEnd) {
                m_pendingFiles.erase(found->second);
            }
            m_ui.onTransfer(translated);
        }
    };
    net->onNotice = [this](const std::string& ip, const std::string& text) {
        if (m_ui.onNotice) {
            const auto found = m_identityByIp.find(ip);
            m_ui.onNotice(found == m_identityByIp.end() ? ip : found->second, text);
        }
    };
    m_net = std::move(net);
}

AppController::~AppController() {
    stop();
}

void AppController::setUiEvents(UiEvents events) {
    m_ui = std::move(events);
}

void AppController::start() {
    if (m_running) return;

    const auto settings = LoadAppSettings();
    if (!settings.username.empty()) m_finderClient.setUsername(settings.username);
    PeerConnection::setDownloadDirectory(settings.downloadDirectory);

    m_server = std::make_unique<ServerSocket>(m_io, m_net, Protocol::kMainPort, Protocol::kFilePort);
    m_server->start();

    m_finderServer = std::make_unique<FinderServer>(m_io, Protocol::kDiscoveryPort, m_finderClient, m_net);
    m_finderServer->start();
    m_finderClient.start();

    m_thread = std::thread([this] {
        try {
            m_io.run();
        } catch (const std::exception& e) {
            LOG() << "ERR -> Ağ thread'i durdu: " << e.what();
        }
    });
    m_running = true;
    LOG() << "INFO -> Ağ servisleri başlatıldı.";
}

bool AppController::saveSettings(AppSettings settings) {
    if (settings.username.empty() || settings.downloadDirectory.empty()) return false;
    std::error_code ec;
    std::filesystem::create_directories(settings.downloadDirectory, ec);
    if (ec || !SaveAppSettings(settings)) return false;
    asio::post(m_io, [this, settings = std::move(settings)] {
        m_finderClient.setUsername(settings.username);
        PeerConnection::setDownloadDirectory(settings.downloadDirectory);
    });
    return true;
}

void AppController::stop() {
    if (!m_running) return;
    m_running = false;

    m_work.reset();
    m_io.stop();
    if (m_thread.joinable()) m_thread.join();

    // io thread durduğu için nesneler artık yalnızca bu thread'den erişiliyor
    m_clients.clear();
    m_devices.clear();
    m_identityByIp.clear();
    m_ipByIdentity.clear();
    m_pendingFiles.clear();
    m_finderServer.reset();
    m_server.reset();
}

std::string AppController::validateIp(const std::string& ip) {
    std::error_code ec;
    const auto address = asio::ip::make_address(ip, ec);
    if (ec || !address.is_v4() || address.is_unspecified() || address.is_multicast() || address.is_loopback()) {
        return "Geçerli bir IPv4 adresi girin.";
    }
    return {};
}

void AppController::addDevice(const std::string& ip) {
    asio::post(m_io, [this, ip] {
        m_finderClient.Probe(ip);

        // Cihaz yanıt vermezse arayüzü bilgilendir
        auto timer = std::make_shared<asio::steady_timer>(m_io, std::chrono::seconds(5));
        timer->async_wait([this, ip, timer](const std::error_code& ec) {
            if (!ec && !m_identityByIp.contains(ip) && m_ui.onNotice) {
                m_ui.onNotice(ip, "Cihaz yanıt vermedi: " + ip);
            }
        });
    });
}

void AppController::sendMessage(const std::string& ip, const std::string& text) {
    asio::post(m_io, [this, id = ip, text] {
        const auto address = m_ipByIdentity.find(id);
        const auto found = address == m_ipByIdentity.end() ? m_clients.end() : m_clients.find(address->second);
        if (found == m_clients.end()) {
            if (m_ui.onNotice) m_ui.onNotice(id, "Mesaj gönderilemedi: cihaz bağlı değil.");
            return;
        }
        found->second->sendMessage(text);
    });
}

void AppController::sendFiles(const std::string& ip, std::vector<FileToSend> files) {
    asio::post(m_io, [this, id = ip, files = std::move(files)] {
        const auto address = m_ipByIdentity.find(id);
        const std::string ip = address == m_ipByIdentity.end() ? id : address->second;
        const auto found = m_clients.find(ip);
        if (found == m_clients.end()) {
            reportTransferFailure(id, "Cihaz bağlı değil.");
            return;
        }
        auto& pending = m_pendingFiles[id];
        pending.insert(pending.end(), files.begin(), files.end());
        found->second->sendFiles(files);
    });
}

void AppController::onMatch(const std::string& ip, const std::string& name, const std::string& id) {
    if (const auto old = m_ipByIdentity.find(id); old != m_ipByIdentity.end() && old->second != ip) {
        const std::string oldIp = old->second;
        m_identityByIp.erase(oldIp);
        m_ipByIdentity[id] = ip;
        if (const auto client = m_clients.find(oldIp); client != m_clients.end()) {
            client->second->close();
            m_clients.erase(client);
        }
    }
    m_devices[id] = name;
    m_identityByIp[ip] = id;
    m_ipByIdentity[id] = ip;
    if (m_ui.onDeviceFound) m_ui.onDeviceFound(id, name, ip);
    connectTo(ip);
}

void AppController::onPeerClosed(const std::string& ip) {
    std::string id;
    if (const auto found = m_identityByIp.find(ip); found != m_identityByIp.end()) {
        id = found->second;
        m_identityByIp.erase(found);
    }
    if (const auto found = m_clients.find(ip); found != m_clients.end()) {
        found->second->close();
        m_clients.erase(found);
    }
    if (!id.empty() && m_ui.onDeviceStatus) m_ui.onDeviceStatus(id, false);
}

// Cihaz zaten bağlıysa yeniden bağlanılmaz; bağlantı kurulamadıysa bir sonraki keşifte tekrar denenir
void AppController::connectTo(const std::string& ip) {
    if (const auto found = m_clients.find(ip); found != m_clients.end()) {
        if (found->second->isConnected()) return;
        found->second->close();
    }

    auto client = ClientSocket::create(m_io, m_net, ip);
    m_clients[ip] = client;
    const auto id = m_identityByIp.contains(ip) ? m_identityByIp[ip] : ip;
    client->connect(Protocol::kMainPort, Protocol::kFilePort, [this, id, ip](bool ok) {
        const auto currentIp = m_ipByIdentity.find(id);
        if (currentIp != m_ipByIdentity.end() && currentIp->second != ip) return;
        if (m_ui.onDeviceStatus) m_ui.onDeviceStatus(id, ok);
        if (ok) {
            LOG() << "INFO -> " << ip << " ile bağlantı kuruldu.";
            if (const auto pending = m_pendingFiles.find(id); pending != m_pendingFiles.end() && !pending->second.empty()) {
                m_clients[ip]->sendFiles(pending->second);
            }
        } else {
            LOG() << "ERR -> " << ip << " ile bağlantı kurulamadı.";
        }
    });
}

void AppController::reportTransferFailure(const std::string& ip, const std::string& reason) {
    if (!m_ui.onTransfer) return;

    TransferInfo info;
    info.ip = ip;
    info.stage = TransferInfo::Stage::Failed;
    info.batchEnd = true;
    info.detail = reason;
    m_ui.onTransfer(info);
}
