#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLESecurity.h>
#include <atomic>
#include <esp_timer.h>
#include <TinyGPSPlus.h>
#include <Preferences.h>
#include <services/gatt/ble_svc_gatt.h>
#include "phone_renderer.h"
#include "wifi_mirror.h"

// 480x320 TFT wiring, ILI9488-compatible initialization, SPI with automatic DMA.
// VCC/LED->3V3; GND->GND; SCK12 MOSI11 MISO15 DC9 CS10 RESET14.
// ATGM336H TX -> GPIO17, UART1 / 9600 baud. No AMap API keys on device.
constexpr uint32_t kTftClockHz=40000000;
class NavLCD : public lgfx::LGFX_Device {
  lgfx::Bus_SPI bus_;
  lgfx::Panel_ILI9488 panel_;
 public:
  NavLCD() {
    auto b = bus_.config();
    b.spi_host = SPI2_HOST; b.spi_mode = 0; b.freq_write = kTftClockHz;
    b.freq_read = 500000; b.spi_3wire = false; b.use_lock = true; // Keep LovyanGFX automatic DMA enabled.
    b.pin_sclk = 12; b.pin_mosi = 11; b.pin_miso = 15; b.pin_dc = 9;
    bus_.config(b); panel_.setBus(&bus_);
    auto p = panel_.config(); p.pin_cs = 10; p.pin_rst = 14; p.pin_busy = -1;
    p.memory_width = p.panel_width = 320; p.memory_height = p.panel_height = 480;
    p.readable = false; p.bus_shared = false; p.invert = false;
    panel_.config(p); setPanel(&panel_);
  }
};

NavLCD lcd;
PhoneRenderer display(lcd);
WifiMirror mirror;
phone_nav::Session session;
BLEServer* server = nullptr;
BLECharacteristic* tx = nullptr;
BLECharacteristic* vehicleTx = nullptr;
BLECharacteristic* gpsDiagnosticsTx = nullptr;
BLECharacteristic* appearanceTx = nullptr;
TinyGPSPlus vehicleGps;
uint64_t lastVehicleUart=0;
portMUX_TYPE appearanceMux=portMUX_INITIALIZER_UNLOCKED;
HardwareSerial vehicleUart(1);
Preferences appearancePrefs;
QueueHandle_t appearanceQueue;
struct WifiCommand {char ssid[33];char password[64];};
QueueHandle_t wifiQueue;
uint8_t appearance[12]={1,1,1,0,0x3f,0xb6,0x6d,0x63,0xff,0xe7,0x51,0x8c};
struct AppearanceCommand {uint8_t bytes[12];};
void refreshVehicle(){
  uint8_t data[20]={1,0,0,0};
  const uint32_t age=vehicleGps.location.isValid()?std::min<uint64_t>(0xffffffffUL,vehicleGps.location.age()):0xffffffffUL;
  const bool fresh=vehicleGps.location.isValid()&&age<15000;
  data[1]=(fresh?1:0)|(vehicleGps.charsProcessed()>0&&uint64_t(esp_timer_get_time()/1000)-lastVehicleUart<5000?2:0);
  data[2]=vehicleGps.satellites.isValid()?std::min<uint32_t>(255,vehicleGps.satellites.value()):0;
  const uint16_t hdop=vehicleGps.hdop.isValid()?std::min<uint32_t>(65535,vehicleGps.hdop.value()):65535;
  const int32_t lat=vehicleGps.location.isValid()?lround(vehicleGps.location.lat()*1e7):0,lon=vehicleGps.location.isValid()?lround(vehicleGps.location.lng()*1e7):0;
  const uint16_t chars=std::min<uint32_t>(65535,vehicleGps.charsProcessed());
  memcpy(data+4,&hdop,2);memcpy(data+6,&age,4);memcpy(data+10,&lat,4);memcpy(data+14,&lon,4);memcpy(data+18,&chars,2);
  if(vehicleTx)vehicleTx->setValue(data,sizeof(data));
  // Independent 20-byte diagnostics keep the existing vehicle protocol compatible.
  uint8_t diagnostics[20]={1,0,0,0};
  const uint32_t counts[]={uint32_t(vehicleGps.charsProcessed()),uint32_t(vehicleGps.passedChecksum()),uint32_t(vehicleGps.failedChecksum()),uint32_t(vehicleGps.sentencesWithFix())};
  memcpy(diagnostics+4,counts,sizeof(counts));
  if(gpsDiagnosticsTx)gpsDiagnosticsTx->setValue(diagnostics,sizeof(diagnostics));
}
std::atomic<bool> connected{false}, encrypted{false}, overflow{false};
std::atomic<uint32_t> epoch{0};
std::atomic<uint16_t> attBudget{20};
std::atomic<int> notifyResult{0};
struct RxFrame { uint32_t epoch; uint64_t at; uint16_t length; uint8_t bytes[512]; };
QueueHandle_t rxQueue;
uint64_t clockMs() { return esp_timer_get_time() / 1000; }

