#include "phone_session.h"
#include <cstdlib>
#include <iostream>
using namespace moto::ble;
using phone_nav::Session;
int failures = 0;
#define CHECK(x) do { if (!(x)) { std::cerr << __LINE__ << ": " #x "\n"; ++failures; } } while (false)

std::vector<Bytes> wire(const Message& m, uint16_t seq, uint8_t flags = 0) {
  auto p = encode_message(m); CHECK(p.ok());
  auto f = fragment_message(message_type(m), seq, ByteView(p.value), 20, flags);
  CHECK(f.ok()); return f.value;
}
void feed(Session& s, const std::vector<Bytes>& frames, uint64_t now) {
  for (const auto& f : frames) s.receive(ByteView(f), now, 20);
}
void handshake(Session& s, uint32_t id, uint64_t now) {
  ConnectionStatus c;
  c.session_id = id; c.capabilities = 255; c.max_frame_size = 20;
  c.state = ConnectionState::Starting;
  feed(s, wire(Message{c}, 1), now);
  CHECK(!s.ready); CHECK(!s.outbound.empty());
  Reassembler rx;
  ReassemblyResult reply;
  for (auto& f : s.outbound) reply = rx.push(ByteView(f), now);
  CHECK(reply.complete());
  auto decoded = decode_message(reply.message.type, ByteView(reply.message.payload));
  CHECK(decoded.ok());
  const auto status = std::get<ConnectionStatus>(decoded.value);
  CHECK(status.role == EndpointRole::Device); CHECK(status.session_id == id);
  CHECK((status.capabilities & 87) == 87);
  s.outbound.clear();
  c.state = ConnectionState::Ready;
  feed(s, wire(Message{c}, 2), now + 10);
  CHECK(s.ready); CHECK(!s.outbound.empty()); s.outbound.clear();
}
int main() {
  Session s;
  NavigationSnapshot n; n.state = NavigationState::Navigating;
  n.flags = NavigationHasFix | NavigationHasRouteView | NavigationHasNextManeuver;
  n.route_token = 42; n.route_generation = 1;
  feed(s, wire(Message{n}, 3), 1); CHECK(!s.hasSnapshot);
  handshake(s, 123, 10);
  feed(s, wire(Message{n}, 3), 30); CHECK(s.hasSnapshot); CHECK(!s.routeVisible());
  RouteGeometry g;
  g.route_token = 42; g.route_generation = 1; g.total_point_count = 4;
  g.chunk_count = 2; g.view_origin = {34670000, 112460000};
  g.points = {{34670000,112460000},{34671000,112460000}};
  feed(s, wire(Message{g}, 4, AckRequested), 40); CHECK(!s.routeVisible());
  s.outbound.clear();
  g.chunk_index = 1; g.first_point_index = 2;
  g.points = {{34672000,112460000},{34673000,112460000}};
  auto lastChunk = wire(Message{g}, 5, AckRequested);
  feed(s, lastChunk, 50); CHECK(s.routeVisible()); CHECK(s.route.points.size() == 4);
  s.outbound.clear();
  feed(s, wire(Message{Heartbeat{123,60,0}}, 6), 60);
  feed(s, lastChunk, 70); CHECK(!s.outbound.empty()); CHECK(s.routeVisible());
  s.outbound.clear();
  n.route_generation = 2;
  feed(s, wire(Message{n}, 7), 80); CHECK(!s.routeVisible());
  auto corrupted = wire(Message{n}, 8); corrupted[0].back() ^= 1;
  feed(s, corrupted, 90); CHECK(s.snapshot.route_generation == 2);
  CHECK(s.selectPage(DisplayPage::Speed, 100)); CHECK(!s.outbound.empty());
  s.outbound.clear();
  s.tick(3200); CHECK(s.lost); CHECK(!s.ready); CHECK(!s.hasSnapshot); CHECK(s.route.points.empty());
  handshake(s, 456, 3300); CHECK(s.sessionId() == 456); CHECK(s.map.roads.empty());
  // Starting again clears old navigation even when numbering restarts at 1.
  feed(s, wire(Message{n}, 3), 3350); CHECK(s.hasSnapshot);
  handshake(s, 789, 3400); CHECK(!s.hasSnapshot); CHECK(s.route.points.empty());
  feed(s, wire(Message{Heartbeat{456,3410,0}}, 3), 3410);
  s.tick(6411); CHECK(s.lost); // Old-session heartbeat does not keep link alive.
  handshake(s, 999, 6500);
  MapScene m; m.scene_revision = 1; m.radius_m = 500;
  m.view_origin = {34670000,112460000};
  feed(s, wire(Message{m}, 3, AckRequested), 6510); CHECK(s.map.scene_revision == 1);
  s.reset(); CHECK(!s.ready); CHECK(s.map.scene_revision == 0); CHECK(s.outbound.empty());
  // Heartbeats cannot keep an old location/speed/turn alive indefinitely.
  handshake(s, 1111, 7000);
  n.speed_deci_kph = 240; n.instruction = "DEMO · 测试导航";
  feed(s, wire(Message{n}, 3), 7020);
  for (uint16_t sequence = 4; sequence <= 15; ++sequence) {
    const uint64_t now = 7020 + (sequence - 3) * 1000;
    feed(s, wire(Message{Heartbeat{1111,static_cast<uint32_t>(now),0}}, sequence), now);
    s.tick(now); s.outbound.clear();
  }
  CHECK(s.ready); CHECK(!s.lost); CHECK(s.hasSnapshot);
  CHECK(!(s.snapshot.flags & NavigationHasFix));
  CHECK(!(s.snapshot.flags & NavigationHasNextManeuver));
  CHECK(s.snapshot.flags & NavigationGnssStale); CHECK(s.snapshot.speed_deci_kph == 0);
  CHECK(s.snapshot.instruction.rfind("DEMO", 0) == 0);
  feed(s, wire(Message{n}, 16), 19100);
  CHECK(s.snapshot.flags & NavigationHasFix); CHECK(s.snapshot.speed_deci_kph == 240);
  // Geometry arrives before its snapshot: retain the current visible generation.
  Session atomic; handshake(atomic, 2222, 20000);
  n.route_generation = 1; feed(atomic, wire(Message{n}, 3), 20020);
  g.chunk_index = 0; g.chunk_count = 1; g.first_point_index = 0;
  g.total_point_count = 2; g.route_generation = 1;
  feed(atomic, wire(Message{g}, 4, AckRequested), 20030);
  CHECK(atomic.routeVisible());
  g.route_generation = 2;
  feed(atomic, wire(Message{g}, 5, AckRequested), 20040);
  CHECK(atomic.routeVisible()); CHECK(atomic.route.route_generation == 1);
  n.route_generation = 2; feed(atomic, wire(Message{n}, 6), 20050);
  CHECK(atomic.routeVisible()); CHECK(atomic.route.route_generation == 2);
  atomic.reset(); CHECK(atomic.route.points.empty());
  std::cout << (failures ? "FAILED" : "All phone session checks passed") << '\n';
  return failures ? EXIT_FAILURE : EXIT_SUCCESS;
}
