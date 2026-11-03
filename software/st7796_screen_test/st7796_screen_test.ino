#include <Arduino.h>
#include <LovyanGFX.hpp>
#include <Wire.h>
#include <TinyGPSPlus.h>

// ST7796S 4-inch module. VCC/LED -> 3V3, GND -> GND.
// CS10 RESET14 DC9 SDI11 SCK12 SDO15(optional).
// CTP_SDA4 CTP_SCL5 CTP_INT6 CTP_RST7. GNSS TX17.
class Screen : public lgfx::LGFX_Device {
  lgfx::Bus_SPI bus;
  // Alternate initialization under test; controller identity is not yet confirmed.
  lgfx::Panel_ILI9488 panel;
 public:
  Screen() {
    auto b=bus.config();
    b.spi_host=SPI2_HOST; b.spi_mode=0;
    b.freq_write=1000000; b.freq_read=500000;
    b.spi_3wire=false; b.use_lock=true; b.dma_channel=0;
    b.pin_sclk=12; b.pin_mosi=11; b.pin_miso=15; b.pin_dc=9;
    bus.config(b); panel.setBus(&bus);
    auto p=panel.config();
    p.pin_cs=10; p.pin_rst=14; p.pin_busy=-1;
    p.memory_width=p.panel_width=320;
    p.memory_height=p.panel_height=480;
    p.readable=true; p.bus_shared=false;
    p.invert=false; p.rgb_order=false;
    panel.config(p); setPanel(&panel);
  }
  void printRegisters() {
    const uint8_t commands[]={0x04,0x09,0x0A,0x0B,0x0C,0xDA,0xDB,0xDC,0xD3};
    for(uint8_t cmd:commands) {
      uint32_t value=panel.readCommand(cmd,0,4);
      Serial.printf("LCD REG %02X: %02X %02X %02X %02X\n",cmd,
        unsigned(value&255),unsigned((value>>8)&255),
        unsigned((value>>16)&255),unsigned((value>>24)&255));
    }
  }
  void verifyWrites() {
    Serial.println("LCD WRITE/READ verification: MADCTL 00 then 40");
    for(uint8_t value: {uint8_t(0),uint8_t(0x40)}) {
      startWrite(); panel.writeCommand(0x36,1); panel.writeData(value,1); endWrite();
      delay(20);
      auto p=panel.config();
      for(uint8_t dummy: {uint8_t(1),uint8_t(8)}) {
        p.dummy_read_bits=dummy; panel.config(p);
        Serial.printf("WRITE %02X dummy=%u READ %08lX\n",value,dummy,
          (unsigned long)panel.readCommand(0x0B,0,4));
      }
    }
    auto p=panel.config(); p.dummy_read_bits=1; panel.config(p);
    setRotation(0); setRotation(1);
  }
  void displayRecovery() {
    // Standard display controls; avoid changing vendor power/gamma registers.
    startWrite(); panel.writeCommand(0x11,1); endWrite(); delay(150);
    startWrite(); panel.writeCommand(0x13,1); panel.writeCommand(0x29,1); endWrite();
    setColorDepth(24); setRotation(0); setRotation(1); delay(50);
    Serial.println("LCD recovery: sleep out, normal mode, display on, 18-bit pixel transfer");
    printRegisters();
  }
};
Screen lcd;
HardwareSerial gnss(1);
TinyGPSPlus gps;
uint32_t gnssBytes=0;
uint8_t touchAddress=0;
bool goodix=false;
uint32_t lastLog=0,lastTouch=0;

