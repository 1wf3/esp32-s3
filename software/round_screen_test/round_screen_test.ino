#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <TinyGPSPlus.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include "nav_renderer.h"
#include "wifi_secrets.h"
#include "amap_secrets.h"
#include "amap_root_ca.h"

// ESP32-S3 N8R8 -> 1.28-inch GC9A01 SPI round LCD
// LCD: GND->GND, VCC->3V3, SCL->GPIO12, SDA->GPIO11,
//      RES->GPIO14, DC->GPIO9, CS->GPIO10, BLK->GPIO13.
// The LCD's SCL/SDA labels are SPI clock/data, not I2C.

constexpr int PIN_LCD_SCLK = 12;
constexpr int PIN_LCD_MOSI = 11;
constexpr int PIN_LCD_DC   = 9;
constexpr int PIN_LCD_CS   = 10;
constexpr int PIN_LCD_RST  = 14;
constexpr int PIN_LCD_BL   = 13;

class RoundLCD : public lgfx::LGFX_Device {
  lgfx::Bus_SPI bus_;
  lgfx::Panel_GC9A01 panel_;

 public:
  RoundLCD() {
    auto bus_cfg = bus_.config();
    bus_cfg.spi_host = SPI2_HOST;
    bus_cfg.spi_mode = 0;
    bus_cfg.freq_write = 20000000;  // Conservative for breadboard wires.
    bus_cfg.freq_read = 10000000;
    bus_cfg.spi_3wire = false;
    bus_cfg.use_lock = true;
    bus_cfg.dma_channel = 0;
    bus_cfg.pin_sclk = PIN_LCD_SCLK;
    bus_cfg.pin_mosi = PIN_LCD_MOSI;
    bus_cfg.pin_miso = -1;
    bus_cfg.pin_dc = PIN_LCD_DC;
    bus_.config(bus_cfg);

    panel_.setBus(&bus_);
    auto panel_cfg = panel_.config();
    panel_cfg.pin_cs = PIN_LCD_CS;
    panel_cfg.pin_rst = PIN_LCD_RST;
    panel_cfg.pin_busy = -1;
    panel_cfg.panel_width = 240;
    panel_cfg.panel_height = 240;
    panel_cfg.readable = false;
    panel_cfg.bus_shared = false;
    panel_cfg.invert = true;  // This module showed inverse colors (black as white).
    panel_.config(panel_cfg);
    setPanel(&panel_);
  }
};


RoundLCD lcd;
NavRenderer nav(lcd);
HardwareSerial gnssSerial(1);
TinyGPSPlus gps;
// GNSS: 3V3, GND, TX -> GPIO17. Module RX/PPS unconnected.
constexpr navmath::Geo DESTINATION{112.460886,34.676011}; // AMap POI: Luoyang Yingtianmen
struct Request { navmath::Geo wgs; };
struct Result {
  JsonDocument doc;
  double correctionLon=0, correctionLat=0;
  char error[32]="";
};
QueueHandle_t requestQueue=nullptr, resultQueue=nullptr;
bool busy=false;
bool clockStarted=false;
uint32_t lastWifi=0, lastRequest=0, fixStamp=0, lastUpdate=0;
uint32_t offRouteSince=0;
double correctionLon=0, correctionLat=0;

bool fetchJson(const String& url, JsonDocument& doc, char* error) {
  WiFiClientSecure tls;
  tls.setCACert(AMAP_ROOT_CA);
  tls.setTimeout(12000);
  HTTPClient http;
  http.setTimeout(12000);
  http.setConnectTimeout(8000);
  if (!http.begin(tls,url)) { snprintf(error,32,"HTTPS ERROR"); return false; }
  const int code=http.GET();
  if (code!=HTTP_CODE_OK) {
    snprintf(error,32,"HTTP %d",code); http.end(); return false;
  }
  if (http.getSize()>300000) { snprintf(error,32,"RESPONSE TOO BIG"); http.end(); return false; }
  String payload=http.getString(); http.end();
  const auto e=deserializeJson(doc,payload);
  if(e) { snprintf(error,32,"JSON ERROR"); return false; }
  if(strcmp(doc["status"] | "0","1")!=0) {
    snprintf(error,32,"AMAP %s",doc["infocode"] | "ERROR"); return false;
  }
  return true;
}