class AppearanceEvents : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic* p) override {uint8_t applied[12];portENTER_CRITICAL(&appearanceMux);memcpy(applied,appearance,12);portEXIT_CRITICAL(&appearanceMux);p->setValue(applied,12);}
  void onWrite(BLECharacteristic* p) override {
    if(!connected||!encrypted)return;
    const auto value=p->getValue();
    if(value.length()!=12||value[0]!=1||uint8_t(value[1])>2||uint8_t(value[2])>2||value[3]!=0)return;
    AppearanceCommand command{};memcpy(command.bytes,value.c_str(),12);xQueueOverwrite(appearanceQueue,&command);
  }
};


class WifiEvents : public BLECharacteristicCallbacks {
 void onRead(BLECharacteristic* p) override {String ip=mirror.stationIp();String value=ip.length()?String("IP:")+ip:mirror.stationStatus();p->setValue(value.c_str());}
 void onWrite(BLECharacteristic* p) override {if(!connected||!encrypted)return;const auto v=p->getValue();if(v.length()<3||uint8_t(v[0])!=1)return;size_t n=uint8_t(v[1]),m=uint8_t(v[2]);if(n<1||n>32||m>63||v.length()!=3+n+m)return;WifiCommand command{};memcpy(command.ssid,v.c_str()+3,n);memcpy(command.password,v.c_str()+3+n,m);xQueueOverwrite(wifiQueue,&command);}
};

class ServerEvents : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    encrypted = false; attBudget = 20; ++epoch; connected = true;
  }
  void onDisconnect(BLEServer*) override {
    connected = false; encrypted = false; ++epoch;
  }
  void onMtuChanged(BLEServer*, ble_gap_conn_desc*, uint16_t mtu) override {
    attBudget = std::min<uint16_t>(512, mtu - 3);
  }
};
class SecurityEvents : public BLESecurityCallbacks {
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(ble_gap_conn_desc* p) override {
    encrypted = p->sec_state.encrypted;
    Serial.println(encrypted ? "BLE encrypted / bonded" : "BLE pairing failed");
    // The GATT layout is unchanged: do not invalidate Android service discovery on every pairing.
  }
};
class RxEvents : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* p) override {
    if (!connected || !encrypted) return;
    const auto bytes = p->getValue();
    if (bytes.length() < moto::ble::kFrameOverhead || bytes.length() > 512) return;
    RxFrame frame{}; frame.epoch = epoch; frame.at = clockMs(); frame.length = bytes.length();
    memcpy(frame.bytes, bytes.c_str(), frame.length);
    if (xQueueSend(rxQueue, &frame, 0) != pdTRUE) overflow = true;
  }
};
class TxEvents : public BLECharacteristicCallbacks {
  void onStatus(BLECharacteristic*, Status status, uint32_t) override {
    notifyResult = status == SUCCESS_NOTIFY ? 1 : -1;
  }
};

