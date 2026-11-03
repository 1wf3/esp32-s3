#pragma once
#include "moto/ble_protocol/ble_protocol.hpp"
#include <algorithm>
#include <deque>

// Hardware-independent display endpoint. All methods run on the Arduino loop
// thread; BLE callbacks only enqueue frames. The phone owns navigation facts.
namespace phone_nav {
using namespace moto::ble;
class Session {
 public:
  static constexpr uint32_t capabilities = CapabilityNavigation |
      CapabilityRouteGeometry | CapabilityTraffic | CapabilityTouchCommands |
      CapabilityCommandAck | CapabilityMapScene;
  NavigationSnapshot snapshot{};
  RouteGeometry route{};
  MapScene map{};
  bool hasSnapshot = false;
  bool ready = false;
  bool lost = false;
  std::deque<Bytes> outbound;
  const char* error = "";
  uint32_t receivedSnapshots = 0;

  void reset() {
    snapshot = {}; route = {}; map = {}; staging_ = {}; pendingRoute_ = {};
    hasSnapshot = ready = lost = false;
    session_ = peerCaps_ = 0; awaitingReady_ = false;
    nextChunk_ = 0; lastRx_ = lastHeartbeat_ = lastSnapshot_ = 0;
    commandPending_ = false; commandId_ = 0; ackCache_.clear();
    outbound.clear(); sequence_.reset(); rx_.reset(); handshakeRx_.reset();
    error = "";
  }

  void receive(ByteView bytes, uint64_t now, uint16_t localFrameSize) {
    const auto frame = decode_frame(bytes);
    if (!frame.ok()) { error = to_string(frame.error); return; }
    // Reliable route/map retransmits may arrive after newer heartbeats. Check
    // byte-identical fragments against bounded committed ACK history first.
    if (ready && !lost) for (const auto& cached : ackCache_) {
      const auto& f = frame.value;
      const auto& m = cached.message;
      if (f.sequence != m.sequence || f.type != m.type || f.message_length != m.payload.size() ||
          (f.flags & kApplicationFrameFlags) != m.flags ||
          f.fragment_offset + f.payload.size() > m.payload.size()) continue;
      if (std::equal(f.payload.begin(), f.payload.end(), m.payload.begin() + f.fragment_offset)) {
        lastRx_ = now;
        if (f.flags & FrameEnd) send(Message{cached.ack});
        return;
      }
    }
    // A new session can restart numbering. Reassemble handshake separately
    // before checking the session, without weakening data-message ordering.
    const bool handshake = frame.value.type == MessageType::ConnectionStatus;
    auto& assembler = handshake ? handshakeRx_ : rx_;
    const auto result = assembler.push(bytes, now);
    if (result.state == ReassemblyState::Error) {
      error = to_string(result.error); return;
    }
    if (result.state == ReassemblyState::DuplicateMessage) {
      return;
    }
    if (!result.complete()) {
      if (ready && !handshake) lastRx_ = now;
      return;
    }
    const auto decoded = decode_message(result.message.type, ByteView(result.message.payload));
    if (!decoded.ok()) { error = to_string(decoded.error); return; }
    if (handshake) {
      connection(std::get<ConnectionStatus>(decoded.value), now, localFrameSize);
      // Handshake retries can reuse bytes/sequence; two Ready replies are vital.
      handshakeRx_.reset();
      return;
    }
    if (!ready || lost) return;
    AckStatus status = AckStatus::Ok;
    auto& value = decoded.value;
    if (auto* h = std::get_if<Heartbeat>(&value)) {
      if (h->session_id != session_) return;
    } else if (auto* s = std::get_if<NavigationSnapshot>(&value)) {
      snapshot = *s; hasSnapshot = true; lastSnapshot_ = now; ++receivedSnapshots;
      if (pendingRoute_.route_token == s->route_token &&
          pendingRoute_.route_generation == s->route_generation &&
          (s->flags & NavigationHasRouteView)) {
        route = std::move(pendingRoute_); pendingRoute_ = {};
      }
      if (!s->route_token || s->state == NavigationState::Idle ||
          s->state == NavigationState::Acquiring || s->state == NavigationState::Planning) {
        route = {}; staging_ = {}; pendingRoute_ = {}; nextChunk_ = 0;
      }
    } else if (auto* g = std::get_if<RouteGeometry>(&value)) {
      status = commitRoute(*g);
    } else if (auto* m = std::get_if<MapScene>(&value)) {
      if (!(peerCaps_ & CapabilityMapScene)) status = AckStatus::Unsupported;
      else if (!map.scene_revision || m->scene_revision > map.scene_revision) map = *m;
      else status = AckStatus::Duplicate;
    } else if (auto* t = std::get_if<TrafficDeviation>(&value)) {
      if (hasSnapshot && t->route_token == snapshot.route_token &&
          t->route_generation == snapshot.route_generation) {
        if (t->flags & RouteInvalidated) {
          route = {}; staging_ = {}; pendingRoute_ = {}; nextChunk_ = 0;
          snapshot.flags &= ~NavigationHasRouteView;
        }
        if (t->flags & Rerouting) snapshot.state = NavigationState::Rerouting;
      }
    } else if (auto* ack = std::get_if<Ack>(&value)) {
      if (commandPending_ && ack->acknowledged_sequence == commandSequence_ &&
          ack->command_id == commandId_) {
        commandPending_ = false;
        if (ack->status != AckStatus::Ok && ack->status != AckStatus::Duplicate)
          error = "PAGE COMMAND REJECTED";
      }
    } else status = AckStatus::Unsupported;
    lastRx_ = now;
    if (result.message.flags & AckRequested) {
      Ack ack{result.message.sequence, status, 0};
      if (ackCache_.size() == 4) ackCache_.pop_front();
      ackCache_.push_back({result.message, ack});
      send(Message{ack});
    }
  }

