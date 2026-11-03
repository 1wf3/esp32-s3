#include <Arduino.h>
#include <WiFi.h>

void setup() {
  Serial.begin(115200);
  delay(1500);
  Serial.println();
  Serial.println("=== Round Nav ESP32-S3 board check ===");
  Serial.printf("Chip: %s, cores: %d, revision: %d\n",
                ESP.getChipModel(), ESP.getChipCores(), ESP.getChipRevision());
  Serial.printf("Flash: %u bytes\n", ESP.getFlashChipSize());
  Serial.printf("PSRAM detected: %s; size: %u bytes\n",
                psramFound() ? "yes" : "no", ESP.getPsramSize());
  WiFi.mode(WIFI_STA);
  WiFi.disconnect();
  Serial.println("Wi-Fi initialized. No credentials are stored in this test.");
}

void loop() {
  Serial.printf("Alive: %lu seconds\n", millis() / 1000UL);
  delay(3000);
}
