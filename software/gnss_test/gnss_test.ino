#include <Arduino.h>
#include <TinyGPSPlus.h>

// ATGM336H: VCC -> 3V3, GND -> GND, TX -> GPIO17.
// Module RX and PPS remain disconnected. LCD wiring can stay in place.
constexpr int GNSS_RX_PIN = 17;
constexpr uint32_t GNSS_BAUD = 9600;
HardwareSerial gnssSerial(1);
TinyGPSPlus gps;
bool rawOutput = true;
uint32_t lastReport = 0;
uint32_t receivedBytes = 0;

void setup() {
  Serial.begin(115200);
  delay(1500);
  gnssSerial.setRxBufferSize(2048);
  gnssSerial.begin(GNSS_BAUD, SERIAL_8N1, GNSS_RX_PIN, -1);
  Serial.println("\nATGM336H test: UART1 RX=GPIO17, 9600 baud");
  Serial.println("Raw NMEA for the first 15 seconds. Send r to toggle raw output.");
  Serial.println("Place antenna ceramic face UP outdoors. Waiting for a fresh fix...");
}

void loop() {
  while (Serial.available()) {
    const char command = Serial.read();
    if (command == 'r' || command == 'R') rawOutput = !rawOutput;
  }
  while (gnssSerial.available()) {
    const char c = gnssSerial.read();
    ++receivedBytes;
    gps.encode(c);
    if (rawOutput) Serial.write(c);
  }
  static bool initialRawEnded = false;
  if (!initialRawEnded && millis() >= 15000) {
    initialRawEnded = true;
    rawOutput = false;
  }
  if (millis() - lastReport >= 1000) {
    lastReport = millis();
    Serial.printf("\nbytes=%lu valid_sentences=%lu checksum_errors=%lu ",
                  (unsigned long)receivedBytes,
                  (unsigned long)gps.passedChecksum(),
                  (unsigned long)gps.failedChecksum());
    if (receivedBytes == 0) {
      Serial.println("NO UART DATA: check power, GND, module TX -> GPIO17.");
    } else if (!gps.location.isValid() || gps.location.age() > 3000) {
      Serial.print("WAIT FIX / STALE FIX");
      if (gps.satellites.isValid() && gps.satellites.age() < 3000)
        Serial.printf(" satellites=%lu", (unsigned long)gps.satellites.value());
      Serial.println();
    } else {
      Serial.printf("FIX lat=%.6f lon=%.6f age=%lu ms",
                    gps.location.lat(), gps.location.lng(),
                    (unsigned long)gps.location.age());
      if (gps.satellites.isValid() && gps.satellites.age() < 3000)
        Serial.printf(" satellites=%lu", (unsigned long)gps.satellites.value());
      if (gps.hdop.isValid() && gps.hdop.age() < 3000)
        Serial.printf(" HDOP=%.1f", gps.hdop.hdop());
      if (gps.speed.isValid() && gps.speed.age() < 3000)
        Serial.printf(" speed=%.1f km/h", gps.speed.kmph());
      else
        Serial.print(" speed=unavailable");
      Serial.println();
    }
  }
  delay(1);
}