void setup() {
  Serial.begin(115200);
#if ARDUINO_USB_CDC_ON_BOOT && ARDUINO_USB_MODE
  Serial.setTxTimeoutMs(0); // Debug output must never stall BLE or frame rendering.
  Serial.setTxBufferSize(4096);
#endif
  delay(300);
  vehicleUart.setRxBufferSize(4096);
  vehicleUart.begin(9600,SERIAL_8N1,17,-1);
  wifiQueue=xQueueCreate(1,sizeof(WifiCommand));
  appearanceQueue=xQueueCreate(1,sizeof(AppearanceCommand));
  if(!appearanceQueue||!wifiQueue){Serial.println("FATAL: appearance queue allocation");while(true)delay(1000);}
  appearancePrefs.begin("nav-style",false);
  if(appearancePrefs.getBytesLength("value")==12)appearancePrefs.getBytes("value",appearance,12);
  if(appearance[0]!=1||appearance[1]>2||appearance[2]>2||appearance[3]!=0){uint8_t defaults[12]={1,1,1,0,0x3f,0xb6,0x6d,0x63,0xff,0xe7,0x51,0x8c};memcpy(appearance,defaults,12);}
  display.setAppearance(appearance);
  lcd.init(); lcd.setRotation(1); lcd.setColorDepth(24);
  if (!display.begin()) {
    Serial.println("FATAL: enable OPI PSRAM; display buffer unavailable");
    lcd.fillScreen(TFT_BLACK); lcd.setCursor(12, 12); lcd.print("ENABLE OPI PSRAM");
    while (true) delay(1000);
  }
  display.startBoot(uint32_t(clockMs()));
  display.render(session, false, false,uint32_t(clockMs()));
  if(!mirror.begin())Serial.println("Wi-Fi mirror initialization failed; BLE navigation retained");else Serial.println("WIFI MIRROR READY: MOTO SCREEN / TCP 8091");
  rxQueue = xQueueCreate(64, sizeof(RxFrame));
  if (!rxQueue) { Serial.println("FATAL: RX queue allocation"); while (true) delay(1000); }
  if (!BLEDevice::init("MOTO GPS TFT")) {
    Serial.println("FATAL: BLE initialization failed"); while (true) delay(1000);
  }
  BLEDevice::setMTU(185);
  BLEDevice::setSecurityCallbacks(new SecurityEvents());
  BLESecurity::setAuthenticationMode(true, false, true); // SC bonding / Just Works
  BLESecurity::setCapability(ESP_IO_CAP_NONE);
  BLESecurity::setInitEncryptionKey(); BLESecurity::setRespEncryptionKey();
  server = BLEDevice::createServer(); server->setCallbacks(new ServerEvents());
  server->advertiseOnDisconnect(true);
  auto* service = server->createService(moto::ble::kServiceUuid);
  auto* rx = service->createCharacteristic(moto::ble::kPhoneToDeviceUuid,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR |
      BLECharacteristic::PROPERTY_WRITE_ENC);
  rx->setCallbacks(new RxEvents());
  tx = service->createCharacteristic(moto::ble::kDeviceToPhoneUuid,
      BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ_ENC);
  tx->setCallbacks(new TxEvents());
  // NimBLE supplies CCCD automatically. READ_ENC requires encryption for
  // notifications/subscription security without exposing a Read property.
  vehicleTx=service->createCharacteristic("7e57a004-b50c-4b6a-9c57-40a54e8e1000",BLECharacteristic::PROPERTY_READ|BLECharacteristic::PROPERTY_READ_ENC);
  gpsDiagnosticsTx=service->createCharacteristic("7e57a006-b50c-4b6a-9c57-40a54e8e1000",BLECharacteristic::PROPERTY_READ|BLECharacteristic::PROPERTY_READ_ENC);
  refreshVehicle();
  appearanceTx=service->createCharacteristic("7e57a003-b50c-4b6a-9c57-40a54e8e1000",BLECharacteristic::PROPERTY_READ|BLECharacteristic::PROPERTY_READ_ENC|BLECharacteristic::PROPERTY_WRITE|BLECharacteristic::PROPERTY_WRITE_ENC);
  appearanceTx->setValue(appearance,sizeof(appearance));appearanceTx->setCallbacks(new AppearanceEvents());
  auto* wifiSettings=service->createCharacteristic("7e57a005-b50c-4b6a-9c57-40a54e8e1000",BLECharacteristic::PROPERTY_READ|BLECharacteristic::PROPERTY_READ_ENC|BLECharacteristic::PROPERTY_WRITE|BLECharacteristic::PROPERTY_WRITE_ENC);wifiSettings->setCallbacks(new WifiEvents());
  service->start();
  auto* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(moto::ble::kServiceUuid);
  advertising->setScanResponse(true); advertising->start();
  Serial.println("PHONE BLE NAV READY. Vehicle GPS UART17 / 9600; phone navigation unchanged.");
  Serial.println("Maler X source: https://github.com/mx3353672833-debug/moto-gps-waveshare");
  Serial.println("Serial n/s/c sends navigation/speed/compass page command to phone. Touch is not enabled.");
}