  void tick(uint64_t now) {
    (void)rx_.expire(now); (void)handshakeRx_.expire(now);
    if ((ready || awaitingReady_) && now - lastRx_ >= 3000) {
      ready = false; awaitingReady_ = false; lost = true;
      hasSnapshot = false; route = {}; map = {}; staging_ = {}; pendingRoute_ = {};
      rx_.reset(); outbound.clear(); commandPending_ = false;
      return;
    }
    if (!ready) return;
    // A transport heartbeat does not prove that navigation facts are fresh.
    // Normal senders publish snapshots every three seconds. Stop displaying
    // old speed/turn guidance when only heartbeats keep arriving.
    if (hasSnapshot && (snapshot.flags & NavigationHasFix) && now - lastSnapshot_ >= 10000) {
      snapshot.flags &= ~(NavigationHasFix | NavigationHasNextManeuver);
      snapshot.flags |= NavigationGnssStale;
      snapshot.speed_deci_kph = 0;
      snapshot.instruction = snapshot.instruction.rfind("DEMO", 0) == 0 ?
          "DEMO · 等待新的手机定位数据" : "等待新的手机定位数据";
    }
    if (now - lastHeartbeat_ >= heartbeatInterval_ && outbound.empty()) {
      send(Message{Heartbeat{session_, static_cast<uint32_t>(now), 0}});
      lastHeartbeat_ = now;
    }
    if (commandPending_ && outbound.empty() && now - commandSentAt_ >= 750) {
      if (commandRetries_++ < 2) {
        for (const auto& f : commandFrames_) outbound.push_back(f);
        commandSentAt_ = now;
      } else { commandPending_ = false; error = "PAGE ACK TIMEOUT"; }
    }
  }

  bool selectPage(DisplayPage page, uint64_t now) {
    if (!ready || commandPending_ || !(peerCaps_ & CapabilityTouchCommands)) return false;
    DeviceCommand command;
    command.kind = DeviceCommandKind::PageSelected;
    if (++commandId_ == 0) ++commandId_;
    command.command_id = commandId_; command.page = page;
    command.event_time_ms = static_cast<uint32_t>(now);
    const auto encoded = encode_message(Message{command});
    commandSequence_ = sequence_.next();
    const auto frames = fragment_message(MessageType::DeviceCommand, commandSequence_,
        ByteView(encoded.value), frameSize_, AckRequested | Urgent);
    if (!encoded.ok() || !frames.ok() || outbound.size() + frames.value.size() > 64) return false;
    commandFrames_ = frames.value;
    for (const auto& f : commandFrames_) outbound.push_back(f);
    commandPending_ = true; commandSentAt_ = now; commandRetries_ = 0;
    return true;
  }

  bool routeVisible() const {
    return ready && hasSnapshot && (snapshot.flags & NavigationHasRouteView) &&
        route.points.size() >= 2 && route.route_token == snapshot.route_token &&
        route.route_generation == snapshot.route_generation;
  }
  uint32_t sessionId() const { return session_; }
  uint16_t frameSize() const { return frameSize_; }