void routeWorker(void*) {
  Request req;
  for(;;) {
    if(xQueueReceive(requestQueue,&req,portMAX_DELAY)!=pdTRUE) continue;
    Result* result=new(std::nothrow) Result;
    if(!result) { xQueueSend(resultQueue,&result,portMAX_DELAY); continue; }
    navmath::Geo origin{};
    JsonDocument conversion;
    String url="https://restapi.amap.com/v3/assistant/coordinate/convert?key=";
    url+=AMAP_KEY;
    url+="&coordsys=gps&locations="+String(req.wgs.lon,6)+","+String(req.wgs.lat,6);
    if(fetchJson(url,conversion,result->error)) {
      const char* location=conversion["locations"] | "";
      if(sscanf(location,"%lf,%lf",&origin.lon,&origin.lat)!=2 || origin.lon<1 || origin.lat<1)
        snprintf(result->error,32,"COORDINATE ERROR");
      else {
        const auto approximate=navmath::gcj02(req.wgs);
        result->correctionLon=origin.lon-approximate.lon;
        result->correctionLat=origin.lat-approximate.lat;
        url="https://restapi.amap.com/v5/direction/driving?key=";
        url+=AMAP_KEY;
        url+="&origin="+String(origin.lon,6)+","+String(origin.lat,6);
        url+="&destination="+String(DESTINATION.lon,6)+","+String(DESTINATION.lat,6);
        url+="&strategy=0&show_fields=polyline,navi";
        fetchJson(url,result->doc,result->error);
      }
    }
    // Ownership is transferred to loop(); only loop() changes/draws the route.
    xQueueSend(resultQueue,&result,portMAX_DELAY);
  }
}

bool freshFix() {
  return gps.location.isValid() && gps.location.age()<3000 &&
         gps.hdop.isValid() && gps.hdop.age()<3000 && gps.hdop.hdop()<=5;
}
void startRequest() {
  if(busy || !freshFix() || WiFi.status()!=WL_CONNECTED) return;
  time_t now; time(&now);
  if(now<1700000000) { nav.setStatus("WAIT NETWORK TIME"); return; }
  Request r{{gps.location.lng(),gps.location.lat()}};
  if(xQueueSend(requestQueue,&r,0)==pdTRUE) {
    busy=true; lastRequest=millis(); nav.setStatus("AMAP REQUEST");
    Serial.println("AMAP route request: current GNSS fix -> Luoyang Yingtianmen");
  }
}

bool mathSelfTest() {
  navmath::Point line[]={{0,0,0},{0,100,100},{100,100,200}};
  auto a=navmath::match(line,3,{3,45,0},false,0,40);
  auto b=navmath::match(line,3,{0,95,0},true,45,20);
  auto c=navmath::match(line,3,{70,50,0},false,0,40);
  const auto tiny=navmath::local({112.400001,34.600001},{112.4,34.6});
  return a.found && fabsf(a.progress-45)<.01f && fabsf(a.distance-3)<.01f &&
         !b.found && c.distance>45 && tiny.east>.08f && tiny.east<.1f &&
         tiny.north>.1f && tiny.north<.12f;
}
void setup() {
  Serial.begin(115200); delay(300);
  gnssSerial.setRxBufferSize(8192);
  gnssSerial.begin(9600,SERIAL_8N1,17,-1);
  pinMode(PIN_LCD_BL,OUTPUT); digitalWrite(PIN_LCD_BL,HIGH);
  lcd.init(); lcd.setRotation(0);
  if(!nav.begin()) { Serial.println("FATAL: frame buffer allocation failed"); while(true) delay(1000); }
  if(!mathSelfTest()) { nav.setStatus("MATH TEST FAILED"); Serial.println("MATH TEST FAILED"); while(true) {nav.tick(); delay(5);} }
  Serial.println("NAV math self-test PASS. Real GNSS only, no simulation.");
  Serial.println("Destination: Luoyang Yingtianmen. Send r to request a new route; p for local coordinates.");
  requestQueue=xQueueCreate(1,sizeof(Request));
  resultQueue=xQueueCreate(1,sizeof(Result*));
  if(!requestQueue || !resultQueue || xTaskCreatePinnedToCore(routeWorker,"amap",16384,nullptr,1,nullptr,0)!=pdPASS) {
    nav.setStatus("NETWORK TASK ERROR"); while(true) {nav.tick(); delay(5);}
  }
  WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true);
  WiFi.begin("iPhone",HOTSPOT_PASSWORD); lastWifi=millis();
  nav.setStatus("WAIT GNSS / WIFI");
}

