# ffmpeg java sample

Reimplementation of the official [transcoding](https://github.com/FFmpeg/FFmpeg/blob/n3.4.1/doc/examples/transcoding.c) ffmpeg sample program using [javacpp-presets/ffmpeg](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg) JNI bindings for [ffmpeg](https://www.ffmpeg.org/).

## Building 

`mvn package`

## Running

`java -jar target/javacpp-ffmpeg-1.0-SNAPSHOT.jar inp.mp4 out.mp4` 