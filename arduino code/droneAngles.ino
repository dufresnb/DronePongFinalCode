#include <SoftwareSerial.h>// import the serial library

SoftwareSerial ss(9, 10); // RX, TX
int BluetoothData; // the data given from Computer
float voltage;

void sendRangeFinder(int pin)
{
  digitalWrite(pin,LOW);
  delayMicroseconds(2);
  digitalWrite(pin,HIGH);
  delayMicroseconds(10);
  digitalWrite(pin,LOW);
}

float angles[10];

void setup() {
  ss.begin(9600);
  ss.println("Bluetooth On");
  pinMode(7,OUTPUT);
  pinMode(8,OUTPUT);
  pinMode(11, INPUT);
  pinMode(12, INPUT);
  for(int i=0;i<10;i++)
  {
    angles[i]=0;
  }
}

void loop() {

  sendRangeFinder(8);
  float inchesAway1 = pulseIn(11,HIGH)/74.0;
  delay(10);
  sendRangeFinder(7);
  float inchesAway2 = pulseIn(12,HIGH)/74.0;

  for(int i=9;i>0;--i)
  {
    angles[i]=angles[i-1];
  }
  //ss.println(inchesAway2);
  if(inchesAway1<50 || inchesAway2<50)
  {
    angles[0] = atan((inchesAway1-inchesAway2)/3.0)*180/3.141592;
  }
  else
  {
    angles[0] = 0;
  }
  if(angles[9]!=0 && angles[0]!=0)
  {
    float angleAve=0;
    for(int i=0;i<10;++i)
    {
      angleAve+=angles[i];
    }
    angleAve/=10.0;
    ss.println(angleAve);
  }
  else ss.println(0);
  delay(100);// prepare for next data ...
}