void loop() {
  while(gnssSerial.available()) gps.encode(gnssSerial.read());
  if(gps.location.isUpdated()) { gps.location.lat(); fixStamp=millis(); }
  const bool fresh=freshFix();
  auto geo=navmath::gcj02({gps.location.lng(),gps.location.lat()});
  geo.lon+=correctionLon; geo.lat+=correctionLat;
  nav.update(geo,fresh,gps.speed.kmph(),gps.speed.isValid() && gps.speed.age()<3000,
             gps.course.deg(),gps.course.isValid() && gps.course.age()<3000,fixStamp);
  if(WiFi.status()==WL_CONNECTED && !clockStarted) {
    configTime(0,0,"ntp.aliyun.com","pool.ntp.org"); clockStarted=true;
  }
  if(WiFi.status()!=WL_CONNECTED && millis()-lastWifi>20000) {
    lastWifi=millis(); WiFi.reconnect();
  }
  Result* result=nullptr;
  if(xQueueReceive(resultQueue,&result,0)==pdTRUE) {
    busy=false;
    if(!result) { nav.setStatus("NO JSON MEMORY"); Serial.println("AMAP result allocation failed"); }
    else {
      JsonArray paths=result->doc["route"]["paths"].as<JsonArray>();
      if(result->error[0]) { nav.setStatus(result->error); Serial.println(result->error); }
      else if(paths.isNull() || !paths.size()) { nav.setStatus("NO ROUTE"); Serial.println("AMAP NO ROUTE"); }
      else if(!nav.load(paths[0]["steps"].as<JsonArray>(),atoi(paths[0]["distance"] | "0"))) {
        nav.setStatus("ROUTE TOO BIG/BAD"); Serial.println("AMAP route invalid or exceeds 2048 points/128 steps");
      } else {
        correctionLon=result->correctionLon; correctionLat=result->correctionLat;
        nav.setStatus("LIVE GNSS");
        Serial.printf("ROUTE READY: %u points, API %d m\n",unsigned(nav.pointCount()),nav.apiDistance());
      }
      delete result;
    }
  }
  if(!nav.ready() && !busy && (!lastRequest || millis()-lastRequest>=60000)) startRequest();
  if(nav.ready() && fresh && nav.offRoute()) {
    if(!offRouteSince) offRouteSince=millis();
    if(millis()-offRouteSince>=15000 && millis()-lastRequest>=60000) startRequest();
  } else offRouteSince=0;
  while(Serial.available()) {
    char command=Serial.read();
    if(command=='r' || command=='R') {
      if(millis()-lastRequest>=10000) startRequest();
      else Serial.println("Please wait 10 s between manual requests.");
    }
    if(command=='p' || command=='P') {
      if(fresh) Serial.printf("LOCAL WGS84: %.6f,%.6f\n",gps.location.lng(),gps.location.lat());
      else Serial.println("NO FRESH GNSS FIX");
    }
  }
  if(millis()-lastUpdate>=2000) {
    lastUpdate=millis();
    Serial.printf("GNSS %s SAT=%lu HDOP=%.1f SPEED=",fresh?"FIX":"WAIT/STALE",(unsigned long)gps.satellites.value(),gps.hdop.hdop());
    if(fresh && gps.speed.isValid() && gps.speed.age()<3000) Serial.printf("%.1f",gps.speed.kmph()); else Serial.print("--");
    Serial.printf(" km/h; OFFSET=%.1f m; ROUTE=%s %.0f/%.0f m; WIFI=%s%s\n",nav.crossTrack(),nav.ready()?(nav.offRoute()?"OFF":"ON"):"WAIT",
      nav.progress(),nav.remaining(),WiFi.status()==WL_CONNECTED?"OK":"WAIT",nav.arrived()?" ARRIVED":"");
  }
  nav.tick(); delay(2);
}
