#include<Arduino.h>
#define infinity 10
int pin1 = 3, pin2 = 9, pinA = 5, pinB = 11;
volatile int state;
void setup() {
    Serial.begin(9600);
    pinMode(pin1, OUTPUT);
    pinMode(pin2, OUTPUT);
    pinMode(pinA, OUTPUT);
    pinMode(pinB, OUTPUT);
    //attachInterrupt(0,judge(),CHANGE);
}
void loop()
{
    int i, j;
    while (Serial.available())
    {
        char c = Serial.read();
        //启动或加速，analogWrite调节占空比。
        switch (c)
        {
        case '1': {
            for (i = 0; i <= 150; i++) {
                digitalWrite(pin1, LOW);
                analogWrite(pinA, i);
                digitalWrite(pin2, LOW);
                analogWrite(pinB, i);
                delay(10);
            }
            c = Serial.read(); }
                  ; break;
                  /* // while(c!='0') {
                       char m=Serial.read();
                       //监视A的接收。应该改进成可连续加速。
                        if(m='1'){
                          analogWrite(pinA,i);
                          analogWrite(pinB,i);
                          char m=Serial.read();
                        if(m=='A'){*/
        case 'A': {
            digitalWrite(pinA, HIGH);
            digitalWrite(pinB, HIGH);
            delay(500);
            analogWrite(pinA, 150);
            analogWrite(pinB, 150);
            state = 1;
            char c = Serial.read(); }
                  ; break;
                  // if(m=='C'){
        case 'C': {
            digitalWrite(pin1, LOW);
            analogWrite(pinA, 150);
            digitalWrite(pin2, LOW);
            digitalWrite(pinB, LOW);
            delay(300);
            //   digitalWrite(pinA,LOW);
            for (i = 0; i <= 150; i++) {
                //   digitalWrite(pin1,LOW);
                 //  analogWrite(pinA,i);
                 //  digitalWrite(pin2,LOW);
                analogWrite(pinB, i);
                delay(1);
            }
            state = 2;
            char c = Serial.read();
        }; break;
            //if(m=='D'){
        case 'D': {
            digitalWrite(pin1, LOW);
            digitalWrite(pinA, LOW);
            digitalWrite(pin2, LOW);
            analogWrite(pinB, 150);
            delay(300);
            //   digitalWrite(pinB,LOW);            
            for (i = 0; i <= 150; i++) {
                //   digitalWrite(pin1,LOW);
                analogWrite(pinA, i);
                //   digitalWrite(pin2,LOW);
                //   analogWrite(pinB,i);
                delay(1);
            }
            /*   digitalWrite(pin1,LOW);
               analogWrite(pinA,i+1);
               digitalWrite(pin2,LOW);
               analogWrite(pinB,i+1);
                  delay(10);
                    m=c;*/state = 3;
            c = Serial.read();
        }; break;
        case 'B': {
            for (j = 0; j <= 150; j++) {
                digitalWrite(pin1, j);
                analogWrite(pinA, LOW);
                digitalWrite(pin2, j);
                analogWrite(pinB, LOW);
                delay(10);
            }
            c = Serial.read();
            while (c != '1'&c != '0')
            {
                if (c == 'C') {
                    digitalWrite(pinB, LOW);
                    digitalWrite(pin1, 150);
                    digitalWrite(pin2, LOW);
                    analogWrite(pinA, LOW);
                    delay(300);
                    for (i = 0; i <= 150; i++) {
                        analogWrite(pin2, i);
                        delay(1);
                    }
                }
                if (c == 'D') {
                    digitalWrite(pin1, LOW);
                    analogWrite(pinB, LOW);
                    digitalWrite(pinA, LOW);
                    digitalWrite(pin2, 150);
                    delay(300);
                    for (i = 0; i <= 150; i++) {
                        analogWrite(pin1, i);
                        delay(1);
                    }
                }
                c = Serial.read();
            }
            c = Serial.read();  }
                  state = 4; ; break;
        case '0': {
            for (i = 149; i >= 0; i--)
            {
                if (state == 4) {
                    digitalWrite(pin1, i);
                    digitalWrite(pin2, i);
                    analogWrite(pinA, LOW);
                    analogWrite(pinB, LOW);
                    delay(5);
                }
                else {
                    digitalWrite(pin1, LOW);
                    digitalWrite(pin2, LOW);
                    analogWrite(pinA, i);
                    analogWrite(pinB, i);
                    delay(5);
                }
            }
            digitalWrite(pin1, LOW);
            digitalWrite(pin2, LOW);
            digitalWrite(pinA, LOW);
            digitalWrite(pinB, LOW);
            c = Serial.read();
            state = 0;
        }; break;
        }
    }
}