bool probe(uint8_t addr) {
  Wire.beginTransmission(addr); return Wire.endTransmission()==0;
}
bool readReg(uint16_t reg,uint8_t* buf,uint8_t size,bool wide=false) {
  Wire.beginTransmission(touchAddress);
  if(wide) Wire.write(reg>>8);
  Wire.write(reg&255);
  if(Wire.endTransmission(false)!=0) return false;
  if(Wire.requestFrom(touchAddress,size)!=size) return false;
  for(uint8_t i=0;i<size;++i) buf[i]=Wire.read();
  return true;
}
void scanTouch() {
  touchAddress=0; goodix=false;
  for(uint8_t a=8;a<120;++a) {
    if(!probe(a)) continue;
    Serial.printf("I2C ACK 0x%02X\n",a);
    if(a==0x38) touchAddress=a;
    else if(!touchAddress && (a==0x5D || a==0x14)) {
      touchAddress=a;
      uint8_t id[4];
      if(readReg(0x8140,id,4,true) && id[0]=='9') goodix=true;
      else touchAddress=0;
    }
  }
  if(touchAddress==0x38) {
    uint8_t id=0;
    if(readReg(0xA3,&id,1)) Serial.printf("FT-compatible address; chip register=0x%02X\n",id);
  }
}
void drawPage() {
  lcd.fillScreen(TFT_BLACK);
  lcd.drawRect(0,0,lcd.width(),lcd.height(),TFT_WHITE);
  lcd.setTextSize(3); lcd.setTextColor(TFT_WHITE,TFT_BLACK);
  lcd.setCursor(20,16); lcd.print("ILI9488 INIT TEST");
  lcd.setTextSize(2); lcd.setCursor(20,56); lcd.print("480 x 320 / SPI 1MHz");
  uint16_t colors[]={TFT_RED,TFT_GREEN,TFT_BLUE,TFT_WHITE};
  const char* labels[]={"RED","GREEN","BLUE","WHITE"};
  for(int i=0;i<4;++i) {
    lcd.fillRect(20+i*110,96,100,58,colors[i]);
    lcd.setTextColor(i==2?TFT_WHITE:TFT_BLACK,colors[i]);
    lcd.setCursor(27+i*110,116); lcd.print(labels[i]);
  }
  lcd.setTextColor(TFT_CYAN,TFT_BLACK);
  lcd.setCursor(20,176);
  if(touchAddress) lcd.printf("TOUCH I2C 0x%02X: tap / slide",touchAddress);
  else lcd.print("NO TOUCH: check CTP wires");
  lcd.setCursor(20,210); lcd.print("Raw touch: --, --");
  lcd.setTextColor(TFT_WHITE,TFT_BLACK);
  lcd.setCursor(20,245); lcd.print("GNSS UART17: waiting data");
  lcd.setTextSize(1); lcd.setCursor(20,292);
  lcd.print("Serial: c=colors d=registers w=verify r=18bit s=16bit");
}
void colorTest() {
  uint16_t colors[]={TFT_RED,TFT_GREEN,TFT_BLUE,TFT_WHITE,TFT_BLACK};
  for(uint16_t color:colors) {
    lcd.fillScreen(color);
    const uint32_t started=millis();
    while(millis()-started<450) {
      while(gnss.available()) {gps.encode(gnss.read());++gnssBytes;}
      delay(1);
    }
  }
  drawPage();
}
void setup() {
  Serial.begin(115200); delay(300);
  gnss.setRxBufferSize(4096); gnss.begin(9600,SERIAL_8N1,17,-1);
  Serial.println("ST7796S low-speed diagnostic / SPI 1MHz / no Wi-Fi");
  pinMode(10,OUTPUT); digitalWrite(10,HIGH);
  pinMode(14,OUTPUT); digitalWrite(14,HIGH); delay(100);
  digitalWrite(14,LOW); delay(100); digitalWrite(14,HIGH); delay(250);
  lcd.init(); lcd.setRotation(1);
  lcd.verifyWrites();
  lcd.displayRecovery();
  lcd.printRegisters();
  // Goodix address selection uses INT low during reset. Also valid reset for FT.
  pinMode(6,OUTPUT); digitalWrite(6,LOW);
  pinMode(7,OUTPUT); digitalWrite(7,LOW); delay(20);
  digitalWrite(7,HIGH); delay(100); pinMode(6,INPUT_PULLUP);
  Wire.begin(4,5,100000); Wire.setTimeOut(25);
  scanTouch(); colorTest();
  Serial.printf("DISPLAY %d x %d; touch=0x%02X\n",lcd.width(),lcd.height(),touchAddress);
}
void loop() {
  while(gnss.available()) {gps.encode(gnss.read());++gnssBytes;}
  if(millis()-lastTouch>=40 && touchAddress) {
    lastTouch=millis(); uint16_t x=0,y=0; bool pressed=false;
    if(!goodix) {
      uint8_t data[5];
      if(readReg(0x02,data,5) && (data[0]&15)>0 && (data[0]&15)<=2 && (data[1]>>6)!=1) {
        x=((data[1]&15)<<8)|data[2]; y=((data[3]&15)<<8)|data[4]; pressed=true;
      }
    } else {
      uint8_t status=0,data[4];
      if(readReg(0x814E,&status,1,true) && (status&0x80)) {
        if((status&15)>0 && (status&15)<=5 && readReg(0x8150,data,4,true)) {
          x=data[0]|(data[1]<<8);y=data[2]|(data[3]<<8);pressed=true;
        }
        Wire.beginTransmission(touchAddress); Wire.write(0x81);Wire.write(0x4E);Wire.write(0);Wire.endTransmission();
      }
    }
    if(pressed) {
      lcd.fillRect(20,207,420,25,TFT_BLACK);lcd.setTextSize(2);lcd.setTextColor(TFT_CYAN,TFT_BLACK);
      lcd.setCursor(20,210);lcd.printf("Raw touch: %u, %u",x,y);
      static uint32_t lastPrint=0;
      if(millis()-lastPrint>250) {lastPrint=millis();Serial.printf("TOUCH raw x=%u y=%u\n",x,y);}
    }
  }
  while(Serial.available()) {
    const char c=Serial.read();
    if(c=='c') colorTest();
    if(c=='i') {static bool inverted=false;inverted=!inverted;lcd.invertDisplay(inverted);drawPage();}
    if(c=='t') {scanTouch();drawPage();}
    if(c=='d') lcd.printRegisters();
    if(c=='w') {lcd.verifyWrites();lcd.printRegisters();}
    if(c=='r') {lcd.displayRecovery();colorTest();}
    if(c=='s') {lcd.setColorDepth(16);colorTest();lcd.printRegisters();}
  }
  if(millis()-lastLog>=2000) {
    lastLog=millis();
    const bool fix=gps.location.isValid() && gps.location.age()<3000;
    lcd.fillRect(20,242,440,26,TFT_BLACK);lcd.setTextSize(2);lcd.setTextColor(TFT_WHITE,TFT_BLACK);
    lcd.setCursor(20,245);lcd.printf("GNSS: %s / SAT %lu",gnssBytes?(fix?"FIX":"WAIT FIX"):"NO DATA",(unsigned long)gps.satellites.value());
    Serial.printf("RUN %lu s; GNSS bytes=%lu fix=%s; touch=0x%02X\n",millis()/1000UL,gnssBytes,fix?"yes":"no",touchAddress);
  }
  delay(2);
}
