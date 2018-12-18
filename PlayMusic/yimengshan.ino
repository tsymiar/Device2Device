int speakerPin = 9;
int length = 67;
int led = 13; // the number of notes 
char notes[] = "DGEDEGEDCD DGDEGEDCaC CEDEGDbaga CDbageg  0 CEDEgDbaga  CDbageg g  "; //以音名展开的旋律 a space represents a rest 
float beats[] = {
    1, 1, 0.5, 0.5, 1, 1, 1, 0.5, 0.5, 1, 1, 1, 1, 1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1, 1, 1, 1, 0.5, 0.5, 1, 0.5, 0.5, 1, 1, 1, 1, 1.5, 0.5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0.5, 0.5, 1, 0.5, 0.5, 0.5, 0.5, 1, 1, 1, 1.5, 0.5, 0.5, 0.5, 1, 1, 1, 1, 1, 1, 1 //音符持续时值
};
int tempo = 400; //一个四分音符的时值
void playTone(int tone, int duration) {
    for (long i = 0; i < duration * 1000L; i += tone * 2)
    {
        digitalWrite(speakerPin, HIGH);
        delayMicroseconds(tone);
        digitalWrite(speakerPin, LOW);
        delayMicroseconds(tone);
    }
}
void playNote(char note, int duration) {
    char names[] = {
           'B', 'A', 'G', 'F', 'E', 'D', 'C', 'b','a', 'g', 'f','e', 'd','c'
    };
    int ones[] = {
         441, 495, 556, 589, 661, 742, 833, 882,	990,	1112,	1178,	1322,	1484,	1665
    };
    int tones[14];
    int j;
    for (j = 0; j < 14; j++)
        tones[j] = ones[j]; /* 音名与频率的对应关系。play the tone corresponding to the note name */
    for (int i = 0; i < 14; i++)
    {
        if (names[i] == note)
        {
            playTone(tones[i], duration);
        }
    }
}
void setup() {
    pinMode(speakerPin, OUTPUT);
    pinMode(led, OUTPUT);
}
void loop() {
    for (int i = 0; i < length; i++)
    {
        if (notes[i] == '0')
            delay(beats[i] * tempo);
        else
        {
            if (notes[i] == ' ')
            {
                digitalWrite(led, HIGH);
                beats[i] += 1;
                delay(beats[i]);
                digitalWrite(led, LOW);
            }
            playNote(notes[i], beats[i] * tempo);
        } // pause between notes 
        delay(tempo / 2);
    }
}