 private:
  Reassembler rx_{};
  Reassembler handshakeRx_{ReassemblerConfig{kDefaultMaxMessageSize, 1000, false}};
  SequenceGenerator sequence_{};
  uint32_t session_ = 0, peerCaps_ = 0;
  uint16_t frameSize_ = 20, heartbeatInterval_ = 1000;
  uint64_t lastRx_ = 0, lastHeartbeat_ = 0, lastSnapshot_ = 0;
  bool awaitingReady_ = false;
  RouteGeometry staging_{}, pendingRoute_{};
  uint16_t nextChunk_ = 0;
  struct CachedAck { ReassembledMessage message; Ack ack; };
  std::deque<CachedAck> ackCache_;
  bool commandPending_ = false;
  uint16_t commandId_ = 0, commandSequence_ = 0;
  uint8_t commandRetries_ = 0;
  uint64_t commandSentAt_ = 0;
  std::vector<Bytes> commandFrames_;

  void send(const Message& message) {
    const auto payload = encode_message(message);
    if (!payload.ok()) { error = to_string(payload.error); return; }
    const auto frames = fragment_message(message_type(message), sequence_.next(),
        ByteView(payload.value), frameSize_);
    if (!frames.ok()) { error = to_string(frames.error); return; }
    if (outbound.size() + frames.value.size() > 64) {
      error = "TX QUEUE FULL"; return;
    }
    for (const auto& frame : frames.value) outbound.push_back(frame);
  }

  void connection(const ConnectionStatus& c, uint64_t now, uint16_t localFrameSize) {
    if (c.role != EndpointRole::Phone || !c.session_id) return;
    if (c.minimum_version > 1 || c.maximum_version < 1) {
      reset(); session_ = c.session_id; frameSize_ = std::min<uint16_t>(localFrameSize, c.max_frame_size);
      ConnectionStatus reply; reply.role = EndpointRole::Device;
      reply.state = ConnectionState::Closing; reply.session_id = session_;
      reply.max_frame_size = frameSize_; send(Message{reply});
      error = "PROTOCOL VERSION"; return;
    }
    if (c.state == ConnectionState::Starting) {
      if (c.session_id != session_ || !awaitingReady_) {
        reset(); session_ = c.session_id;
        peerCaps_ = capabilities & c.capabilities;
        frameSize_ = std::min<uint16_t>(localFrameSize, c.max_frame_size);
        heartbeatInterval_ = 1000;
      }
      awaitingReady_ = true; ready = false; lost = false;
    } else if (c.state == ConnectionState::Ready) {
      if (!awaitingReady_ || c.session_id != session_ ||
          c.max_frame_size != frameSize_ || c.heartbeat_interval_ms != heartbeatInterval_ ||
          (c.capabilities & capabilities) != peerCaps_) return;
      ready = true; lost = false;
    } else {
      if (c.session_id == session_) reset();
      return;
    }
    lastRx_ = lastHeartbeat_ = now;
    ConnectionStatus reply;
    reply.role = EndpointRole::Device; reply.state = ConnectionState::Ready;
    reply.session_id = session_; reply.capabilities = peerCaps_;
    reply.max_frame_size = frameSize_; reply.heartbeat_interval_ms = heartbeatInterval_;
    send(Message{reply});
  }

  AckStatus commitRoute(const RouteGeometry& g) {
    if (g.coordinate_system != CoordinateSystem::Gcj02) return AckStatus::Unsupported;
    if (g.chunk_index == 0) {
      if (route.route_token == g.route_token && route.route_generation > g.route_generation)
        return AckStatus::InvalidState;
      if (pendingRoute_.route_token == g.route_token && pendingRoute_.route_generation > g.route_generation)
        return AckStatus::InvalidState;
      if (staging_.route_token == g.route_token && staging_.route_generation > g.route_generation)
        return AckStatus::InvalidState;
      staging_ = g; staging_.points.clear(); nextChunk_ = 0;
    }
    if (g.chunk_index != nextChunk_ || g.first_point_index != staging_.points.size() ||
        g.route_token != staging_.route_token || g.route_generation != staging_.route_generation ||
        g.chunk_count != staging_.chunk_count || g.total_point_count != staging_.total_point_count ||
        !(g.view_origin == staging_.view_origin)) {
      staging_ = {}; nextChunk_ = 0; return AckStatus::InvalidState;
    }
    staging_.points.insert(staging_.points.end(), g.points.begin(), g.points.end());
    if (staging_.points.size() > kMaxRoutePointsPerChunk) {
      staging_ = {}; nextChunk_ = 0; return AckStatus::Failed;
    }
    ++nextChunk_;
    if (nextChunk_ == g.chunk_count) {
      if (staging_.points.size() != g.total_point_count) return AckStatus::Failed;
      // Keep the visible generation until the matching navigation snapshot arrives.
      if (hasSnapshot && snapshot.route_token == staging_.route_token &&
          snapshot.route_generation == staging_.route_generation) route = std::move(staging_);
      else pendingRoute_ = std::move(staging_);
      staging_ = {}; nextChunk_ = 0;
    }
    return AckStatus::Ok;
  }
};
} // namespace phone_nav