void loop() {
  static uint32_t activeEpoch = 0;
  static uint64_t lastRender = 0, lastTx = 0, lastLog = 0, perfAt = 0;
  static uint32_t frames = 0, renderMax = 0, renderTotal = 0;
  static bool notificationPending=false;static uint32_t notified=0;static uint64_t notificationAt=0;
  const uint64_t now = clockMs();
  int uartBudget=1024;while(vehicleUart.available()&&uartBudget-->0){vehicleGps.encode(vehicleUart.read());lastVehicleUart=now;}
  static uint64_t lastVehicleUpdate=0;
  if(now-lastVehicleUpdate>=500){refreshVehicle();lastVehicleUpdate=now;}
  WifiCommand wifiCommand;if(xQueueReceive(wifiQueue,&wifiCommand,0)==pdTRUE){mirror.station(wifiCommand.ssid,wifiCommand.password);Serial.println("MIRROR_STA_CONFIGURED");}
  AppearanceCommand appearanceCommand;
  if(xQueueReceive(appearanceQueue,&appearanceCommand,0)==pdTRUE){portENTER_CRITICAL(&appearanceMux);memcpy(appearance,appearanceCommand.bytes,12);portEXIT_CRITICAL(&appearanceMux);display.setAppearance(appearance);appearancePrefs.putBytes("value",appearance,12);appearanceTx->setValue(appearance,12);Serial.println("APPEARANCE_APPLIED");}

  if (activeEpoch != epoch.load()) {
    activeEpoch = epoch; session.reset();notificationPending=false;notifyResult=0;
    Serial.println(connected ? "BLE connected, waiting encrypted handshake" : "BLE disconnected, advertising");
  }
  if (overflow.exchange(false)) {
    session.reset();
    Serial.println("RX overflow: disconnecting to avoid partial/stale display");
    if (connected) server->disconnect(server->getConnId());
  }
  RxFrame frame;
  while (xQueueReceive(rxQueue, &frame, 0) == pdTRUE) {
    if (frame.epoch != activeEpoch || !connected || !encrypted) continue;
    // MTU callbacks may race onConnect; use the current negotiated ATT value.
    const uint16_t mtu=server->getPeerMTU(server->getConnId());
    if(mtu>=23)attBudget=std::min<uint16_t>(512,mtu-3);
    session.receive({frame.bytes, frame.length}, frame.at, attBudget);
  }
  mirror.poll(uint32_t(clockMs()));
  if(mirror.take()){uint32_t drawAt=millis();display.mirrorJpeg(mirror.data(),mirror.size());mirror.rendered(millis()-drawAt);}
  session.tick(clockMs());
  if (session.lost && connected) server->disconnect(server->getConnId());
  // NimBLE notification completion can arrive after notify() returns.
  // Consume it on the loop thread before advancing the outbound queue.
  if(notificationPending){int result=notifyResult.exchange(0);if(result!=0){if(result==1&&!session.outbound.empty()){session.outbound.pop_front();++notified;}notificationPending=false;}else if(clockMs()-notificationAt>1000){notificationPending=false;}}
  if (connected && encrypted && !notificationPending && !session.outbound.empty() && now-lastTx >= 15) {
    const auto& value = session.outbound.front();tx->setValue(value.data(), value.size());
    notifyResult=0;notificationPending=true;notificationAt=clockMs();tx->notify();lastTx=clockMs();
  }
  while (Serial.available()) {
    const char key = Serial.read();
    if (key == 'n') session.selectPage(moto::ble::DisplayPage::Navigation, now);
    if (key == 's') session.selectPage(moto::ble::DisplayPage::Speed, now);
    if (key == 'c') session.selectPage(moto::ble::DisplayPage::Compass, now);
  }
  // A full SPI frame takes time; drain BLE queues between frames and avoid
  // rendering during handshake/notification fragmentation.
  const uint32_t frameInterval=session.ready&&session.hasSnapshot?100:display.frameInterval();
  if (!mirror.active(uint32_t(now)) && now-lastRender >= frameInterval && session.outbound.empty() && uxQueueMessagesWaiting(rxQueue) == 0) {
    const uint64_t started=clockMs();
    display.render(session, connected, encrypted,uint32_t(started));lastRender=started;
    const uint32_t elapsed=clockMs()-started;++frames;renderTotal+=elapsed;renderMax=std::max(renderMax,elapsed);
  }
  if(now-perfAt>=5000){
    Serial.printf("RENDER FPS_X10=%lu AVG_MS=%lu MAX_MS=%lu SPI_MHZ=%lu\n",(unsigned long)(frames*10000/std::max<uint64_t>(1,now-perfAt)),(unsigned long)(frames?renderTotal/frames:0),(unsigned long)renderMax,(unsigned long)(kTftClockHz/1000000));
    frames=renderMax=renderTotal=0;perfAt=now;
  }
  if (now-lastLog >= 3000) {
    Serial.printf("BLE_TX_OK=%lu PENDING=%d OUT=%u\n",(unsigned long)notified,int(notificationPending),unsigned(session.outbound.size()));
    Serial.printf("MIRROR_STA_IP=%s\n",mirror.stationIp().c_str());
    Serial.printf("MIRROR_FRAMES=%lu ACTIVE=%d JPEG_BYTES=%u\n",(unsigned long)mirror.frames(),int(mirror.active(uint32_t(now))),unsigned(mirror.size()));
    Serial.printf("BLE=%d ENC=%d READY=%d NAV=%lu ROUTE=%d MAP=%lu STATE=%u FLAGS=%u SPEED=%u ERROR=%s\n",
        int(connected.load()), int(encrypted.load()), int(session.ready),
        (unsigned long)session.receivedSnapshots, int(session.routeVisible()),
        (unsigned long)session.map.scene_revision, unsigned(session.snapshot.state),
        unsigned(session.snapshot.flags), unsigned(session.snapshot.speed_deci_kph), session.error);
    Serial.printf("MAP_ROADS=%u GEOM_POINTS=%u GEOM_GEN=%lu NAV_GEN=%lu TOKEN_MATCH=%d\n",
        unsigned(session.map.roads.size()), unsigned(session.route.points.size()), (unsigned long)session.route.route_generation,
        (unsigned long)session.snapshot.route_generation,
        int(session.route.route_token == session.snapshot.route_token));
    Serial.printf("VEHICLE_GPS UART=%lu VALID=%d SAT=%lu AGE=%lu\n",(unsigned long)vehicleGps.charsProcessed(),int(vehicleGps.location.isValid()&&vehicleGps.location.age()<15000),(unsigned long)vehicleGps.satellites.value(),(unsigned long)vehicleGps.location.age());
   // 新增：GPS 报文校验与有效定位诊断
Serial.printf(
    "GPS_PARSE PASS=%lu FAIL=%lu FIX_SENTENCES=%lu\n",
    (unsigned long)vehicleGps.passedChecksum(),
    (unsigned long)vehicleGps.failedChecksum(),
    (unsigned long)vehicleGps.sentencesWithFix()
);

    lastLog = now;
  }
  delay(2);
}
